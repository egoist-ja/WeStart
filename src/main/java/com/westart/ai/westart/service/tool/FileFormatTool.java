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

    /** 单文件最大 50MB */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    /** 文件格式转换引擎，负责实际的转换逻辑 */
    private final FileFormatConverter converter;

    /** 微信客户端会话注册表，用于查找用户对应的 ILinkClient */
    private final ILinkClientSessionRegistry sessionRegistry;

    /**
     * 接收并缓存微信用户发送的文件。
     *
     * <p>下载文件 → 校验大小 → 识别扩展名 → 存入 {@link UserFileCache} → 返回提示文本给 AI 模型。</p>
     *
     * @param client 微信客户端
     * @param userId 消息发送者的用户 ID
     * @param item   包含文件信息的消息项
     * @return 含文件名的提示文本；下载失败或格式不支持时返回 null
     */
    public TextContent processIncomingFile(ILinkClient client, String userId, MessageItem item) {
        if (item.getFile_item() == null) return null;
        String fileName = item.getFile_item().getFile_name();
        if (fileName == null) return null;
        try {
            // 下载文件
            byte[] fileData = client.downloadFileFromMessageItem(item);
            if (fileData == null || fileData.length == 0) return null;
            if (fileData.length > MAX_FILE_SIZE) {
                log.warn("文件超过大小限制，userId={}，fileName={}，size={}", userId, fileName, fileData.length);
                return TextContent.from("文件大小超过 50MB 限制，无法处理。");
            }

            // 根据扩展名确定 MIME 类型
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

            // 缓存文件，用 userId 做 key
            String fileKey = UserFileCache.store(userId, fileName, fileData, mime);
            return TextContent.from("用户发送了文件: " + fileName + " [文件ID: " + fileKey + "]");
        } catch (IOException | ILinkException e) {
            log.warn("文件下载失败，userId={}，fileName={}", userId, fileName);
            return null;
        }
    }

    /**
     * 根据 userId 查找对应的微信客户端连接。
     *
     * @param userId 微信用户 ID
     * @return 客户端连接；未找到时返回 null
     */
    private ILinkClient getClient(String userId) {
        Optional<ILinkClient> client = sessionRegistry.findClientByUserId(userId);
        if (client.isEmpty()) {
            log.warn("未找到 userId={} 的客户端连接", userId);
        }
        return client.orElse(null);
    }

    /**
     * 将用户缓存的文件转为 DOCX 并通过微信发送回去。
     *
     * <p>从 {@link UserFileCache} 中以 userId 取文件 → {@link FileFormatConverter#toDocx} 转换 → 发送 → 清除缓存。</p>
     */
    @Tool(value = "将用户在当前会话中已经上传的文件转换为Word文档并直接发送给用户。只需要传userId。")
    public String convertToDocx(@ToolMemoryId String userId) {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertToDocx 开始执行，userId={}", userId);
        
        UserFileCache.StoredFile file = UserFileCache.get(userId);
        if (file == null) {
            log.warn("[FileFormatTool] 文件不存在或已过期，userId={}", userId);
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
            
            UserFileCache.remove(userId);
            long duration = System.currentTimeMillis() - startTime;
            log.info("[FileFormatTool] convertToDocx 执行成功，耗时={}ms", duration);
            return "已为您将 " + file.fileName() + " 转换为Word文档。";
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[FileFormatTool] convertToDocx 执行失败，userId={}，耗时={}ms", userId, duration, e);
            return "文件转换失败：" + e.getMessage();
        }
    }

    /**
     * 将用户缓存的文件转为 PDF 并通过微信发送回去。
     *
     * <p>文本/Markdown 直接走 UAPI 渲染；DOCX 先走 {@link FileFormatConverter#toPdf} 提取后渲染。</p>
     */
    @Tool(value = "将用户在当前会话中已经上传的文档转换为PDF并直接发送给用户。只需要传userId。")
    public String convertToPdf(@ToolMemoryId String userId) {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] convertToPdf 开始执行，userId={}", userId);
        
        UserFileCache.StoredFile file = UserFileCache.get(userId);
        if (file == null) {
            log.warn("[FileFormatTool] 文件不存在或已过期，userId={}", userId);
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
            
            UserFileCache.remove(userId);
            long duration = System.currentTimeMillis() - startTime;
            log.info("[FileFormatTool] convertToPdf 执行成功，耗时={}ms", duration);
            return "已为您将 " + file.fileName() + " 转换为PDF。";
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[FileFormatTool] convertToPdf 执行失败，userId={}，耗时={}ms", userId, duration, e);
            return "文件转换失败：" + e.getMessage();
        }
    }

    /**
     * 提取用户缓存文件的纯文本内容，生成 TXT 文件通过微信发送回去。
     */
    @Tool(value = "提取用户在当前会话中已经上传文件的纯文本内容，并以TXT文件直接发送给用户。只需要传userId。")
    public String extractText(@ToolMemoryId String userId) {
        long startTime = System.currentTimeMillis();
        log.info("[FileFormatTool] extractText 开始执行，userId={}", userId);
        
        UserFileCache.StoredFile file = UserFileCache.get(userId);
        if (file == null) {
            log.warn("[FileFormatTool] 文件不存在或已过期，userId={}", userId);
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
            log.error("[FileFormatTool] extractText 执行失败，userId={}，耗时={}ms", userId, duration, e);
            return "文件文本提取失败：" + e.getMessage();
        }
    }

    /**
     * 从 Base64 编码的文档数据中直接提取纯文本。
     *
     * <p>与 {@link #extractText} 不同，本方法不依赖文件缓存，适用于调用方已持有文件数据的场景。</p>
     */
    @Tool(value = "从Base64编码的Word、PDF、Markdown或TXT文档中提取纯文本并直接返回文本内容。"
            + "本工具只处理调用方已经持有Base64数据的文档，不处理带有[文件ID]的会话上传文件。"
            + "base64Data和mimeType均为必填参数。")
    public String extractDocumentText(
            @P("文件的Base64编码内容") String base64Data,
            @P("文件MIME类型，如 application/pdf") String mimeType) {
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

    /**
     * 将 Markdown 文本渲染为 PDF，返回 Base64 编码的 PDF 数据。
     *
     * <p>通过 UAPI 外部服务渲染，theme/paperSize 可选，未填时为 github/A4。</p>
     */
    @Tool(value = "将Markdown文本渲染为PDF文件，并返回PDF的Base64编码数据。"
            + "本工具处理直接提供的Markdown文本，不处理用户已经上传的文件。"
            + "markdownText为必填；theme为github、minimal、light或dark，选填；"
            + "paperSize为A4或Letter，选填。")
    public String convertMarkdownToPdf(
            @P("Markdown格式的文本内容") String markdownText,
            @P("渲染主题，可选 github/minimal/light/dark，默认 github") String theme,
            @P("纸张大小，可选 A4/Letter，默认 A4") String paperSize) {
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

    /**
     * 将 Markdown 文本转为 DOCX 文件，返回 Base64 编码的 DOCX 数据。
     *
     * <p>本地通过 docx4j 构建，识别标题和列表语法。</p>
     */
    @Tool(value = "将Markdown文本转换为Word文档，并返回DOCX文件的Base64编码数据。"
            + "本工具处理直接提供的Markdown文本，不处理用户已经上传的文件。"
            + "markdownText为必填参数。")
    public String convertMarkdownToDocx(
            @P("Markdown格式的文本内容") String markdownText) {
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

    /**
     * 将 Markdown 文本渲染为 HTML，通过 UAPI 外部服务完成。
     *
     * <p>{@code completePage=true} 返回完整页面，{@code false} 仅返回 HTML 片段。</p>
     */
    @Tool(value = "将Markdown文本转换为HTML内容，可生成完整HTML页面或仅生成HTML片段。"
            + "markdownText为必填；completePage为true时返回包含DOCTYPE和样式的完整页面，"
            + "为false时只返回HTML片段。")
    public String convertMarkdownToHtml(
            @P("Markdown格式的文本内容") String markdownText,
            @P("是否生成完整HTML页面，true生成含DOCTYPE和样式的完整页面，false返回HTML片段") boolean completePage) {
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

    /**
     * 返回系统支持的所有格式转换能力说明，仅查询不执行转换。
     */
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
}
