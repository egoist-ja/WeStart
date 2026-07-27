package com.westart.ai.westart.service.tool;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.westart.ai.westart.service.FileFormatService;
import com.westart.ai.westart.service.impl.ILinkClientSessionRegistry;
import com.westart.ai.westart.util.UserFileCache;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.message.TextContent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

@Service("fileOperationServiceImpl")
@RequiredArgsConstructor
public class FileFormatTool {

    private static final Logger log = LoggerFactory.getLogger(FileFormatTool.class);

    private final FileFormatService fileFormatService;
    private final ILinkClientSessionRegistry sessionRegistry;

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

    private ILinkClient getClient(String userId) {
        Optional<ILinkClient> client = sessionRegistry.findClientByUserId(userId);
        if (client.isEmpty()) {
            log.warn("未找到 userId={} 的客户端连接", userId);
        }
        return client.orElse(null);
    }

    @Tool(value = "将用户之前发送的文件转换为Word文档(.docx)。" +
            "fileKey的值来自之前用户消息中的[文件ID: xxx]，直接提取xxx传入即可。")
    public String convertToDocx(@ToolMemoryId String userId,
                                @P("[文件ID]中冒号后面的值，例如用户消息是[文件ID: abc]，则传abc") String fileKey) {
        UserFileCache.StoredFile file = UserFileCache.get(fileKey);
        if (file == null) return "错误：文件已过期或不存在，请重新发送文件。";
        ILinkClient client = getClient(userId);
        if (client == null) return "错误：无法获取客户端连接。";
        try {
            byte[] result = fileFormatService.toDocx(file.data(), file.mime());
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
                result = fileFormatService.markdownToPdf(text, "github", "A4");
            } else if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)) {
                result = fileFormatService.toPdf(file.data(), mime);
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
            String text = fileFormatService.toTxt(file.data(), file.mime());
            client.sendText(userId, file.fileName() + " 的文本内容：\n" + text);
            UserFileCache.remove(fileKey);
            return "已提取 " + file.fileName() + " 的文本内容并发送。";
        } catch (Exception e) {
            log.error("文件文本提取失败，fileKey={}", fileKey, e);
            return "文件文本提取失败：" + e.getMessage();
        }
    }
}
