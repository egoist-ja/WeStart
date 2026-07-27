package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.westart.ai.westart.service.UserMessageService;
import com.westart.ai.westart.service.VoiceGenerateService;
import com.westart.ai.westart.service.ai.WeChatAssistant;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 用户消息服务实现，负责解析消息批次、调用微信助手并发送处理结果。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserMessageServiceImpl implements UserMessageService {

    private static final String DEFAULT_IMAGE_PROMPT = "请分析用户发送的图片并给出有帮助的回答。";
    private static final String EMPTY_VOICE_TRANSCRIPTION_REPLY =
            "语音消息未包含可用的转写内容呢，再试一遍吧？";
    private static final String MODEL_FAILURE_REPLY = "消息处理失败，请稍后重试。";

    private final ILinkClientSessionRegistry sessionRegistry;
    private final WeChatAssistant wechatAssistant;
    private final VoiceGenerateService voiceGenerateService;
    private final OkHttpClient okHttpClient;

    /**
     * 向指定微信用户发送文本消息。
     *
     * @param sessionId iLink客户端会话ID
     * @param userId 微信用户ID
     * @param content 消息内容
     */
    @Override
    public void sendMessage(String sessionId, String userId, String content) {
        ILinkClient client = sessionRegistry.getRequired(sessionId).client();
        sendMessage(client, sessionId, userId, content);
    }

    /**
     * 使用指定iLink客户端向微信用户发送文本消息。
     *
     * @param client 当前消息所属的iLink客户端
     * @param sessionId iLink客户端会话ID
     * @param userId 微信用户ID
     * @param content 消息内容
     */
    private void sendMessage(ILinkClient client, String sessionId,
            String userId, String content) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        if (!client.isLoggedIn()) {
            throw new IllegalStateException(
                    "微信客户端会话尚未登录，sessionId=" + sessionId);
        }

        try {
            client.sendText(userId, content);
            log.info("微信消息发送成功，sessionId={}，userId={}", sessionId, userId);
        } catch (IOException | ILinkException exception) {
            throw new IllegalStateException(
                    "微信消息发送失败，sessionId=" + sessionId + "，userId=" + userId,
                    exception);
        }
    }

    /**
     * 解析并处理指定用户的完整消息批次。
     *
     * @param sessionId iLink客户端会话ID
     * @param userId 微信用户ID
     * @param batchMessages 完成防抖收集的原始微信消息
     */
    @Override
    public void processMessageBatch(
            String sessionId,
            String userId,
            List<WeixinMessage> batchMessages) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (batchMessages == null || batchMessages.isEmpty()) {
            return;
        }

        ILinkClient client = sessionRegistry.getRequired(sessionId).client();
        try {
            boolean replyWithVoice = containsVoiceMessage(batchMessages);
            List<Content> contents = batchMessages.stream()
                    .map(message -> buildUserMessage(client, message))
                    .filter(Objects::nonNull)
                    .toList();
            if (contents.isEmpty()) {
                if (replyWithVoice) {
                    sendMessage(client, sessionId, userId, EMPTY_VOICE_TRANSCRIPTION_REPLY);
                }
                log.info("微信消息批次不包含可处理内容，sessionId={}，userId={}", sessionId, userId);
                return;
            }
            Result<String> result = wechatAssistant.reply(sessionId, prepareModelContents(contents));
            boolean imageSent = sendGeneratedImages(client, sessionId, userId, result.toolExecutions());
            if (!StringUtils.isBlank(result.content())) {
                if (replyWithVoice) {
                    voiceGenerateService.generateAndSendVoice(client, userId, result.content());
                } else {
                    sendMessage(client, sessionId, userId, result.content());
                }
            } else if (!imageSent) {
                throw new IllegalStateException("AI模型未返回有效回复");
            }
        } catch (RuntimeException exception) {
            log.error(
                    "微信消息模型处理失败，sessionId={}，userId={}",
                    sessionId,
                    userId,
                    exception);
            sendFailureReply(client, sessionId, userId);
        }
    }

    /**
     * 根据iLink消息项类型构建单条模型内容。
     *
     * @param client 当前消息所属的iLink客户端
     * @param message iLink原始微信消息
     * @return 可处理的模型内容；消息无效或类型不受支持时返回null
     */
    private Content buildUserMessage(
            ILinkClient client,
            WeixinMessage message) {
        if (message == null) {
            return null;
        }
        List<MessageItem> itemList = message.getItem_list();
        if (itemList == null || itemList.isEmpty()) {
            log.warn("微信消息不包含消息项，messageId={}", message.getMessage_id());
            return null;
        }

        for (MessageItem item : itemList) {
            if (item == null) {
                continue;
            }
            if (item.getText_item() != null) {
                String text = item.getText_item().getText();
                if (!StringUtils.isBlank(text)) {
                    return TextContent.from(text.trim());
                }
                continue;
            }
            if (item.getImage_item() != null) {
                return buildImageContent(client, message.getFrom_user_id(), item);
            }
            if (item.getVoice_item() != null) {
                return buildVoiceContent(message.getFrom_user_id(), item);
            }
            if (item.getVideo_item() != null) {
                log.info("忽略微信视频消息，userId={}", message.getFrom_user_id());
                return null;
            }
        }

        log.info(
                "忽略不支持的微信消息，userId={}，messageId={}",
                message.getFrom_user_id(),
                message.getMessage_id());
        return null;
    }

    /**
     * 下载微信图片并转换为模型图片内容。
     *
     * @param client 当前消息所属的iLink客户端
     * @param userId 微信用户ID
     * @param item 包含图片信息的消息项
     * @return 图片模型内容；下载失败或图片无效时返回null
     */
    private Content buildImageContent(
            ILinkClient client,
            String userId,
            MessageItem item) {
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("收到空的微信图片消息，userId={}", userId);
                return null;
            }
            String mimeType = detectImageMimeType(imageBytes);
            String base64Data = Base64.getEncoder().encodeToString(imageBytes);
            log.info("微信图片下载成功，userId={}，imageSize={}", userId, imageBytes.length);
            return ImageContent.from(base64Data, mimeType);
        } catch (IOException | ILinkException | IllegalArgumentException exception) {
            log.error("下载微信图片失败，userId={}", userId, exception);
            return null;
        }
    }

    /**
     * 将微信语音消息的转写结果转换为文本模型内容。
     *
     * @param userId 微信用户ID
     * @param item 包含语音转写结果的消息项
     * @return 语音转写文本；转写结果为空时返回null
     */
    private Content buildVoiceContent(String userId, MessageItem item) {
        String transcription = item.getVoice_item().getText();
        if (StringUtils.isBlank(transcription)) {
            log.warn("微信语音消息未包含转写文本，userId={}", userId);
            return null;
        }

        String normalizedTranscription = transcription.trim();
        log.info(
                "微信语音转文字成功，userId={}，textLength={}",
                userId,
                normalizedTranscription.length());
        return TextContent.from(normalizedTranscription);
    }

    /**
     * 判断当前批次是否包含微信语音消息。
     *
     * @param batchMessages 原始微信消息批次
     * @return 包含语音消息时返回true，否则返回false
     */
    private boolean containsVoiceMessage(List<WeixinMessage> batchMessages) {
        return batchMessages.stream()
                .filter(Objects::nonNull)
                .map(WeixinMessage::getItem_list)
                .filter(itemList -> itemList != null && !itemList.isEmpty())
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .anyMatch(item -> item.getVoice_item() != null);
    }

    /**
     * 为无文本的多模态消息补充默认提示词。
     *
     * @param contents 当前语义片段内容
     * @return 可直接交给模型的不可变内容列表
     */
    private List<Content> prepareModelContents(List<Content> contents) {
        boolean containsText = contents.stream().anyMatch(TextContent.class::isInstance);
        if (containsText) {
            return List.copyOf(contents);
        }

        List<Content> modelContents = new ArrayList<>(contents.size() + 1);
        modelContents.add(TextContent.from(DEFAULT_IMAGE_PROMPT));
        modelContents.addAll(contents);
        return List.copyOf(modelContents);
    }

    /**
     * 根据图片文件头识别MIME类型。
     *
     * @param imageBytes 图片原始字节
     * @return 图片MIME类型
     */
    private String detectImageMimeType(byte[] imageBytes) {
        if (startsWith(imageBytes, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(imageBytes, 0x89, 0x50, 0x4E, 0x47)) {
            return "image/png";
        }
        if (startsWith(imageBytes, 0x47, 0x49, 0x46, 0x38)) {
            return "image/gif";
        }
        if (startsWith(imageBytes, 0x42, 0x4D)) {
            return "image/bmp";
        }
        if (imageBytes.length >= 12
                && startsWith(imageBytes, 0x52, 0x49, 0x46, 0x46)
                && matchesAt(imageBytes, 8, 0x57, 0x45, 0x42, 0x50)) {
            return "image/webp";
        }
        throw new IllegalArgumentException("不支持或无法识别的图片格式");
    }

    /**
     * 判断字节数组是否以指定文件头开始。
     *
     * @param source 源字节数组
     * @param expected 期望的无符号字节序列
     * @return 匹配时返回true，否则返回false
     */
    private boolean startsWith(byte[] source, int... expected) {
        return matchesAt(source, 0, expected);
    }

    /**
     * 判断字节数组指定位置是否与目标字节序列匹配。
     *
     * @param source 源字节数组
     * @param offset 起始偏移量
     * @param expected 期望的无符号字节序列
     * @return 匹配时返回true，否则返回false
     */
    private boolean matchesAt(byte[] source, int offset, int... expected) {
        if (source.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (Byte.toUnsignedInt(source[offset + index]) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 发送图片生成工具返回的图片。
     *
     * @param client 当前消息所属的iLink客户端
     * @param sessionId iLink客户端会话ID
     * @param userId 微信用户ID
     * @param toolExecutions 工具执行结果
     * @return 至少发送一张图片时返回true
     */
    private boolean sendGeneratedImages(
            ILinkClient client,
            String sessionId,
            String userId,
            List<ToolExecution> toolExecutions) {
        if (toolExecutions == null || toolExecutions.isEmpty()) {
            return false;
        }

        boolean imageSent = false;
        for (ToolExecution execution : toolExecutions) {
            if (execution == null
                    || execution.hasFailed()
                    || execution.request() == null
                    || !"generateImage".equals(execution.request().name())) {
                continue;
            }
            if (!(execution.resultObject() instanceof List<?> resultList)) {
                throw new IllegalStateException("图片生成工具返回了无效结果");
            }
            for (int index = 0; index < resultList.size(); index++) {
                Object resultItem = resultList.get(index);
                if (!(resultItem instanceof Image image)) {
                    throw new IllegalStateException("图片生成工具返回了无效图片");
                }
                byte[] imageBytes = resolveGeneratedImageBytes(image);
                String fileName = "generated-image-" + (index + 1) + ".png";
                try {
                    client.sendImage(userId, imageBytes, fileName, null);
                    imageSent = true;
                    log.info(
                            "微信生成图片发送成功，sessionId={}，userId={}，imageSize={}",
                            sessionId,
                            userId,
                            imageBytes.length);
                } catch (IOException | ILinkException exception) {
                    throw new IllegalStateException(
                            "微信生成图片发送失败，sessionId="
                                    + sessionId
                                    + "，userId="
                                    + userId,
                            exception);
                }
            }
        }
        return imageSent;
    }

    /**
     * 获取图片生成结果的二进制数据。
     *
     * @param image 图片生成结果
     * @return 图片二进制数据
     */
    private byte[] resolveGeneratedImageBytes(Image image) {
        if (!StringUtils.isBlank(image.base64Data())) {
            try {
                return Base64.getDecoder().decode(image.base64Data());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("图片生成模型返回了无效的Base64数据", exception);
            }
        }
        if (image.url() == null) {
            throw new IllegalStateException("图片生成结果不包含URL或Base64数据");
        }

        Request request = new Request.Builder()
                .url(image.url().toString())
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                throw new IllegalStateException(
                        "下载生成图片失败，HTTP状态码=" + response.code());
            }
            byte[] imageBytes = responseBody.bytes();
            if (imageBytes.length == 0) {
                throw new IllegalStateException("下载生成图片失败，图片内容为空");
            }
            return imageBytes;
        } catch (IOException exception) {
            throw new IllegalStateException("下载生成图片失败，url=" + image.url(), exception);
        }
    }

    /**
     * 模型调用失败时发送统一提示，避免异常继续向上传播。
     *
     * @param client 当前消息所属的iLink客户端
     * @param sessionId iLink客户端会话ID
     * @param userId 微信用户ID
     */
    private void sendFailureReply(
            ILinkClient client,
            String sessionId,
            String userId) {
        try {
            sendMessage(client, sessionId, userId, MODEL_FAILURE_REPLY);
        } catch (RuntimeException exception) {
            log.error(
                    "微信模型失败提示发送失败，sessionId={}，userId={}",
                    sessionId,
                    userId,
                    exception);
        }
    }
}
