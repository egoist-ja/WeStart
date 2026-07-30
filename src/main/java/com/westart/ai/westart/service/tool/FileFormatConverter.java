package com.westart.ai.westart.service.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jcodec.codecs.aac.AACDecoder;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class FileFormatConverter {
    
    private static final File TEMP_DIR = new File(
            System.getProperty("java.io.tmpdir"), "westart_convert");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    static {
        TEMP_DIR.mkdirs();
    }

    // ==================== 音频转换 ====================

    public byte[] toWav(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        return switch (srcMime) {
            case "audio/wav" -> srcData;
            case "audio/mpeg" -> decodeMp3ToPcm(srcData);
            case "audio/mp4" -> decodeM4aToPcm(srcData);
            default -> throw new IOException("不支持的音频格式: " + srcMime);
        };
    }

    // ==================== 文档转换 ====================

    public byte[] toPdf(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        return switch (srcMime) {
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                File tmp = null;
                try {
                    tmp = saveTemp(srcData, ".docx");
                    String markdown;
                    try (FileInputStream fis = new FileInputStream(tmp)) {
                        markdown = docxToMarkdown(fis);
                    }
                    yield callMarkdownToPdfApi(markdown, "github", "A4");
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("Word转PDF失败", e);
                } finally {
                    deleteFile(tmp);
                }
            }
            case "text/plain" -> callMarkdownToPdfApi(decodeText(srcData), "github", "A4");
            case "text/markdown" -> callMarkdownToPdfApi(decodeText(srcData), "github", "A4");
            default -> throw new IOException("不支持的格式: " + srcMime + "（仅支持 DOCX / TXT / Markdown）");
        };
    }

    public byte[] toDocx(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        return switch (srcMime) {
            case "application/pdf" -> pdfToDocx(srcData);
            case "text/markdown" -> mdToDocx(decodeText(srcData));
            case "text/plain" -> txtToDocx(decodeText(srcData));
            default -> throw new IOException("不支持的格式: " + srcMime + "（仅支持 PDF / Markdown / TXT）");
        };
    }

    public String toTxt(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        return switch (srcMime) {
            case "text/plain" -> decodeText(srcData);
            case "text/markdown" -> mdToPlainText(decodeText(srcData));
            case "application/pdf" -> extractPdfText(srcData);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    extractDocxText(srcData);
            default -> throw new IOException("不支持的格式: " + srcMime + "（仅支持 TXT / Markdown / PDF / DOCX）");
        };
    }

    // ==================== uapis.cn API 调用 ====================

    public byte[] callMarkdownToPdfApi(String markdown, String theme, String paperSize) throws IOException {
        String apiKey = System.getenv("UAPI_API_KEY");
        if (apiKey == null) {
            throw new IOException("UAPI_API_KEY 未设置，请注册 https://uapis.cn 获取");
        }
        String requestBodyJson = objectMapper.writeValueAsString(
                java.util.Map.of("text", markdown, "theme", theme, "paper_size", paperSize));
        RequestBody body = RequestBody.create(
                requestBodyJson, okhttp3.MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url("https://uapis.cn/api/v1/text/markdown-to-pdf")
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();
        try (Response resp = okHttpClient.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String err = resp.body() != null ? resp.body().string() : "";
                throw new IOException("API 返回错误: " + resp.code() + " " + err);
            }
            byte[] pdf = resp.body() != null ? resp.body().bytes() : null;
            if (pdf == null || pdf.length == 0) throw new IOException("API 返回空 PDF");
            return pdf;
        }
    }

    public String callMarkdownToHtmlApi(String markdown, boolean completePage) throws IOException {
        String apiKey = System.getenv("UAPI_API_KEY");
        if (apiKey == null) {
            throw new IOException("UAPI_API_KEY 未设置，请注册 https://uapis.cn 获取");
        }
        String requestBodyJson = objectMapper.writeValueAsString(
                java.util.Map.of("text", markdown, "format", completePage ? "html" : "json"));
        RequestBody body = RequestBody.create(
                requestBodyJson, okhttp3.MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url("https://uapis.cn/api/v1/text/markdown-to-html")
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();
        try (Response resp = okHttpClient.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String err = resp.body() != null ? resp.body().string() : "";
                throw new IOException("API 返回错误: " + resp.code() + " " + err);
            }
            if (completePage) return resp.body() != null ? resp.body().string() : "";
            String json = resp.body() != null ? resp.body().string() : "";
            JsonNode root = objectMapper.readTree(json);
            return root.path("html").asText();
        }
    }

    // ==================== 文档文本提取 ====================

    private String extractDocxText(byte[] fileData) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(fileData))) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement elem : doc.getBodyElements()) {
                if (elem.getElementType() == BodyElementType.PARAGRAPH) {
                    String text = ((XWPFParagraph) elem).getText().trim();
                    if (!text.isEmpty()) sb.append(text).append("\n");
                } else if (elem.getElementType() == BodyElementType.TABLE) {
                    for (XWPFTableRow row : ((XWPFTable) elem).getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText().trim());
                        }
                        sb.append(String.join(" | ", cells)).append("\n");
                    }
                }
            }
            String text = sb.toString().trim();
            return text.isEmpty() ? "文档中未提取到文本内容" : text;
        }
    }

    private String extractPdfText(byte[] fileData) throws IOException {
        File tmp = null;
        try {
            tmp = saveTemp(fileData, ".pdf");
            try (PDDocument doc = Loader.loadPDF(tmp)) {
                if (doc.isEncrypted()) {
                    throw new IOException("PDF 已加密，无法提取文本。");
                }
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setWordSeparator(" ");
                String text = stripper.getText(doc).trim();
                if (text.isEmpty()) {
                    throw new IOException("PDF 无法提取文本（可能为纯图片、扫描件或特殊格式）");
                }
                return text;
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("PDF 解析失败", e);
        } finally {
            deleteFile(tmp);
        }
    }

    // ==================== 音频解码 ====================

    private byte[] decodeMp3ToPcm(byte[] mp3Data) throws IOException {
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

    private byte[] decodeM4aToPcm(byte[] m4aData) throws IOException {
        File tmp = null;
        try {
            tmp = saveTemp(m4aData, ".m4a");
            try (RandomAccessFile raf = new RandomAccessFile(tmp, "r");
                 FileChannel ch = raf.getChannel();
                 FileChannelWrapper fcw = new FileChannelWrapper(ch)) {
                MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(fcw);
                List<DemuxerTrack> tracks = demuxer.getAudioTracks();
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

    private void decodeFrame(AACDecoder decoder, Packet packet, ByteBuffer out,
                             ByteArrayOutputStream pcm) throws IOException {
        out.clear();
        decoder.decodeFrame(packet.getData(), out);
        out.flip();
        byte[] b = new byte[out.remaining()];
        out.get(b);
        pcm.write(b);
    }

    // ==================== WAV 封装 ====================

    private byte[] writeWav(byte[] pcm, int sampleRate) throws IOException {
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

    private void writeStr(ByteArrayOutputStream out, String s) throws IOException {
        for (byte b : s.getBytes("US-ASCII")) out.write(b);
    }

    private void writeLe32(ByteArrayOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private void writeLe16(ByteArrayOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    // ==================== TXT / MD ↔ DOCX ====================

    private byte[] pdfToDocx(byte[] srcData) throws IOException {
        File tmp = null;
        try {
            tmp = saveTemp(srcData, ".pdf");
            try (PDDocument doc = Loader.loadPDF(tmp)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setWordSeparator(" ");
                String text = stripper.getText(doc);
                org.docx4j.openpackaging.packages.WordprocessingMLPackage wordPkg =
                        org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
                for (String line : text.split("\\r?\\n")) {
                    if (line.trim().isEmpty()) continue;
                    wordPkg.getMainDocumentPart().addParagraphOfText(line.trim());
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

    private byte[] txtToDocx(String text) throws IOException {
        try {
            org.docx4j.openpackaging.packages.WordprocessingMLPackage wordPkg =
                    org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
            for (String line : text.split("\\r?\\n")) {
                if (line.trim().isEmpty()) continue;
                wordPkg.getMainDocumentPart().addParagraphOfText(line.trim());
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wordPkg.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IOException("TXT转Word失败", e);
        }
    }

    private byte[] mdToDocx(String markdown) throws IOException {
        try {
            org.docx4j.openpackaging.packages.WordprocessingMLPackage wordPkg =
                    org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
            String[] lines = markdown.split("\\r?\\n");
            boolean inCodeBlock = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("```")) { inCodeBlock = !inCodeBlock; continue; }
                if (inCodeBlock || trimmed.isEmpty()) continue;
                java.util.regex.Matcher headingMatcher =
                        java.util.regex.Pattern.compile("^(#{1,6})\\s+(.*)").matcher(trimmed);
                if (headingMatcher.matches()) {
                    wordPkg.getMainDocumentPart().addParagraphOfText(headingMatcher.group(2));
                    continue;
                }
                if (trimmed.matches("^[-*]\\s+.*")) {
                    wordPkg.getMainDocumentPart().addParagraphOfText(trimmed.replaceAll("^[-*]\\s+", ""));
                    continue;
                }
                wordPkg.getMainDocumentPart().addParagraphOfText(trimmed);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wordPkg.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IOException("Markdown转Word失败", e);
        }
    }

    public String decodeText(byte[] data) {
        String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        if (text.contains("\uFFFD")) {
            text = new String(data, Charset.forName("GBK"));
        }
        return text;
    }

    private String mdToPlainText(String markdown) {
        return markdown.lines()
                .filter(l -> !l.trim().startsWith("```"))
                .map(l -> l.replaceAll("^#{1,6}\\s+", "")
                        .replaceAll("^[-*]\\s+", "")
                        .replaceAll("\\*\\*(.*?)\\*\\*", "$1")
                        .replaceAll("\\*(.*?)\\*", "$1")
                        .replaceAll("`(.*?)`", "$1")
                        .replaceAll("!\\[.*?\\]\\(.*?\\)", "")
                        .replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1")
                        .strip())
                .filter(l -> !l.isEmpty())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private String docxToMarkdown(FileInputStream docxStream) throws IOException {
        XWPFDocument doc = new XWPFDocument(docxStream);
        StringBuilder md = new StringBuilder();
        for (IBodyElement elem : doc.getBodyElements()) {
            if (elem.getElementType() == BodyElementType.PARAGRAPH) {
                XWPFParagraph p = (XWPFParagraph) elem;
                String text = p.getText().trim();
                if (text.isEmpty()) { md.append("\n"); continue; }
                if (p.getStyleID() != null && p.getStyleID().startsWith("Heading")) {
                    int level = 1;
                    try { level = Integer.parseInt(p.getStyleID().replace("Heading", "")); } catch (Exception ignored) {}
                    if (level < 1) level = 1;
                    if (level > 6) level = 6;
                    md.append("#".repeat(level)).append(" ").append(text).append("\n\n");
                } else {
                    md.append(text).append("\n\n");
                }
            } else if (elem.getElementType() == BodyElementType.TABLE) {
                XWPFTable t = (XWPFTable) elem;
                List<XWPFTableRow> rows = t.getRows();
                if (rows.isEmpty()) continue;
                boolean headerWritten = false;
                for (XWPFTableRow row : rows) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText().trim().replace("\n", " "));
                    }
                    md.append("| ").append(String.join(" | ", cells)).append(" |\n");
                    if (!headerWritten) {
                        List<String> sep = new ArrayList<>();
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

    // ==================== 临时文件管理 ====================

    private File saveTemp(byte[] data, String suffix) throws IOException {
        File f = new File(TEMP_DIR, UUID.randomUUID().toString() + suffix);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return f;
    }

    private void deleteFile(File f) {
        if (f != null && f.exists()) {
            try { Files.delete(f.toPath()); } catch (IOException ignored) {}
        }
    }
}
