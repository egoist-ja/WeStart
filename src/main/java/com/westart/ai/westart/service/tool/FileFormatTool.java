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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.Optional;

@Service("fileFormatTool")
@RequiredArgsConstructor
@Slf4j
public class FileFormatTool {


    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    private final FileFormatConverter converter;
    private final ILinkClientSessionRegistry sessionRegistry;

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
            byte[] fileData = downloadFileWithRetry(client, item, fileName);
            if (fileData == null || fileData.length == 0) return null;
            if (fileData.length > MAX_FILE_SIZE) {
                log.warn("文件超过大小限制，userId={}，fileName={}，size={}", userId, fileName, fileData.length);
                return TextContent.from("文件大小超过 50MB 限制，无法处理。");
            }
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
        } catch (ILinkException e) {
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
            throw new IllegalStateException("无法获取客户端连接");
        }
        return client.get();
    }

    /** 工具方法。 */

    @Tool(value = "将用户在当前会话中已经上传的文件转换为Word文档并直接发送给用户。"
            + "本工具只处理带有[文件ID]的已上传文件，不接收Base64数据。"
            + "fileKey为[文件ID: xxx]中的xxx，必填。")
    public String convertToDocx(@ToolMemoryId String userId,
                                @P("[文件ID]中冒号后面的值，例如用户消息是[文件ID: abc]，则传abc") String fileKey) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertToDocx 开始执行，userId={}, fileKey={}", userId, fileKey);

        UserFileCache.StoredFile file = UserFileCache.get(fileKey);
        if (file == null) {
            throw new IllegalArgumentException("文件已过期或不存在，请重新发送文件");
        }

        ILinkClient client = getClient(userId);

        log.info("[FileFormatTool] 开始转换，fileName={}, mimeType={}, fileSize={} bytes",
                file.fileName(), file.mime(), file.data().length);
        byte[] result = converter.toDocx(file.data(), file.mime());
        log.info("[FileFormatTool] 转换完成，resultSize={} bytes", result.length);

        String newName = file.fileName().replaceAll("\\.[^.]+$", "") + ".docx";
        log.info("[FileFormatTool] 开始发送文件，newName={}", newName);
        client.sendFile(userId, result, newName, null);

        UserFileCache.remove(fileKey);
        long duration = System.currentTimeMillis() - startTime;
        log.info("[FileFormatTool] convertToDocx 执行成功，耗时={}ms", duration);
        return "已为您将 " + file.fileName() + " 转换为Word文档。";
    }

    @Tool(value = "将用户在当前会话中已经上传的文档转换为PDF并直接发送给用户。"
            + "本工具只处理带有[文件ID]的已上传文件，不接收Base64数据。"
            + "fileKey为[文件ID: xxx]中的xxx，必填。")
    public String convertToPdf(@ToolMemoryId String userId,
                               @P("[文件ID]中冒号后面的值，例如用户消息是[文件ID: abc]，则传abc") String fileKey) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertToPdf 开始执行，userId={}, fileKey={}", userId, fileKey);

        UserFileCache.StoredFile file = UserFileCache.get(fileKey);
        if (file == null) {
            throw new IllegalArgumentException("文件已过期或不存在，请重新发送文件");
        }

        ILinkClient client = getClient(userId);

        byte[] result;
        String mime = file.mime();
        log.info("[FileFormatTool] 开始转换，fileName={}, mimeType={}, fileSize={} bytes",
                file.fileName(), mime, file.data().length);

        if ("text/plain".equals(mime) || "text/markdown".equals(mime)) {
            log.info("[FileFormatTool] 文本/Markdown → PDF 路径");
            String text = converter.decodeText(file.data());
            result = converter.callMarkdownToPdfApi(text, "github", "A4");
        } else if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)) {
            log.info("[FileFormatTool] DOCX → PDF 路径");
            result = converter.toPdf(file.data(), mime);
        } else {
            throw new IllegalArgumentException("不支持将 " + file.fileName() + " 转换为PDF");
        }

        log.info("[FileFormatTool] 转换完成，resultSize={} bytes", result.length);
        String newName = file.fileName().replaceAll("\\.[^.]+$", "") + ".pdf";
        log.info("[FileFormatTool] 开始发送文件，newName={}", newName);
        client.sendFile(userId, result, newName, null);

        UserFileCache.remove(fileKey);
        long duration = System.currentTimeMillis() - startTime;
        log.info("[FileFormatTool] convertToPdf 执行成功，耗时={}ms", duration);
        return "已为您将 " + file.fileName() + " 转换为PDF。";
    }

    @Tool(value = "提取用户在当前会话中已经上传文件的纯文本内容，并以TXT文件直接发送给用户。"
            + "本工具只处理带有[文件ID]的已上传文件，不接收Base64数据。"
            + "fileKey为[文件ID: xxx]中的xxx，必填。")
    public String extractText(@ToolMemoryId String userId,
                              @P("[文件ID]中冒号后面的值，例如用户消息是[文件ID: abc]，则传abc") String fileKey) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] extractText 开始执行，userId={}, fileKey={}", userId, fileKey);

        UserFileCache.StoredFile file = UserFileCache.get(fileKey);
        if (file == null) {
            throw new IllegalArgumentException("文件已过期或不存在，请重新发送文件");
        }

        ILinkClient client = getClient(userId);

        log.info("[FileFormatTool] 开始提取文本，fileName={}, mimeType={}, fileSize={} bytes",
                file.fileName(), file.mime(), file.data().length);
        String text = converter.toTxt(file.data(), file.mime());
        log.info("[FileFormatTool] 文本提取完成，textLength={} chars", text.length());

        String baseName = file.fileName().replaceAll("\\.[^.]+$", "");
        log.info("[FileFormatTool] 开始发送文本文件，baseName={}", baseName);
        client.sendFile(userId, text.getBytes(java.nio.charset.StandardCharsets.UTF_8), baseName + ".txt", null);

        long duration = System.currentTimeMillis() - startTime;
        log.info("[FileFormatTool] extractText 执行成功，耗时={}ms", duration);
        return "已提取 " + file.fileName() + " 的文本内容并发送。";
    }

    @Tool(value = "从Base64编码的Word、PDF、Markdown或TXT文档中提取纯文本并直接返回文本内容。"
            + "本工具只处理调用方已经持有Base64数据的文档，不处理带有[文件ID]的会话上传文件。"
            + "base64Data和mimeType均为必填参数。")
    public String extractDocumentText(String base64Data, String mimeType) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] extractDocumentText 开始执行，mimeType={}", mimeType);

        if (base64Data == null || base64Data.isBlank()) {
            throw new IllegalArgumentException("文件内容为空");
        }

        byte[] fileData = Base64.getDecoder().decode(base64Data);
        log.info("[FileFormatTool] Base64解码完成，fileSize={} bytes", fileData.length);

        String result = converter.toTxt(fileData, mimeType);
        long duration = System.currentTimeMillis() - startTime;
        log.info("[FileFormatTool] extractDocumentText 执行成功，mimeType={}，耗时={}ms", mimeType, duration);
        return result;
    }

    @Tool(value = "将Markdown文本渲染为PDF文件，并返回PDF的Base64编码数据。"
            + "本工具处理直接提供的Markdown文本，不处理用户已经上传的文件。"
            + "markdownText为必填；theme为github、minimal、light或dark，选填；"
            + "paperSize为A4或Letter，选填。")
    public String convertMarkdownToPdf(String markdownText, String theme, String paperSize) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertMarkdownToPdf 开始执行，theme={}, paperSize={}", theme, paperSize);

        if (markdownText == null || markdownText.isBlank()) {
            throw new IllegalArgumentException("Markdown文本不能为空");
        }

        String resolvedTheme = (theme == null || theme.isBlank()) ? "github" : theme;
        String resolvedPaperSize = (paperSize == null || paperSize.isBlank()) ? "A4" : paperSize;
        byte[] pdfData = converter.callMarkdownToPdfApi(markdownText, resolvedTheme, resolvedPaperSize);
        String result = Base64.getEncoder().encodeToString(pdfData);
        long duration = System.currentTimeMillis() - startTime;
        log.info("[FileFormatTool] convertMarkdownToPdf 执行成功，pdfSize={} bytes，耗时={}ms", pdfData.length, duration);
        return result;
    }

    @Tool(value = "将Markdown文本转换为Word文档，并返回DOCX文件的Base64编码数据。"
            + "本工具处理直接提供的Markdown文本，不处理用户已经上传的文件。"
            + "markdownText为必填参数。")
    public String convertMarkdownToDocx(String markdownText) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertMarkdownToDocx 开始执行");

        byte[] docxData = converter.toDocx(
                markdownText.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/markdown");
        String result = Base64.getEncoder().encodeToString(docxData);
        long duration = System.currentTimeMillis() - startTime;
        log.info("[FileFormatTool] convertMarkdownToDocx 执行成功，docxSize={} bytes，耗时={}ms", docxData.length, duration);
        return result;
    }

    @Tool(value = "将Markdown文本转换为HTML内容，可生成完整HTML页面或仅生成HTML片段。"
            + "markdownText为必填；completePage为true时返回包含DOCTYPE和样式的完整页面，"
            + "为false时只返回HTML片段。")
    public String convertMarkdownToHtml(String markdownText, boolean completePage) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertMarkdownToHtml 开始执行，completePage={}", completePage);

        if (markdownText == null || markdownText.isBlank()) {
            throw new IllegalArgumentException("Markdown文本不能为空");
        }

        String result = converter.callMarkdownToHtmlApi(markdownText, completePage);
        long duration = System.currentTimeMillis() - startTime;
        log.info("[FileFormatTool] convertMarkdownToHtml 执行成功，htmlLength={} chars，耗时={}ms", result.length(), duration);
        return result;
    }

    @Tool("查询当前系统支持的文档输入格式、输出格式和文件转换组合。"
            + "本工具只返回能力说明，不执行文件转换。")
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
     * 带重试的文件下载（ILink 网络瞬断重试）。
     */
    private byte[] downloadFileWithRetry(ILinkClient client, MessageItem item, String fileName) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return client.downloadFileFromMessageItem(item);
            } catch (Exception e) {
                lastException = e;
                if (attempt < 3) {
                    long delayMs = attempt * 1000L + (long) (Math.random() * 500);
                    log.warn("文件下载第{}次尝试失败，{}ms后重试，fileName={}", attempt, delayMs, fileName);
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        log.error("文件下载最终失败，fileName={}", fileName, lastException);
        return null;
    }
}
