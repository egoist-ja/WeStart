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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    // ==================== 文件消息处理 ====================

    public TextContent processIncomingFile(ILinkClient client, String userId, MessageItem item) {
        if (item.getFile_item() == null) return null;
        String fileName = item.getFile_item().getFile_name();
        if (fileName == null) return null;
        try {
            byte[] fileData = client.downloadFileFromMessageItem(item);
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
        } catch (IOException | ILinkException e) {
            log.warn("文件下载失败，userId={}，fileName={}", userId, fileName);
            return null;
        }
    }

    // ==================== 客户端连接 ====================

    private ILinkClient getClient(String userId) {
        Optional<ILinkClient> client = sessionRegistry.findClientByUserId(userId);
        if (client.isEmpty()) {
            log.warn("未找到 userId={} 的客户端连接", userId);
        }
        return client.orElse(null);
    }

    // ==================== 工具方法 ====================

    @Tool(value = "将用户之前发送的文件转换为Word文档(.docx)。" +
            "fileKey的值来自之前用户消息中的[文件ID: xxx]，直接提取xxx传入即可。")
    public String convertToDocx(@ToolMemoryId String userId,
                                @P("[文件ID]中冒号后面的值，例如用户消息是[文件ID: abc]，则传abc") String fileKey) {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertToDocx 开始执行，userId={}, fileKey={}", userId, fileKey);
        
        UserFileCache.StoredFile file = UserFileCache.get(fileKey);
        if (file == null) {
            log.warn("[FileFormatTool] 文件不存在或已过期，fileKey={}", fileKey);
            return "错误：文件已过期或不存在，请重新发送文件。";
        }
        
        ILinkClient client = getClient(userId);
        if (client == null) {
            log.error("[FileFormatTool] 无法获取客户端连接，userId={}", userId);
            return "错误：无法获取客户端连接。";
        }
        
        try {
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
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[FileFormatTool] convertToDocx 执行失败，fileKey={}，耗时={}ms", fileKey, duration, e);
            return "文件转换失败：" + e.getMessage();
        }
    }

    @Tool(value = "将用户之前发送的文件转换为PDF。" +
            "fileKey的值来自之前用户消息中的[文件ID: xxx]，直接提取xxx传入即可。")
    public String convertToPdf(@ToolMemoryId String userId,
                               @P("[文件ID]中冒号后面的值，例如用户消息是[文件ID: abc]，则传abc") String fileKey) {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertToPdf 开始执行，userId={}, fileKey={}", userId, fileKey);
        
        UserFileCache.StoredFile file = UserFileCache.get(fileKey);
        if (file == null) {
            log.warn("[FileFormatTool] 文件不存在或已过期，fileKey={}", fileKey);
            return "错误：文件已过期或不存在，请重新发送文件。";
        }
        
        ILinkClient client = getClient(userId);
        if (client == null) {
            log.error("[FileFormatTool] 无法获取客户端连接，userId={}", userId);
            return "错误：无法获取客户端连接。";
        }
        
        try {
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
                log.warn("[FileFormatTool] 不支持的格式，mimeType={}", mime);
                return "错误：不支持将 " + file.fileName() + " 转换为PDF。";
            }
            
            log.info("[FileFormatTool] 转换完成，resultSize={} bytes", result.length);
            String newName = file.fileName().replaceAll("\\.[^.]+$", "") + ".pdf";
            log.info("[FileFormatTool] 开始发送文件，newName={}", newName);
            client.sendFile(userId, result, newName, null);
            
            UserFileCache.remove(fileKey);
            long duration = System.currentTimeMillis() - startTime;
            log.info("[FileFormatTool] convertToPdf 执行成功，耗时={}ms", duration);
            return "已为您将 " + file.fileName() + " 转换为PDF。";
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[FileFormatTool] convertToPdf 执行失败，fileKey={}，耗时={}ms", fileKey, duration, e);
            return "文件转换失败：" + e.getMessage();
        }
    }

    @Tool(value = "提取用户之前发送文件的纯文本内容。" +
            "fileKey的值来自之前用户消息中的[文件ID: xxx]，直接提取xxx传入即可。")
    public String extractText(@ToolMemoryId String userId,
                              @P("[文件ID]中冒号后面的值，例如用户消息是[文件ID: abc]，则传abc") String fileKey) {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] extractText 开始执行，userId={}, fileKey={}", userId, fileKey);
        
        UserFileCache.StoredFile file = UserFileCache.get(fileKey);
        if (file == null) {
            log.warn("[FileFormatTool] 文件不存在或已过期，fileKey={}", fileKey);
            return "错误：文件已过期或不存在，请重新发送文件。";
        }
        
        ILinkClient client = getClient(userId);
        if (client == null) {
            log.error("[FileFormatTool] 无法获取客户端连接，userId={}", userId);
            return "错误：无法获取客户端连接。";
        }
        
        try {
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
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[FileFormatTool] extractText 执行失败，fileKey={}，耗时={}ms", fileKey, duration, e);
            return "文件文本提取失败：" + e.getMessage();
        }
    }

    @Tool(value = "当用户需要从Word文档(.docx)、PDF、Markdown或TXT文件中提取纯文本内容时，调用此工具。" +
            "参数mimeType为文件类型，base64Data为文件的Base64编码数据。返回提取的纯文本内容。")
    public String extractDocumentText(String base64Data, String mimeType) {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] extractDocumentText 开始执行，mimeType={}", mimeType);
        
        if (base64Data == null || base64Data.isBlank()) {
            log.warn("[FileFormatTool] 文件内容为空");
            return "错误：文件内容为空";
        }
        
        byte[] fileData;
        try {
            fileData = Base64.getDecoder().decode(base64Data);
            log.info("[FileFormatTool] Base64解码完成，fileSize={} bytes", fileData.length);
        } catch (IllegalArgumentException e) {
            log.warn("[FileFormatTool] Base64解码失败", e);
            return "错误：无效的Base64编码数据";
        }
        
        try {
            String result = converter.toTxt(fileData, mimeType);
            long duration = System.currentTimeMillis() - startTime;
            log.info("[FileFormatTool] extractDocumentText 执行成功，mimeType={}，耗时={}ms", mimeType, duration);
            return result;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[FileFormatTool] extractDocumentText 执行失败，mimeType={}，耗时={}ms", mimeType, duration, e);
            return "不支持的文件格式: " + mimeType + "，仅支持 .docx / .pdf / .md / .txt";
        }
    }

    @Tool(value = "当用户需要将Markdown文本内容直接转换为PDF文件时，调用此工具。" +
            "markdownText为Markdown格式的文本，theme为可选主题(github/minimal/light/dark，默认github)，" +
            "paperSize为可选纸张大小(A4/Letter，默认A4)。返回Base64编码的PDF文件数据。")
    public String convertMarkdownToPdf(String markdownText, String theme, String paperSize) {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertMarkdownToPdf 开始执行，theme={}, paperSize={}", theme, paperSize);
        
        if (markdownText == null || markdownText.isBlank()) {
            log.warn("[FileFormatTool] Markdown文本为空");
            return "错误：Markdown文本不能为空";
        }
        
        try {
            String resolvedTheme = (theme == null || theme.isBlank()) ? "github" : theme;
            String resolvedPaperSize = (paperSize == null || paperSize.isBlank()) ? "A4" : paperSize;
            byte[] pdfData = converter.callMarkdownToPdfApi(markdownText, resolvedTheme, resolvedPaperSize);
            String result = Base64.getEncoder().encodeToString(pdfData);
            long duration = System.currentTimeMillis() - startTime;
            log.info("[FileFormatTool] convertMarkdownToPdf 执行成功，pdfSize={} bytes，耗时={}ms", pdfData.length, duration);
            return result;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[FileFormatTool] convertMarkdownToPdf 执行失败，耗时={}ms", duration, e);
            return "错误：Markdown转PDF失败 - " + e.getMessage();
        }
    }

    @Tool(value = "当用户需要将Markdown文本内容直接转换为Word文档(.docx)时，调用此工具。" +
            "markdownText为Markdown格式的文本。返回Base64编码的docx文件数据。")
    public String convertMarkdownToDocx(String markdownText) {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertMarkdownToDocx 开始执行");
        
        try {
            byte[] docxData = converter.toDocx(
                    markdownText.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/markdown");
            String result = Base64.getEncoder().encodeToString(docxData);
            long duration = System.currentTimeMillis() - startTime;
            log.info("[FileFormatTool] convertMarkdownToDocx 执行成功，docxSize={} bytes，耗时={}ms", docxData.length, duration);
            return result;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[FileFormatTool] convertMarkdownToDocx 执行失败，耗时={}ms", duration, e);
            return "错误：Markdown转Word失败 - " + e.getMessage();
        }
    }

    @Tool(value = "当用户需要将Markdown文本内容转换为HTML时，调用此工具。" +
            "markdownText为Markdown格式的文本，completePage表示是否生成完整HTML页面(含DOCTYPE和样式)，" +
            "false则只返回HTML片段。返回HTML内容。")
    public String convertMarkdownToHtml(String markdownText, boolean completePage) {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertMarkdownToHtml 开始执行，completePage={}", completePage);
        
        if (markdownText == null || markdownText.isBlank()) {
            log.warn("[FileFormatTool] Markdown文本为空");
            return "错误：Markdown文本不能为空";
        }
        
        try {
            String result = converter.callMarkdownToHtmlApi(markdownText, completePage);
            long duration = System.currentTimeMillis() - startTime;
            log.info("[FileFormatTool] convertMarkdownToHtml 执行成功，htmlLength={} chars，耗时={}ms", result.length(), duration);
            return result;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[FileFormatTool] convertMarkdownToHtml 执行失败，耗时={}ms", duration, e);
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
}
