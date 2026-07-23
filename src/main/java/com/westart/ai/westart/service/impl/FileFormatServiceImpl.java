package com.westart.ai.westart.service.impl;

import com.westart.ai.westart.service.FileFormatService;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
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
import org.springframework.stereotype.Service;
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
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class FileFormatServiceImpl implements FileFormatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileFormatServiceImpl.class);
    private static final File TEMP_DIR = new File(
            System.getProperty("java.io.tmpdir"), "westart_convert");
    private static final int[] WAV_HEADER = {0x52, 0x49, 0x46, 0x46};
    private static final int[] WAVE_ID = {0x57, 0x41, 0x56, 0x45};

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    static {
        TEMP_DIR.mkdirs();
    }

    @Override
    public byte[] toWav(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;

        return switch (srcMime) {
            case "audio/wav" -> srcData;
            case "audio/mpeg" -> decodeMp3ToPcm(srcData);
            case "audio/mp4" -> decodeM4aToPcm(srcData);
            default -> throw new IOException("不支持的音频格式: " + srcMime);
        };
    }

    @Override
    public byte[] toPdf(byte[] srcData, String srcMime) throws IOException {
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
            try (FileInputStream fis = new FileInputStream(tmp)) {
                markdown = docxToMarkdown(fis);
            }

            String requestBodyJson = objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "text", markdown,
                            "theme", "github",
                            "paper_size", "A4"));
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

    @Override
    public byte[] toDocx(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        if (!"application/pdf".equals(srcMime)) {
            throw new IOException("不支持的格式: " + srcMime + "（仅支持 PDF）");
        }

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

    @Tool(value = "当用户需要从Word文档(.docx)或PDF文件中提取文本内容时，调用此工具。参数mimeType为文件类型，base64Data为文件的Base64编码数据。返回提取的纯文本内容。")
    public String extractDocumentText(String base64Data, String mimeType) throws IOException {
        if (base64Data == null || base64Data.isBlank()) {
            return "错误：文件内容为空";
        }
        byte[] fileData;
        try {
            fileData = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            return "错误：无效的Base64编码数据";
        }

        return switch (mimeType) {
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    extractDocxText(fileData);
            case "application/pdf" ->
                    extractPdfText(fileData);
            default ->
                    "不支持的文件格式: " + mimeType + "，仅支持 .docx 和 .pdf";
        };
    }

    @Tool(value = "当用户需要查询系统支持的文件格式转换类型时，调用此工具。返回所有支持的格式转换说明。")
    public String getSupportedConversions() {
        return """
                支持的文件格式转换：
                1. Word (.docx) ↔ PDF
                2. 音频文件 → WAV (.wav)
                3. 支持的音频输入格式：MP3、M4A
                4. 文档文本提取：Word (.docx) 和 PDF 文件可提取纯文本内容
                """;
    }

    private String extractDocxText(byte[] fileData) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(fileData))) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement elem : doc.getBodyElements()) {
                if (elem.getElementType() == BodyElementType.PARAGRAPH) {
                    String text = ((XWPFParagraph) elem).getText().trim();
                    if (!text.isEmpty()) {
                        sb.append(text).append("\n");
                    }
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
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setWordSeparator(" ");
                String text = stripper.getText(doc).trim();
                return text.isEmpty() ? "PDF中未提取到文本内容" : text;
            }
        } finally {
            deleteFile(tmp);
        }
    }

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

    private String docxToMarkdown(FileInputStream docxStream) throws IOException {
        XWPFDocument doc = new XWPFDocument(docxStream);
        StringBuilder md = new StringBuilder();

        for (IBodyElement elem : doc.getBodyElements()) {
            if (elem.getElementType() == BodyElementType.PARAGRAPH) {
                XWPFParagraph p = (XWPFParagraph) elem;
                String text = p.getText().trim();
                if (text.isEmpty()) {
                    md.append("\n");
                    continue;
                }
                if (p.getStyleID() != null && p.getStyleID().startsWith("Heading")) {
                    int level = 1;
                    try {
                        level = Integer.parseInt(p.getStyleID().replace("Heading", ""));
                    } catch (Exception ignored) {}
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
                for (int ri = 0; ri < rows.size(); ri++) {
                    XWPFTableRow row = rows.get(ri);
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

    private File saveTemp(byte[] data, String suffix) throws IOException {
        File f = new File(TEMP_DIR, UUID.randomUUID().toString() + suffix);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return f;
    }

    private void deleteFile(File f) {
        if (f != null && f.exists()) {
            try {
                Files.delete(f.toPath());
            } catch (IOException ignored) {}
        }
    }
}
