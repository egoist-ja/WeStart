package com.westart.ai.westart.service.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.jcodec.codecs.aac.AACDecoder;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
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

/**
 * 文件格式转换引擎，提供音频解码、文档格式互转和 Markdown 渲染能力。
 *
 * <p>转换流水线分两条独立通道：</p>
 * <ul>
 *   <li><b>文档通道</b>：任意格式文件 → toMarkdown（CLI 提取） → Markdown → 目标格式</li>
 *   <li><b>音频通道</b>：MP3/M4A → PCM → WAV，不经过 MarkItDown</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FileFormatConverter {

    private static final File TEMP_DIR = new File(
            System.getProperty("java.io.tmpdir"), "westart_convert");

    /** HTTP 客户端，用于调用 UAPI 的 Markdown→PDF/HTML 接口 */
    private final OkHttpClient okHttpClient;

    /** JSON 序列化/反序列化 */
    private final ObjectMapper objectMapper;

    static {
        TEMP_DIR.mkdirs();
    }

    // ====================  音频通道：MP3/M4A 解码为 WAV  ====================

    /**
     * 将音频数据解码为 WAV 格式。
     *
     * <p>支持的输入格式：WAV（直通）、MP3、M4A。</p>
     *
     * @param srcData 音频文件二进制数据
     * @param srcMime 源 MIME 类型（audio/wav、audio/mpeg、audio/mp4）
     * @return WAV 格式的 PCM 数据
     */
    public byte[] toWav(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        return switch (srcMime) {
            case "audio/wav" -> srcData;
            case "audio/mpeg" -> decodeMp3ToPcm(srcData);
            case "audio/mp4" -> decodeM4aToPcm(srcData);
            default -> throw new IOException("不支持的音频格式: " + srcMime);
        };
    }

    // ====================  文档通道：任意文件 → Markdown → 目标格式  ====================

    /**
     * 将任意支持格式的文件转为 PDF。
     *
     * <p>{@code text/plain} 和 {@code text/markdown} 直接调用 UAPI 渲染；
     * 其他格式先通过 {@code toMarkdown} 提取 Markdown 内容。</p>
     *
     * @param srcData 文件二进制数据
     * @param srcMime 源 MIME 类型
     * @return PDF 字节数组
     */
    public byte[] toPdf(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        return switch (srcMime) {
            case "text/plain", "text/markdown" ->
                    callMarkdownToPdfApi(decodeText(srcData), "github", "A4");
            default -> {
                String markdown = toMarkdown(srcData, mimeToExtension(srcMime));
                if (markdown == null) markdown = extractTextFallback(srcData, srcMime);
                if (markdown == null) {
                    throw new IOException("无法转换 " + srcMime + " 为 PDF（文件提取失败）");
                }
                yield callMarkdownToPdfApi(markdown, "github", "A4");
            }
        };
    }

    /**
     * 将任意支持格式的文件转为 DOCX。
     *
     * <p>Markdown 输入保留标题样式；纯文本按行构建段落；
     * 其他格式先通过 {@code toMarkdown} 提取 Markdown。</p>
     *
     * @param srcData 文件二进制数据
     * @param srcMime 源 MIME 类型
     * @return DOCX 字节数组
     */
    public byte[] toDocx(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        return switch (srcMime) {
            case "text/plain" -> txtToDocx(decodeText(srcData));
            case "text/markdown" -> mdToDocx(decodeText(srcData));
            default -> {
                String markdown = toMarkdown(srcData, mimeToExtension(srcMime));
                if (markdown == null) markdown = extractTextFallback(srcData, srcMime);
                if (markdown == null) {
                    throw new IOException("无法转换 " + srcMime + " 为 DOCX（文件提取失败）");
                }
                yield txtToDocx(markdown);
            }
        };
    }

    /**
     * 从任意支持格式的文件中提取纯文本。
     *
     * <p>非文本格式先通过 {@code toMarkdown} 提取 Markdown，再清洗标签语法。
     * 文本格式直接解码返回。</p>
     *
     * @param srcData 文件二进制数据
     * @param srcMime 源 MIME 类型
     * @return 纯文本内容
     */
    public String toTxt(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        return switch (srcMime) {
            case "text/plain" -> decodeText(srcData);
            case "text/markdown" -> mdToPlainText(decodeText(srcData));
            default -> {
                String markdown = toMarkdown(srcData, mimeToExtension(srcMime));
                if (markdown == null) markdown = extractTextFallback(srcData, srcMime);
                if (markdown == null) {
                    throw new IOException("无法提取 " + srcMime + " 的文本");
                }
                yield mdToPlainText(markdown);
            }
        };
    }

    // ====================  Markdown 渲染：调用 UAPI 外部服务  ====================

    /**
     * 调用 UAPI 将 Markdown 文本渲染为 PDF 文件。
     *
     * @param markdown  Markdown 文本
     * @param theme     渲染主题（github / minimal / light / dark）
     * @param paperSize 纸张大小（A4 / Letter）
     * @return PDF 字节数组
     */
    public byte[] callMarkdownToPdfApi(String markdown, String theme, String paperSize) throws IOException {
        String apiKey = System.getenv("UAPI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("UAPI_KEY 未设置，请注册 https://uapis.cn 获取");
        }
        String requestBodyJson = objectMapper.writeValueAsString(
                java.util.Map.of("text", markdown, "theme", theme, "paper_size", paperSize));
        RequestBody body = RequestBody.create(
                requestBodyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                MediaType.get("application/json; charset=utf-8"));
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

    /**
     * 调用 UAPI 将 Markdown 文本渲染为 HTML。
     *
     * @param markdown     Markdown 文本
     * @param completePage {@code true} 返回完整 HTML 页面，{@code false} 仅返回片段
     * @return HTML 内容
     */
    public String callMarkdownToHtmlApi(String markdown, boolean completePage) throws IOException {
        String apiKey = System.getenv("UAPI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("UAPI_KEY 未设置，请注册 https://uapis.cn 获取");
        }
        String requestBodyJson = objectMapper.writeValueAsString(
                java.util.Map.of("text", markdown, "format", completePage ? "html" : "json"));
        RequestBody body = RequestBody.create(
                requestBodyJson, MediaType.get("application/json; charset=utf-8"));
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

    // ====================  Markdown 纯文本清洗  ====================

    /**
     * 去除 Markdown 语法标签，提取纯文本。
     * 移除代码块、标题标记、加粗/斜体、行内代码、链接和图片语法。
     */
    private String mdToPlainText(String markdown) {
        return markdown.lines()
                .filter(l -> !l.trim().startsWith("```"))                  // 跳过代码块界定符
                .map(l -> l.replaceAll("^#{1,6}\\s+", "")                   // 移除标题 #
                        .replaceAll("^[-*]\\s+", "")                        // 移除列表标记
                        .replaceAll("\\*\\*(.*?)\\*\\*", "$1")              // 粗体
                        .replaceAll("\\*(.*?)\\*", "$1")                     // 斜体
                        .replaceAll("`(.*?)`", "$1")                         // 行内代码
                        .replaceAll("!\\[.*?\\]\\(.*?\\)", "")               // 图片
                        .replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1")            // 链接
                        .strip())
                .filter(l -> !l.isEmpty())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    // ====================  文本编码检测  ====================

    /**
     * 解码文本文件字节，自动检测 UTF-8 / GBK 编码。
     * UTF-8 解码出现替换字符（U+FFFD）时自动回退到 GBK。
     */
    public String decodeText(byte[] data) {
        String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        if (text.contains("\uFFFD")) {
            text = new String(data, Charset.forName("GBK"));
        }
        return text;
    }

    // ====================  文本/Markdown → DOCX  ====================

    /**
     * 纯文本转 DOCX：每行一个段落。
     */
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

    /**
     * Markdown 转 DOCX：识别标题和列表语法，跳过代码块。
     */
    private byte[] mdToDocx(String markdown) throws IOException {
        try {
            org.docx4j.openpackaging.packages.WordprocessingMLPackage wordPkg =
                    org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
            String[] lines = markdown.split("\\r?\\n");
            boolean inCodeBlock = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("```")) { inCodeBlock = !inCodeBlock; continue; }      // 代码块开关
                if (inCodeBlock || trimmed.isEmpty()) continue;                               // 跳过代码块内容和空行
                java.util.regex.Matcher headingMatcher =
                        java.util.regex.Pattern.compile("^(#{1,6})\\s+(.*)").matcher(trimmed);
                if (headingMatcher.matches()) {
                    wordPkg.getMainDocumentPart().addParagraphOfText(headingMatcher.group(2)); // 标题
                    continue;
                }
                if (trimmed.matches("^[-*]\\s+.*")) {
                    wordPkg.getMainDocumentPart().addParagraphOfText(
                            trimmed.replaceAll("^[-*]\\s+", ""));                              // 列表项
                    continue;
                }
                wordPkg.getMainDocumentPart().addParagraphOfText(trimmed);                     // 普通段落
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wordPkg.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IOException("Markdown转Word失败", e);
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

    // ==================== MIME → 文件扩展名映射 ====================

    private static String mimeToExtension(String mime) {
        if (mime == null) return ".bin";
        return switch (mime) {
            case "application/pdf" -> ".pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.ms-powerpoint" -> ".ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "text/html" -> ".html";
            case "text/csv" -> ".csv";
            case "application/json" -> ".json";
            case "text/xml", "application/xml" -> ".xml";
            case "application/epub+zip" -> ".epub";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> ".bin";
        };
    }

    // ==================== MarkItDown 文件→Markdown（Docker封装） ====================

    private String toMarkdown(byte[] fileData, String suffix) {
        if (fileData == null || fileData.length == 0) return null;
        if (!markitdownReady) {
            synchronized (FileFormatConverter.class) {
                if (!markitdownReady) {
                    markitdownReady = ensureMarkitdownImage();
                }
            }
        }
        if (!markitdownReady) {
            log.debug("markitdown Docker 镜像不可用，跳过");
            return null;
        }
        File tmp = null;
        try {
            tmp = saveTemp(fileData, suffix);
            String inContainer = "/tmp/input" + suffix;
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "run", "--rm", "-v",
                    tmp.getAbsolutePath() + ":" + inContainer,
                    "markitdown:latest",
                    "markitdown", inContainer);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("markitdown Docker 执行失败，exitCode={}，输出={}", exitCode,
                        output.length() > 500 ? output.substring(0, 500) : output);
                return null;
            }
            return output.strip();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("markitdown Docker 异常: {}", e.getMessage());
            return null;
        } finally {
            deleteFile(tmp);
        }
    }

    private static volatile boolean markitdownReady;

    private static boolean ensureMarkitdownImage() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "image", "inspect", "markitdown:latest");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor() == 0) {
                log.info("markitdown Docker 镜像已存在");
                return true;
            }
        } catch (Exception ignored) {}

        log.info("markitdown Docker 镜像不存在，开始自动构建...");
        try {
            String dockerfile = FileFormatConverter.class.getClassLoader()
                    .getResource("markitdown.Dockerfile").getPath();
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "build", "-t", "markitdown:latest", "-f", dockerfile, ".");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (p.waitFor() == 0) {
                log.info("markitdown Docker 镜像构建成功");
                return true;
            }
            log.error("markitdown Docker 镜像构建失败: {}", output);
        } catch (Exception e) {
            log.error("markitdown Docker 镜像构建异常", e);
        }
        return false;
    }

    // ====================  Java 兜底：DOCX/PDF 文本提取  ====================

    private String extractTextFallback(byte[] data, String mime) {
        try {
            return switch (mime) {
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                        extractDocxText(data);
                case "application/pdf" -> extractPdfText(data);
                default -> null;
            };
        } catch (Exception e) {
            log.warn("Java 兜底提取失败，mime={}", mime, e);
            return null;
        }
    }

    private String extractDocxText(byte[] fileData) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(fileData))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText().trim();
                if (!text.isEmpty()) sb.append(text).append("\n");
            }
            return sb.toString().trim();
        }
    }

    private String extractPdfText(byte[] fileData) throws IOException {
        File tmp = null;
        try {
            tmp = saveTemp(fileData, ".pdf");
            try (PDDocument doc = Loader.loadPDF(tmp)) {
                if (doc.isEncrypted()) throw new IOException("PDF 已加密");
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                return stripper.getText(doc).trim();
            }
        } finally {
            deleteFile(tmp);
        }
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
