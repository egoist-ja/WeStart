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
import java.util.UUID;

/**
 * 文件格式转换服务实现。
 * <p>
 * 支持的能力分为两类：
 * <ol>
 *   <li><b>本地处理</b> — 在 JVM 内用 Java 库完成，不依赖外部服务：
 *     <ul>
 *       <li>MP3 → PCM → WAV（javax.sound）</li>
 *       <li>M4A/AAC → PCM → WAV（jcodec）</li>
 *       <li>PDF → DOCX（PDFBox + docx4j）</li>
 *     </ul>
 *   </li>
 *   <li><b>API 调用</b> — 通过 uapis.cn 云服务完成：
 *     <ul>
 *       <li>DOCX → Markdown → PDF（POI + uapis API）</li>
 *       <li>Markdown → PDF（uapis API）</li>
 *       <li>Markdown → HTML（uapis API）</li>
 *     </ul>
 *   </li>
 * </ol>
 * 标注了 {@code @Tool} 的方法可被 AI 模型直接调用。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class FileFormatServiceImpl implements FileFormatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileFormatServiceImpl.class);

    /** 临时文件存放目录 */
    private static final File TEMP_DIR = new File(
            System.getProperty("java.io.tmpdir"), "westart_convert");

    private static final int[] WAV_HEADER = {0x52, 0x49, 0x46, 0x46};
    private static final int[] WAVE_ID = {0x57, 0x41, 0x56, 0x45};

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    static {
        TEMP_DIR.mkdirs();
    }

    // ==================== 接口实现：本地音频转换 ====================

    /**
     * 音频转 WAV。
     * WAV 格式直接透传；MP3 和 M4A 先解码为 PCM 再封装 WAV 头。
     *
     * 流程：
     *   1. 判断输入格式
     *   2. WAV → 直接返回原数据
     *   3. MP3 → decodeMp3ToPcm：javax.sound 解码 MP3 → PCM → 封装 WAV
     *   4. M4A → decodeM4aToPcm：jcodec 解复用 AAC 轨道 → 逐帧解码 → 封装 WAV
     */
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

    // ==================== 接口实现：文档互转（本地 + API） ====================

    /**
     * DOCX → PDF。
     * 先通过 POI 将 docx 解析为 Markdown 文本，再调用 uapis API 渲染 PDF。
     *
     * 流程：
     *   1. 校验文件 MIME 类型是否为 docx
     *   2. 将字节数组写入临时 .docx 文件（PDFBox/POI 需要 File 接口）
     *   3. 用 POI（XWPFDocument）读取临时文件，逐元素解析为 Markdown 文本
     *      - 段落 PARAGRAPH → Heading 样式转 # 标题，普通段落直接输出
     *      - 表格 TABLE → 转为 Markdown 表格语法（| --- | 分隔行）
     *   4. 将 Markdown POST 到 uapis.cn 的 markdown-to-pdf API
     *   5. 返回 API 响应的 PDF 二进制流
     *   6. 清理临时文件
     */
    @Override
    public byte[] toPdf(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;
        if (!"application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(srcMime)) {
            throw new IOException("不支持的格式: " + srcMime + "（仅支持 .docx）");
        }

        File tmp = null;
        try {
            // 步骤2：写入临时文件
            tmp = saveTemp(srcData, ".docx");
            // 步骤3：docx → Markdown
            String markdown;
            try (FileInputStream fis = new FileInputStream(tmp)) {
                markdown = docxToMarkdown(fis);
            }
            // 步骤4：调用 API 渲染 PDF
            return callMarkdownToPdfApi(markdown, "github", "A4");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Word转PDF失败", e);
        } finally {
            // 步骤6：清理临时文件
            deleteFile(tmp);
        }
    }

    /**
     * TXT / Markdown / PDF → DOCX。
     *
     * 流程（按 MIME 类型分支）：
     *   application/pdf       → PDFBox 提取文本 → docx4j 逐行写入
     *   text/markdown         → 解析 Markdown 语法转为带样式 docx
     *   text/plain            → 直接逐行写入 docx4j 段落
     */
    @Override
    public byte[] toDocx(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;

        return switch (srcMime) {
            case "application/pdf" -> pdfToDocx(srcData);
            case "text/markdown" -> mdToDocx(new String(srcData, java.nio.charset.StandardCharsets.UTF_8));
            case "text/plain" -> txtToDocx(new String(srcData, java.nio.charset.StandardCharsets.UTF_8));
            default -> throw new IOException("不支持的格式: " + srcMime + "（仅支持 PDF / Markdown / TXT）");
        };
    }

    /**
     * TXT / DOCX / PDF → 纯文本。
     *
     * 流程（按 MIME 类型分支）：
     *   text/plain            → 直接透传
     *   text/markdown         → 剥离 Markdown 语法标记
     *   application/pdf       → PDFBox 提取文本
     *   application/vnd...doc → POI 提取段落和表格文本
     */
    @Override
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

    /**
     * TXT / PDF → Markdown。
     *
     * 流程（按 MIME 类型分支）：
     *   text/plain            → 直接透传
     *   application/pdf       → PDFBox 提取文本 → 转为 Markdown
     *   application/vnd...doc → POI 解析样式转为 Markdown（含标题、表格）
     */
    @Override
    public String toMarkdown(byte[] srcData, String srcMime) throws IOException {
        if (srcData == null || srcData.length == 0 || srcMime == null) return null;

        return switch (srcMime) {
            case "text/plain" -> new String(srcData, java.nio.charset.StandardCharsets.UTF_8);
            case "text/markdown" -> new String(srcData, java.nio.charset.StandardCharsets.UTF_8);
            case "application/pdf" -> {
                String text = extractPdfText(srcData);
                yield "# 转换文档\n\n" + text;
            }
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                File tmp = saveTemp(srcData, ".docx");
                try (FileInputStream fis = new FileInputStream(tmp)) {
                    yield docxToMarkdown(fis);
                } finally {
                    deleteFile(tmp);
                }
            }
            default -> throw new IOException("不支持的格式: " + srcMime + "（仅支持 TXT / Markdown / PDF / DOCX）");
        };
    }

    // ==================== 接口实现：Markdown → PDF（仅 API） ====================

    /**
     * Markdown → PDF。
     * 直接调用 uapis API，支持自定义主题和纸张大小。
     */
    @Override
    public byte[] markdownToPdf(String markdownText, String theme, String paperSize) throws IOException {
        if (markdownText == null || markdownText.isBlank()) {
            throw new IOException("Markdown文本不能为空");
        }
        String resolvedTheme = (theme == null || theme.isBlank()) ? "github" : theme;
        String resolvedPaperSize = (paperSize == null || paperSize.isBlank()) ? "A4" : paperSize;
        return callMarkdownToPdfApi(markdownText, resolvedTheme, resolvedPaperSize);
    }

    // ==================== 接口实现：Markdown → HTML（仅 API） ====================

    /**
     * Markdown → HTML。
     * 调用 uapis API，可选择返回完整页面或 HTML 片段。
     */
    @Override
    public String markdownToHtml(String markdownText, boolean completePage) throws IOException {
        if (markdownText == null || markdownText.isBlank()) {
            throw new IOException("Markdown文本不能为空");
        }
        return callMarkdownToHtmlApi(markdownText, completePage);
    }

    // ==================== uapis API 调用封装 ====================

    /**
     * 调用 uapis.cn Markdown → PDF 接口。
     * POST /api/v1/text/markdown-to-pdf，返回 PDF 二进制流。
     *
     * 流程：
     *   1. 读取环境变量 UAPIS_API_KEY
     *   2. 构造 JSON 请求体：text（Markdown 内容）、theme（主题）、paper_size（纸张）
     *   3. POST 请求 https://uapis.cn/api/v1/text/markdown-to-pdf
     *   4. 校验 HTTP 状态码，读取响应体字节流
     *   5. 返回 PDF 字节数组
     */
    private byte[] callMarkdownToPdfApi(String markdown, String theme, String paperSize) throws IOException {
        // 步骤1：获取 API Key
        String apiKey = getUapiKey();
        if (apiKey == null) {
            throw new IOException("UAPIS_API_KEY 未设置，请注册 https://uapis.cn 获取");
        }

        // 步骤2：构造请求体
        String requestBodyJson = objectMapper.writeValueAsString(
                java.util.Map.of(
                        "text", markdown,
                        "theme", theme,
                        "paper_size", paperSize));
        RequestBody body = RequestBody.create(
                requestBodyJson, okhttp3.MediaType.get("application/json; charset=utf-8"));
        // 步骤3：发起 POST 请求
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
            // 步骤4-5：读取 PDF 字节
            byte[] pdf = resp.body() != null ? resp.body().bytes() : null;
            if (pdf == null || pdf.length == 0) {
                throw new IOException("API 返回空 PDF");
            }
            return pdf;
        }
    }

    /**
     * 调用 uapis.cn Markdown → HTML 接口。
     * POST /api/v1/text/markdown-to-html。
     *
     * 流程：
     *   1. 读取环境变量 UAPIS_API_KEY
     *   2. 构造 JSON 请求体：text（Markdown）、format（html/json）
     *      - completePage=true  → format=html，接口直接返回完整 HTML 页面字符串
     *      - completePage=false → format=json，接口返回 JSON，从 html 字段提取
     *   3. POST 请求 https://uapis.cn/api/v1/text/markdown-to-html
     *   4. 校验状态码，根据 format 参数返回 HTML 字符串
     */
    private String callMarkdownToHtmlApi(String markdown, boolean completePage) throws IOException {
        // 步骤1：获取 API Key
        String apiKey = getUapiKey();
        if (apiKey == null) {
            throw new IOException("UAPIS_API_KEY 未设置，请注册 https://uapis.cn 获取");
        }

        // 步骤2：构造请求体
        String requestBodyJson = objectMapper.writeValueAsString(
                java.util.Map.of(
                        "text", markdown,
                        "format", completePage ? "html" : "json"));
        RequestBody body = RequestBody.create(
                requestBodyJson, okhttp3.MediaType.get("application/json; charset=utf-8"));
        // 步骤3：发起 POST 请求
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
            // 步骤4：根据 format 返回不同格式
            if (completePage) {
                return resp.body() != null ? resp.body().string() : "";
            }
            String json = resp.body() != null ? resp.body().string() : "";
            JsonNode root = objectMapper.readTree(json);
            return root.path("html").asText();
        }
    }

    private String getUapiKey() {
        return System.getenv("UAPIS_API_KEY");
    }

    // ==================== @Tool 方法（AI 可调用） ====================

    /**
     * AI 工具：从 Word 或 PDF 文档中提取纯文本。
     * 输入为 Base64 编码的文件数据，输出为提取的文本内容。
     */
    @Tool(value = "当用户需要从Word文档(.docx)、PDF、Markdown或TXT文件中提取纯文本内容时，调用此工具。" +
            "参数mimeType为文件类型，base64Data为文件的Base64编码数据。返回提取的纯文本内容。")
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

        try {
            return toTxt(fileData, mimeType);
        } catch (IOException e) {
            return "不支持的文件格式: " + mimeType + "，仅支持 .docx / .pdf / .md / .txt";
        }
    }

    /**
     * AI 工具：Markdown 文本 → PDF。
     * 返回 Base64 编码的 PDF 数据，方便 AI 在回复中引用。
     */
    @Tool(value = "当用户需要将Markdown文本内容直接转换为PDF文件时，调用此工具。" +
            "markdownText为Markdown格式的文本，theme为可选主题(github/minimal/light/dark，默认github)，" +
            "paperSize为可选纸张大小(A4/Letter，默认A4)。返回Base64编码的PDF文件数据。")
    public String convertMarkdownToPdf(String markdownText, String theme, String paperSize) {
        try {
            byte[] pdfData = markdownToPdf(markdownText, theme, paperSize);
            return Base64.getEncoder().encodeToString(pdfData);
        } catch (IOException e) {
            LOGGER.error("Markdown转PDF失败", e);
            return "错误：Markdown转PDF失败 - " + e.getMessage();
        }
    }

    /**
     * AI 工具：Markdown 文本 → HTML。
     * completePage=true 时返回完整 HTML 页面结构，false 时仅返回片段。
     */
    @Tool(value = "当用户需要将Markdown文本内容转换为HTML时，调用此工具。" +
            "markdownText为Markdown格式的文本，completePage表示是否生成完整HTML页面(含DOCTYPE和样式)，" +
            "false则只返回HTML片段。返回HTML内容。")
    public String convertMarkdownToHtml(String markdownText, boolean completePage) {
        try {
            return markdownToHtml(markdownText, completePage);
        } catch (IOException e) {
            LOGGER.error("Markdown转HTML失败", e);
            return "错误：Markdown转HTML失败 - " + e.getMessage();
        }
    }

    /**
     * AI 工具：查询当前系统支持的所有文件格式转换类型。
     */
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

    // ==================== 文档文本提取（私有方法） ====================

    /**
     * 从 .docx 字节中提取纯文本，包含段落和表格内容。
     */
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

    /**
     * 从 PDF 字节中提取纯文本。
     * 写入临时文件后用 PDFBox 读取，按位置排序以保持段落顺序。
     */
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

    // ==================== 音频解码（私有方法） ====================

    /**
     * MP3 → PCM → WAV。
     * 使用 javax.sound 解码 MP3，重采样为 16kHz 16bit 单声道 PCM，再封装 WAV 头。
     *
     * 流程：
     *   1. 将 MP3 字节包装为 ByteArrayInputStream
     *   2. AudioSystem.getAudioInputStream() 自动识别 MP3 格式并创建解码流
     *   3. 构造目标格式：16kHz / 16bit / 单声道 / PCM_SIGNED / 小端序
     *   4. AudioSystem.getAudioInputStream(target, ais) 重采样转换
     *   5. AudioSystem.write() 自动封装 RIFF/WAVE 头部并写出完整 WAV 文件
     *   6. 返回 WAV 字节数组
     */
    private byte[] decodeMp3ToPcm(byte[] mp3Data) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(mp3Data);
             AudioInputStream ais = AudioSystem.getAudioInputStream(bais)) {
            // 步骤3：目标 PCM 格式参数
            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, 16000, 16, 1, 2, 16000, false);
            try (AudioInputStream converted = AudioSystem.getAudioInputStream(target, ais);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                // 步骤5：写出 WAV（含 RIFF + fmt + data 头）
                AudioSystem.write(converted, AudioFileFormat.Type.WAVE, baos);
                return baos.toByteArray();
            }
        } catch (Exception e) {
            throw new IOException("MP3解码失败", e);
        }
    }

    /**
     * M4A/AAC → PCM → WAV。
     * 使用 jcodec 库逐帧解码 AAC，重采样为 16kHz 16bit 单声道 PCM，再封装 WAV 头。
     *
     * 流程：
     *   1. 将 M4A 字节写入临时 .m4a 文件（jcodec 需要 FileChannel 接口）
     *   2. 用 RandomAccessFile + FileChannel 打开临时文件
     *   3. MP4Demuxer 解复用 MP4 容器，获取音频轨道（AAC）
     *   4. 从音频轨道读取第一帧，用其数据初始化 AACDecoder（解析 AAC 配置）
     *   5. 循环读取后续所有音频帧，逐帧调用 AACDecoder.decodeFrame() 解码为 PCM
     *   6. 将完整 PCM 裸数据传入 writeWav()，手动组装 RIFF/WAVE 头部（fmt + data 块）
     *   7. 清理临时文件，返回完整 WAV 字节
     */
    private byte[] decodeM4aToPcm(byte[] m4aData) throws IOException {
        File tmp = null;
        try {
            // 步骤1：写入临时文件
            tmp = saveTemp(m4aData, ".m4a");
            try (RandomAccessFile raf = new RandomAccessFile(tmp, "r");
                 FileChannel ch = raf.getChannel();
                 FileChannelWrapper fcw = new FileChannelWrapper(ch)) {
                // 步骤3：解复用 MP4，获取 AAC 音频轨道
                MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(fcw);
                List<DemuxerTrack> tracks = demuxer.getAudioTracks();
                if (tracks.isEmpty()) throw new IOException("没有音频轨道");

                DemuxerTrack track = tracks.get(0);
                Packet first = track.nextFrame();
                if (first == null) throw new IOException("没有音频帧");

                // 步骤4：用第一帧初始化 AAC 解码器
                AACDecoder decoder = new AACDecoder(first.getData());
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                ByteBuffer buf = ByteBuffer.allocate(8192);

                // 步骤5：逐帧解码
                decodeFrame(decoder, first, buf, pcm);
                Packet p;
                while ((p = track.nextFrame()) != null) decodeFrame(decoder, p, buf, pcm);

                // 步骤6：封装 WAV 头
                return writeWav(pcm.toByteArray(), 16000);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("M4A解码失败", e);
        } finally {
            // 步骤7：清理临时文件
            deleteFile(tmp);
        }
    }

    /**
     * 解码单帧 AAC 数据并写入 PCM 输出流。
     */
    private void decodeFrame(AACDecoder decoder, Packet packet, ByteBuffer out,
                             ByteArrayOutputStream pcm) throws IOException {
        out.clear();
        decoder.decodeFrame(packet.getData(), out);
        out.flip();
        byte[] b = new byte[out.remaining()];
        out.get(b);
        pcm.write(b);
    }

    // ==================== WAV 封装工具 ====================

    /**
     * 将 PCM 裸数据封装为标准 WAV 文件（RIFF/WAVE 格式）。
     *
     * WAV 文件结构（44 字节头部 + PCM 数据）：
     *   [0-3]    RIFF 标记 "RIFF"
     *   [4-7]    文件总长度 - 8（小端 32bit）
     *   [8-11]   WAVE 标记 "WAVE"
     *   [12-15]  fmt  子块标记 "fmt "
     *   [16-19]  fmt 子块长度（16 = PCM）
     *   [20-21]  音频格式（1 = PCM）
     *   [22-23]  声道数（1 = 单声道）
     *   [24-27]  采样率（Hz）
     *   [28-31]  字节率 = 采样率 × 声道数 × 位深/8
     *   [32-33]  块对齐 = 声道数 × 位深/8
     *   [34-35]  位深（16bit）
     *   [36-39]  data 子块标记 "data"
     *   [40-43]  PCM 数据长度
     *   [44..]   PCM 音频数据
     *
     * @param pcm        PCM 音频数据
     * @param sampleRate 采样率（Hz）
     * @return 完整的 WAV 文件字节
     */
    private byte[] writeWav(byte[] pcm, int sampleRate) throws IOException {
        int channels = 1, bits = 16;
        int byteRate = sampleRate * channels * bits / 8;
        int align = channels * bits / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        // RIFF 头
        writeStr(out, "RIFF");
        writeLe32(out, 36 + pcm.length);
        writeStr(out, "WAVE");
        // fmt 子块
        writeStr(out, "fmt ");
        writeLe32(out, 16);
        writeLe16(out, 1);
        writeLe16(out, channels);
        writeLe32(out, sampleRate);
        writeLe32(out, byteRate);
        writeLe16(out, align);
        writeLe16(out, bits);
        // data 子块
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

    // ==================== TXT / MD ↔ DOCX 辅助方法 ====================

    /**
     * PDF → DOCX。
     * PDFBox 提取文本 → docx4j 逐行写入。
     *
     * 流程：
     *   1. 写入临时 .pdf 文件
     *   2. PDFBox 按位置排序提取纯文本
     *   3. docx4j 逐行添加段落
     *   4. 保存为 .docx 字节流
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

    /**
     * TXT → DOCX。
     * 逐行写入 docx4j 段落。
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
     * Markdown → DOCX。
     * 解析 Markdown 语法，生成带样式的 Word 文档。
     *
     * 流程：
     *   1. 逐行扫描 Markdown 文本
     *   2. # ~ ###### 标题 → 对应 Heading 层级
     *   3. - / * 无序列表 → 添加 bullet 段落
     *   4. 普通文本 → 直接写入段落
     *   5. ``` 代码块 → 按普通文本段落写入
     */
    private byte[] mdToDocx(String markdown) throws IOException {
        try {
            org.docx4j.openpackaging.packages.WordprocessingMLPackage wordPkg =
                    org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
            String[] lines = markdown.split("\\r?\\n");
            boolean inCodeBlock = false;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("```")) {
                    inCodeBlock = !inCodeBlock;
                    continue;
                }
                if (inCodeBlock || trimmed.isEmpty()) continue;

                // Heading: # ~ ######
                java.util.regex.Matcher headingMatcher =
                        java.util.regex.Pattern.compile("^(#{1,6})\\s+(.*)").matcher(trimmed);
                if (headingMatcher.matches()) {
                    int level = headingMatcher.group(1).length();
                    String content = headingMatcher.group(2);
                    wordPkg.getMainDocumentPart().addParagraphOfText(content);
                    continue;
                }

                // Unordered list: - or *
                if (trimmed.matches("^[-*]\\s+.*")) {
                    String content = trimmed.replaceAll("^[-*]\\s+", "");
                    wordPkg.getMainDocumentPart().addParagraphOfText(content);
                    continue;
                }

                // Regular paragraph
                wordPkg.getMainDocumentPart().addParagraphOfText(trimmed);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wordPkg.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IOException("Markdown转Word失败", e);
        }
    }

    /**
     * Markdown → 纯文本。
     * 剥离 Markdown 语法标记，仅保留可读文本。
     *
     * 流程：
     *   1. 移除 ``` 代码块标记
     *   2. 移除 # 标题标记
     *   3. 移除 - / * 列表标记
     *   4. 移除 ** / * / ` 等行内标记
     *   5. 移除图片/链接语法，仅保留显示文本
     */
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

    /**
     * 将 docx 文档解析为 Markdown 格式文本。
     * 支持段落、标题（Heading 样式 → # 标记）和表格（→ Markdown 表格语法）。
     *
     * 流程：
     *   1. 用 POI（XWPFDocument）加载 docx 文件流
     *   2. 遍历所有 body 元素
     *      a. 段落 PARAGRAPH
     *         - 空行 → 保留换行
     *         - Heading 样式（如 "Heading1"）→ 解析层级 → 转为 # ~ ###### 标题
     *         - 普通段落 → 直接输出文本
     *      b. 表格 TABLE
     *         - 逐行读取单元格文本 → 拼接为 Markdown 表格行 | a | b | c |
     *         - 首行后自动添加分割行 | --- | --- | --- |
     *   3. 返回拼接后的 Markdown 字符串
     */
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
                // 识别 Heading 样式，转为 Markdown 标题
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
                    // 首行下方插入分割行
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

    /**
     * 将字节数组写入临时文件，供本地库（PDFBox、jcodec 等）读取。
     *
     * @param data   文件数据
     * @param suffix 文件后缀（如 .pdf、.m4a）
     * @return 创建的临时文件
     */
    private File saveTemp(byte[] data, String suffix) throws IOException {
        File f = new File(TEMP_DIR, UUID.randomUUID().toString() + suffix);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return f;
    }

    /**
     * 安全删除临时文件。
     */
    private void deleteFile(File f) {
        if (f != null && f.exists()) {
            try {
                Files.delete(f.toPath());
            } catch (IOException ignored) {}
        }
    }
}
