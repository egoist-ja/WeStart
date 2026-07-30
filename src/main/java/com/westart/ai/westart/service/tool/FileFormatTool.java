package com.westart.ai.westart.service.tool;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.westart.ai.westart.service.impl.ILinkClientSessionRegistry;
import com.westart.ai.westart.util.UserFileCache;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.message.TextContent;
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
import org.springframework.stereotype.Service;
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service("fileFormatTool")
@RequiredArgsConstructor
@Slf4j
public class FileFormatTool {

    private static final File TEMP_DIR = new File(
            System.getProperty("java.io.tmpdir"), "westart_convert");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final ILinkClientSessionRegistry sessionRegistry;

    static {
        TEMP_DIR.mkdirs();
    }

    /**
     * 文件消息处理
     *
     * @param client
     * @param userId
     * @param item
     * @return
     */
    public TextContent processIncomingFile(ILinkClient client, String userId, MessageItem item) {
        if (item.getFile_item() == null) return null;
        String fileName = item.getFile_item().getFile_name();
        if (fileName == null) return null;
        try {
            byte[] fileData = client.downloadFileFromMessageItem(item);
            if (fileData == null || fileData.length == 0) return null;
            String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            String mime = switch (ext) {
                case "pdf" -> "application/pdf";
                case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "txt" -> "text/plain";
                case "md" -> "text/markdown";
                case "mp3" -> "audio/mpeg";
                case "m4a" -> "audio/mp4";
                case "wav" -> "audio/wav";
                default -> null;
            };
            if (mime == null) return null;
            String fileKey = UserFileCache.store(userId, fileName, fileData, mime);
            return TextContent.from("用户发送了文件: " + fileName + " [文件ID: " + fileKey + "]");
        } catch (IOException | ILinkException e) {
            log.warn("文件下载失败，userId={}，fileName={}", userId, fileName);
            return null;
        }
    }

    /**
     * 客户端连接
     *
     * @param userId
     * @return
     */
    private ILinkClient getClient(String userId) {
        Optional<ILinkClient> client = sessionRegistry.findClientByUserId(userId);
        if (client.isEmpty()) {
            log.warn("未找到 userId={} 的客户端连接", userId);
        }
        return client.orElse(null);
    }


