package com.westart.ai.westart.util;

import org.jcodec.codecs.aac.AACDecoder;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.UUID;

/**
 * 文件格式转换工具。
 * 先将源文件解码为二进制编码（PCM），再将二进制编码译码为目标格式文件。
 */
public class FileFormatConverter {

    private static final File TEMP_DIR = new File(System.getProperty("java.io.tmpdir"), "westart_convert");

    static {
        TEMP_DIR.mkdirs();
    }

    /**
     * 将音频文件转为 WAV 格式。
     *
     * @param srcData  源文件字节
     * @param srcMime  源文件 MIME 类型（audio/wav, audio/mpeg, audio/mp4）
     * @return WAV 文件字节
     */
    public static byte[] toWav(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;

        return switch (srcMime) {
            case "audio/wav" -> srcData;
            case "audio/mpeg" -> decodeMp3ToPcm(srcData);
            case "audio/mp4" -> decodeM4aToPcm(srcData);
            default -> throw new IOException("不支持的格式: " + srcMime);
        };
    }

    /* ==================== MP3 → PCM ==================== */

    private static byte[] decodeMp3ToPcm(byte[] mp3Data) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(mp3Data);
             AudioInputStream ais = AudioSystem.getAudioInputStream(bais)) {
            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, 16000, 16, 1, 2, 16000, false);
            try (AudioInputStream converted = AudioSystem.getAudioInputStream(target, ais);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                AudioSystem.write(converted, AudioFileFormat.Type.WAVE, baos);
                return baos.toByteArray();
            }
        } catch (Exception e) {
            throw new IOException("MP3解码失败", e);
        }
    }

    /* ==================== M4A/AAC → PCM ==================== */

    private static byte[] decodeM4aToPcm(byte[] m4aData) throws IOException {
        File tmp = null;
        try {
            tmp = saveTemp(m4aData, ".m4a");
            try (RandomAccessFile raf = new RandomAccessFile(tmp, "r");
                 FileChannel ch = raf.getChannel();
                 FileChannelWrapper fcw = new FileChannelWrapper(ch)) {
                MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(fcw);
                java.util.List<DemuxerTrack> tracks = demuxer.getAudioTracks();
                if (tracks.isEmpty()) throw new IOException("没有音频轨道");

                DemuxerTrack track = tracks.get(0);
                Packet first = track.nextFrame();
                if (first == null) throw new IOException("没有音频帧");

                AACDecoder decoder = new AACDecoder(first.getData());
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                ByteBuffer buf = ByteBuffer.allocate(8192);

                decodeFrame(decoder, first, buf, pcm);
                Packet p;
                while ((p = track.nextFrame()) != null) decodeFrame(decoder, p, buf, pcm);

                return writeWav(pcm.toByteArray(), 16000);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("M4A解码失败", e);
        } finally {
            deleteFile(tmp);
        }
    }

    private static void decodeFrame(AACDecoder decoder, Packet packet, ByteBuffer out, ByteArrayOutputStream pcm)
            throws IOException {
        out.clear();
        decoder.decodeFrame(packet.getData(), out);
        out.flip();
        byte[] b = new byte[out.remaining()];
        out.get(b);
        pcm.write(b);
    }

    /* ==================== PCM → WAV ==================== */

    private static byte[] writeWav(byte[] pcm, int sampleRate) throws IOException {
        int channels = 1, bits = 16;
        int byteRate = sampleRate * channels * bits / 8;
        int align = channels * bits / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        writeStr(out, "RIFF");
        writeLe32(out, 36 + pcm.length);
        writeStr(out, "WAVE");
        writeStr(out, "fmt ");
        writeLe32(out, 16);
        writeLe16(out, 1);
        writeLe16(out, channels);
        writeLe32(out, sampleRate);
        writeLe32(out, byteRate);
        writeLe16(out, align);
        writeLe16(out, bits);
        writeStr(out, "data");
        writeLe32(out, pcm.length);
        out.write(pcm);
        return out.toByteArray();
    }

    private static void writeStr(ByteArrayOutputStream out, String s) throws IOException {
        for (byte b : s.getBytes("US-ASCII")) out.write(b);
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) throws IOException {
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF); out.write((v >> 24) & 0xFF);
    }

    private static void writeLe16(ByteArrayOutputStream out, int v) throws IOException {
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
    }

    /* ==================== Word → PDF ==================== */

    /**
     * 将 Word 文档转为 PDF（转为 Markdown 后调用云端 API 渲染）。
     *
     * @param srcData Word 文件字节
     * @param srcMime MIME 类型（application/vnd.openxmlformats-officedocument.wordprocessingml.document）
     * @return PDF 文件字节
     */
    public static byte[] toPdf(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        if (!"application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(srcMime)) {
            throw new IOException("不支持的格式: " + srcMime + "（仅支持 .docx）");
        }

        String apiKey = System.getenv("UAPIS_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("UAPIS_API_KEY 未设置，请注册 https://uapis.cn 获取");
        }

        File tmp = null;
        try {
            tmp = saveTemp(srcData, ".docx");
            String markdown;
            try (java.io.InputStream is = new java.io.FileInputStream(tmp)) {
                markdown = docxToMarkdown(is);
            }

            okhttp3.MediaType jsonType = okhttp3.MediaType.get("application/json; charset=utf-8");
            String requestBody = "{\"text\":" + new com.google.gson.Gson().toJson(markdown)
                    + ",\"theme\":\"github\",\"paper_size\":\"A4\"}";
            okhttp3.RequestBody body = okhttp3.RequestBody.create(requestBody, jsonType);
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url("https://uapis.cn/api/v1/text/markdown-to-pdf")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            try (okhttp3.Response resp = client.newCall(request).execute()) {
                if (!resp.isSuccessful()) {
                    String err = resp.body() != null ? resp.body().string() : "";
                    throw new IOException("API 返回错误: " + resp.code() + " " + err);
                }
                byte[] pdf = resp.body() != null ? resp.body().bytes() : null;
                if (pdf == null || pdf.length == 0) {
                    throw new IOException("API 返回空 PDF");
                }
                return pdf;
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Word转PDF失败", e);
        } finally {
            deleteFile(tmp);
        }
    }

    private static String docxToMarkdown(java.io.InputStream docxStream) throws IOException {
        org.apache.poi.xwpf.usermodel.XWPFDocument doc =
                new org.apache.poi.xwpf.usermodel.XWPFDocument(docxStream);
        StringBuilder md = new StringBuilder();

        // 收集所有段落和表格的插入位置
        java.util.Iterator<org.apache.poi.xwpf.usermodel.IBodyElement> iter = doc.getBodyElementsIterator();
        while (iter.hasNext()) {
            org.apache.poi.xwpf.usermodel.IBodyElement elem = iter.next();
            if (elem.getElementType() == org.apache.poi.xwpf.usermodel.BodyElementType.PARAGRAPH) {
                org.apache.poi.xwpf.usermodel.XWPFParagraph p = (org.apache.poi.xwpf.usermodel.XWPFParagraph) elem;
                String text = p.getText().trim();
                if (text.isEmpty()) {
                    md.append("\n");
                    continue;
                }
                if (p.getStyleID() != null && p.getStyleID().startsWith("Heading")) {
                    int level = 1;
                    try { level = Integer.parseInt(p.getStyleID().replace("Heading", "")); } catch (Exception ignored) {}
                    if (level < 1) level = 1; if (level > 6) level = 6;
                    md.append("#".repeat(level)).append(" ").append(text).append("\n\n");
                } else {
                    md.append(text).append("\n\n");
                }
            } else if (elem.getElementType() == org.apache.poi.xwpf.usermodel.BodyElementType.TABLE) {
                org.apache.poi.xwpf.usermodel.XWPFTable t = (org.apache.poi.xwpf.usermodel.XWPFTable) elem;
                java.util.List<org.apache.poi.xwpf.usermodel.XWPFTableRow> rows = t.getRows();
                if (rows.isEmpty()) continue;

                // 表头分隔线
                boolean headerWritten = false;
                for (int ri = 0; ri < rows.size(); ri++) {
                    org.apache.poi.xwpf.usermodel.XWPFTableRow row = rows.get(ri);
                    java.util.List<String> cells = new java.util.ArrayList<>();
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText().trim().replace("\n", " "));
                    }
                    md.append("| ").append(String.join(" | ", cells)).append(" |\n");
                    if (!headerWritten) {
                        java.util.List<String> sep = new java.util.ArrayList<>();
                        for (int ci = 0; ci < cells.size(); ci++) sep.add("---");
                        md.append("| ").append(String.join(" | ", sep)).append(" |\n");
                        headerWritten = true;
                    }
                }
                md.append("\n");
            }
        }
        return md.toString();
    }

    private static String extractDocxText(org.docx4j.openpackaging.packages.WordprocessingMLPackage wordPkg)
            throws IOException {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            org.docx4j.TextUtils.extractText(wordPkg.getMainDocumentPart().getJaxbElement(), sw);
            return sw.toString();
        } catch (Exception e) {
            throw new IOException("提取Word文本失败", e);
        }
    }

    /* ==================== PDF → Word ==================== */

    /**
     * 将 PDF 文件提取文本后生成 .docx 文件。
     *
     * @param srcData PDF 文件字节
     * @param srcMime MIME 类型（application/pdf）
     * @return .docx 文件字节
     */
    public static byte[] toDocx(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        if (!"application/pdf".equals(srcMime)) {
            throw new IOException("不支持的格式: " + srcMime + "（仅支持 PDF）");
        }

        File tmp = null;
        try {
            tmp = saveTemp(srcData, ".pdf");
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(tmp)) {
                org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setWordSeparator(" ");
                String text = stripper.getText(doc);

                org.docx4j.openpackaging.packages.WordprocessingMLPackage wordPkg =
                        org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
                String[] lines = text.split("\\r?\\n");
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    org.docx4j.wml.P para = wordPkg.getMainDocumentPart().addParagraphOfText(line.trim());
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                wordPkg.save(baos);
                return baos.toByteArray();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("PDF转Word失败", e);
        } finally {
            deleteFile(tmp);
        }
    }

    /* ==================== 工具 ==================== */

    private static File saveTemp(byte[] data, String suffix) throws IOException {
        File f = new File(TEMP_DIR, UUID.randomUUID().toString() + suffix);
        try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
        return f;
    }

    private static void deleteFile(File f) {
        if (f != null && f.exists()) { try { Files.delete(f.toPath()); } catch (IOException ignored) {} }
    }
}