    /**
     * 音频转换
     *
     * @param srcData
     * @param srcMime
     * @return
     * @throws IOException
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

    /**
     * 文档转换
     *
     * @param srcData
     * @param srcMime
     * @return
     * @throws IOException
     */

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
            case "text/plain", "text/markdown" -> {
                String text = new String(srcData, java.nio.charset.StandardCharsets.UTF_8);
                yield callMarkdownToPdfApi(text, "github", "A4");
            }
            default -> throw new IOException("不支持的格式: " + srcMime + "（仅支持 DOCX / TXT / Markdown）");
        };
    }

    public byte[] toDocx(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        return switch (srcMime) {
            case "application/pdf" -> pdfToDocx(srcData);
            case "text/markdown" -> mdToDocx(new String(srcData, java.nio.charset.StandardCharsets.UTF_8));
            case "text/plain" -> txtToDocx(new String(srcData, java.nio.charset.StandardCharsets.UTF_8));
            default -> throw new IOException("不支持的格式: " + srcMime + "（仅支持 PDF / Markdown / TXT）");
        };
    }

    public String toTxt(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        return switch (srcMime) {
            case "text/plain" -> new String(srcData, java.nio.charset.StandardCharsets.UTF_8);
            case "text/markdown" -> mdToPlainText(new String(srcData, java.nio.charset.StandardCharsets.UTF_8));
            case "application/pdf" -> extractPdfText(srcData);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    extractDocxText(srcData);
            default -> throw new IOException("不支持的格式: " + srcMime + "（仅支持 TXT / Markdown / PDF / DOCX）");
        };
    }

    public byte[] markdownToPdf(String markdownText, String theme, String paperSize) throws IOException {
        if (markdownText == null || markdownText.isBlank()) {
            throw new IOException("Markdown文本不能为空");
        }
        String resolvedTheme = (theme == null || theme.isBlank()) ? "github" : theme;
        String resolvedPaperSize = (paperSize == null || paperSize.isBlank()) ? "A4" : paperSize;
        return callMarkdownToPdfApi(markdownText, resolvedTheme, resolvedPaperSize);
    }

    public String markdownToHtml(String markdownText, boolean completePage) throws IOException {
        if (markdownText == null || markdownText.isBlank()) {
            throw new IOException("Markdown文本不能为空");
        }
        return callMarkdownToHtmlApi(markdownText, completePage);
    }

    /**
     * uapi.cn API调用
     *
     * @param markdown
     * @param theme
     * @param paperSize
     * @return
     * @throws IOException
     */
    private byte[] callMarkdownToPdfApi(String markdown, String theme, String paperSize) throws IOException {
        String apiKey = getUapiKey();
        if (apiKey == null) {
            throw new IOException("UAPI_KEY 未设置，请注册 https://uapis.cn 获取");
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

    private String callMarkdownToHtmlApi(String markdown, boolean completePage) throws IOException {
        String apiKey = getUapiKey();
        if (apiKey == null) {
            throw new IOException("UAPI_KEY 未设置，请注册 https://uapis.cn 获取");
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

    private String getUapiKey() {
        return System.getenv("UAPI_KEY");
    }

    /**
     *  工具方法：文件转换
     */

    @Tool(value = "将用户之前发送的文件转换为Word文档(.docx)。" +
            "fileKey的值来自之前用户消息中的[文件ID: xxx]，直接提取xxx传入即可。")
    public String convertToDocx(@ToolMemoryId String userId,
                                @P("[文件ID]中冒号后面的值，例如用户消息是[文件ID: abc]，则传abc") String fileKey) {
        UserFileCache.StoredFile file = UserFileCache.get(fileKey);
        if (file == null) return "错误：文件已过期或不存在，请重新发送文件。";
        ILinkClient client = getClient(userId);
        if (client == null) return "错误：无法获取客户端连接。";
        try {
            byte[] result = toDocx(file.data(), file.mime());
            String newName = file.fileName().replaceAll("\\.[^.]+$", "") + ".docx";
            client.sendFile(userId, result, newName, null);
            UserFileCache.remove(fileKey);
            return "已为您将 " + file.fileName() + " 转换为Word文档。";
        } catch (Exception e) {
            log.error("文件转Word失败，fileKey={}", fileKey, e);
            return "文件转换失败：" + e.getMessage();
        }
    }

    @Tool(value = "将用户之前发送的文件转换为PDF。" +
            "fileKey的值来自之前用户消息中的[文件ID: xxx]，直接提取xxx传入即可。")
    public String convertToPdf(@ToolMemoryId String userId,
                               @P("[文件ID]中冒号后面的值，例如用户消息是[文件ID: abc]，则传abc") String fileKey) {
        UserFileCache.StoredFile file = UserFileCache.get(fileKey);
        if (file == null) return "错误：文件已过期或不存在，请重新发送文件。";
        ILinkClient client = getClient(userId);
        if (client == null) return "错误：无法获取客户端连接。";
        try {
            byte[] result;
            String mime = file.mime();
            if ("text/plain".equals(mime) || "text/markdown".equals(mime)) {
                String text = new String(file.data(), java.nio.charset.StandardCharsets.UTF_8);
                result = markdownToPdf(text, "github", "A4");
            } else if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)) {
                result = toPdf(file.data(), mime);
            } else {
                return "错误：不支持将 " + file.fileName() + " 转换为PDF。";
            }
            String newName = file.fileName().replaceAll("\\.[^.]+$", "") + ".pdf";
            client.sendFile(userId, result, newName, null);
            UserFileCache.remove(fileKey);
            return "已为您将 " + file.fileName() + " 转换为PDF。";
        } catch (Exception e) {
            log.error("文件转PDF失败，fileKey={}", fileKey, e);
            return "文件转换失败：" + e.getMessage();
        }
    }

    @Tool(value = "提取用户之前发送文件的纯文本内容。" +
            "fileKey的值来自之前用户消息中的[文件ID: xxx]，直接提取xxx传入即可。")
    public String extractText(@ToolMemoryId String userId,
                              @P("[文件ID]中冒号后面的值，例如用户消息是[文件ID: abc]，则传abc") String fileKey) {
        UserFileCache.StoredFile file = UserFileCache.get(fileKey);
        if (file == null) return "错误：文件已过期或不存在，请重新发送文件。";
        ILinkClient client = getClient(userId);
        if (client == null) return "错误：无法获取客户端连接。";
        try {
            String text = toTxt(file.data(), file.mime());
            UserFileCache.remove(fileKey);
            String baseName = file.fileName().replaceAll("\\.[^.]+$", "");
            client.sendFile(userId, text.getBytes(java.nio.charset.StandardCharsets.UTF_8), baseName + ".txt", null);
            return "已提取 " + file.fileName() + " 的文本内容并发送。";
        } catch (Exception e) {
            log.error("文件文本提取失败，fileKey={}", fileKey, e);
            return "文件文本提取失败：" + e.getMessage();
        }
    }

    @Tool(value = "当用户需要从Word文档(.docx)、PDF、Markdown或TXT文件中提取纯文本内容时，调用此工具。" +
            "参数mimeType为文件类型，base64Data为文件的Base64编码数据。返回提取的纯文本内容。")
    public String extractDocumentText(String base64Data, String mimeType) {
        if (base64Data == null || base64Data.isBlank()) return "错误：文件内容为空";
        byte[] fileData;
        try {
            fileData = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            return "错误：无效的Base64编码数据";
        }
        try {
            return toTxt(fileData, mimeType);
        } catch (IOException e) {
            return "不支持的文件格式: " + mimeType + "，仅支持 .docx / .pdf / .md / .txt";
        }
    }

    @Tool(value = "当用户需要将Markdown文本内容直接转换为PDF文件时，调用此工具。" +
            "markdownText为Markdown格式的文本，theme为可选主题(github/minimal/light/dark，默认github)，" +
            "paperSize为可选纸张大小(A4/Letter，默认A4)。返回Base64编码的PDF文件数据。")
    public String convertMarkdownToPdf(String markdownText, String theme, String paperSize) {
        try {
            byte[] pdfData = markdownToPdf(markdownText, theme, paperSize);
            return Base64.getEncoder().encodeToString(pdfData);
        } catch (IOException e) {
            return "错误：Markdown转PDF失败 - " + e.getMessage();
        }
    }

    @Tool(value = "当用户需要将Markdown文本内容直接转换为Word文档(.docx)时，调用此工具。" +
            "markdownText为Markdown格式的文本。返回Base64编码的docx文件数据。")
    public String convertMarkdownToDocx(String markdownText) {
        try {
            byte[] docxData = toDocx(
                    markdownText.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/markdown");
            return Base64.getEncoder().encodeToString(docxData);
        } catch (IOException e) {
            return "错误：Markdown转Word失败 - " + e.getMessage();
        }
    }

    @Tool(value = "当用户需要将Markdown文本内容转换为HTML时，调用此工具。" +
            "markdownText为Markdown格式的文本，completePage表示是否生成完整HTML页面(含DOCTYPE和样式)，" +
            "false则只返回HTML片段。返回HTML内容。")
    public String convertMarkdownToHtml(String markdownText, boolean completePage) {
        try {
            return markdownToHtml(markdownText, completePage);
        } catch (IOException e) {
            return "错误：Markdown转HTML失败 - " + e.getMessage();
        }
    }

    @Tool(value = "当用户需要查询系统支持的文件格式转换类型时，调用此工具。返回所有支持的格式转换说明。")
    public String getSupportedConversions() {
        return """
                支持的文件格式转换：
                1. TXT ↔ DOCX / Markdown / PDF
                2. Markdown ↔ DOCX / TXT / PDF / HTML
                3. DOCX ↔ TXT / Markdown / PDF
                4. PDF ↔ TXT / Markdown / DOCX
                5. 音频文件 → WAV (.wav)
                6. 支持的音频输入格式：MP3、M4A
                7. 文档文本提取：Word (.docx) 和 PDF 文件可提取纯文本内容
                """;
    }

    /**
     * 文档文本提取
     */


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
    /**
     *  音频解码
     *
     */

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

    /**
     * WAV 封装
     *
     */

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

    /**
     *  TXT / MD ↔ DOCX 辅助方法
     *
     */

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

    /**
     *   临时文件管理
     *
     */

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
