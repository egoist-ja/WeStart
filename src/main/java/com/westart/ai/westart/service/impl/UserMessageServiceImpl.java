package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.westart.ai.westart.DTO.MessageContent;
import com.westart.ai.westart.DTO.SegmentResult;
import com.westart.ai.westart.service.ImageGenerateService;
import com.westart.ai.westart.service.MessageRouteService;
import com.westart.ai.westart.service.UserMessageService;
import com.westart.ai.westart.service.VoiceGenerateService;
import com.westart.ai.westart.service.ai.WeChatAssistant;
import com.westart.ai.westart.service.domain.RouteType;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 用户消息服务实现，负责解析消息批次、调用消息路由并分发到对应AI服务。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserMessageServiceImpl implements UserMessageService {

    private static final String DEFAULT_IMAGE_PROMPT = "请分析用户发送的图片并给出有帮助的回答。";
    private static final String DEFAULT_IMAGE_CLARIFICATION =
            "请补充希望生成的图片主体、场景或具体用途后，再发送完整的图片生成请求。";
    private static final String EMPTY_VOICE_TRANSCRIPTION_REPLY =
            "语音消息未包含可用的转写内容呢，再试一遍吧？";
    private static final String MODEL_FAILURE_REPLY = "消息处理失败，请稍后重试。";

    private final ILinkClient iLinkClient;
    private final WeChatAssistant wechatAssistant;
    private final MessageRouteService messageRouteService;
    private final ImageGenerateService imageGenerateService;
    private final VoiceGenerateService voiceGenerateService;

    /**
     * 向指定微信用户发送文本消息。
     *
     * @param userId 微信用户ID
     * @param content 消息内容
     */
    @Override
    public void sendMessage(String userId, String content) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        if (!iLinkClient.isLoggedIn()) {
            throw new IllegalStateException("微信机器人尚未登录");
        }

        try {
            iLinkClient.sendText(userId, content);
            log.info("微信消息发送成功，userId={}", userId);
        } catch (IOException | ILinkException exception) {
            throw new IllegalStateException("微信消息发送失败，userId=" + userId, exception);
        }
    }

    /**
     * 解析并处理指定用户的完整消息批次。
     *
     * @param userId 微信用户ID
     * @param batchMessages 完成防抖收集的原始微信消息
     */
    @Override
    public void processMessageBatch(String userId, List<WeixinMessage> batchMessages) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (batchMessages == null || batchMessages.isEmpty()) {
            return;
        }

        try {
            boolean replyWithVoice = containsVoiceMessage(batchMessages);
            List<MessageContent> messageContents = buildBatchContents(batchMessages);
            if (messageContents.isEmpty()) {
                if (replyWithVoice) {
                    sendMessage(userId, EMPTY_VOICE_TRANSCRIPTION_REPLY);
                }
                log.info("微信消息批次不包含可处理内容，userId={}", userId);
                return;
            }

            List<SegmentResult> segmentResults =
                    messageRouteService.classifyMessages(userId, messageContents);
            for (SegmentResult segmentResult : segmentResults) {
                processSegmentSafely(
                        userId,
                        messageContents,
                        segmentResult,
                        replyWithVoice);
            }
        } catch (RuntimeException exception) {
            log.error("微信消息模型处理失败，userId={}", userId, exception);
            sendFailureReply(userId);
        }
    }

    /**
     * 安全处理单个路由片段，避免一个片段失败影响同批次的其他片段。
     *
     * @param userId 微信用户ID
     * @param messageContents 当前批次的索引化消息
     * @param segmentResult 语义路由片段
     * @param replyWithVoice 是否使用语音回复
     */
    private void processSegmentSafely(
            String userId,
            List<MessageContent> messageContents,
            SegmentResult segmentResult,
            boolean replyWithVoice) {
        try {
            if (segmentResult.type() == RouteType.IMAGE && !segmentResult.executable()) {
                String clarification = StringUtils.isBlank(segmentResult.clarification())
                        ? DEFAULT_IMAGE_CLARIFICATION
                        : segmentResult.clarification();
                sendMessage(userId, clarification);
                return;
            }

            List<Content> segmentContents = messageRouteService.selectSegmentContents(
                    messageContents,
                    segmentResult.content());
            processRouteSegment(userId, segmentContents, segmentResult, replyWithVoice);
        } catch (RuntimeException exception) {
            log.error(
                    "微信消息路由片段处理失败，userId={}，routeType={}",
                    userId,
                    segmentResult.type(),
                    exception);
            sendFailureReply(userId);
        }
    }

    /**
     * 根据路由类型将单个语义片段分发给对应服务。
     *
     * @param userId 微信用户ID
     * @param segmentContents 当前语义片段内容
     * @param segmentResult 语义路由片段
     * @param replyWithVoice 是否使用语音回复
     */
    private void processRouteSegment(
            String userId,
            List<Content> segmentContents,
            SegmentResult segmentResult,
            boolean replyWithVoice) {
        switch (segmentResult.type()) {
            case CHAT -> {
                String reply = wechatAssistant.reply(prepareModelContents(segmentContents));
                if (replyWithVoice) {
                    voiceGenerateService.generateAndSendVoice(userId, reply);
                } else {
                    sendMessage(userId, reply);
                }
            }
            case IMAGE -> imageGenerateService.generateAndSendImages(
                    userId,
                    segmentContents,
                    segmentResult.context());
            case VIDEO -> log.info("暂不处理微信视频生成请求，userId={}", userId);
        }
    }

    /**
     * 将完整消息批次转换为带原始消息索引的模型内容。
     *
     * @param batchMessages 原始微信消息批次
     * @return 按原始顺序排列的索引化多模态内容
     */
    private List<MessageContent> buildBatchContents(List<WeixinMessage> batchMessages) {
        List<MessageContent> contents = new ArrayList<>(batchMessages.size());
        for (int index = 0; index < batchMessages.size(); index++) {
            Optional<Content> content = buildUserMessage(batchMessages.get(index));
            if (content.isPresent()) {
                contents.add(new MessageContent(index, content.get()));
            }
        }
        return List.copyOf(contents);
    }

    /**
     * 根据iLink消息项类型构建单条模型内容。
     *
     * @param message iLink原始微信消息
     * @return 可处理的模型内容；消息无效或类型不受支持时返回空
     */
    private Optional<Content> buildUserMessage(WeixinMessage message) {
        if (message == null) {
            return Optional.empty();
        }
        List<MessageItem> itemList = message.getItem_list();
        if (itemList == null || itemList.isEmpty()) {
            log.warn("微信消息不包含消息项，messageId={}", message.getMessage_id());
            return Optional.empty();
        }

        for (MessageItem item : itemList) {
            if (item == null) {
                continue;
            }
            if (item.getText_item() != null) {
                String text = item.getText_item().getText();
                if (!StringUtils.isBlank(text)) {
                    return Optional.of(TextContent.from(text.trim()));
                }
                continue;
            }
            if (item.getImage_item() != null) {
                return buildImageContent(message.getFrom_user_id(), item);
            }
            if (item.getVoice_item() != null) {
                return buildVoiceContent(message.getFrom_user_id(), item);
            }
            if (item.getVideo_item() != null) {
                log.info("忽略微信视频消息，userId={}", message.getFrom_user_id());
                return Optional.empty();
            }
        }

        log.info(
                "忽略不支持的微信消息，userId={}，messageId={}",
                message.getFrom_user_id(),
                message.getMessage_id());
        return Optional.empty();
    }

    /**
     * 下载微信图片并转换为模型图片内容。
     *
     * @param userId 微信用户ID
     * @param item 包含图片信息的消息项
     * @return 图片模型内容；下载失败或图片无效时返回空
     */
    private Optional<Content> buildImageContent(String userId, MessageItem item) {
        try {
            byte[] imageBytes = iLinkClient.downloadImageFromMessageItem(item);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("收到空的微信图片消息，userId={}", userId);
                return Optional.empty();
            }
            String mimeType = detectImageMimeType(imageBytes);
            String base64Data = Base64.getEncoder().encodeToString(imageBytes);
            log.info("微信图片下载成功，userId={}，imageSize={}", userId, imageBytes.length);
            return Optional.of(ImageContent.from(base64Data, mimeType));
        } catch (IOException | ILinkException | IllegalArgumentException exception) {
            log.error("下载微信图片失败，userId={}", userId, exception);
            return Optional.empty();
        }
    }

    /**
     * 将微信语音消息的转写结果转换为文本模型内容。
     *
     * @param userId 微信用户ID
     * @param item 包含语音转写结果的消息项
     * @return 语音转写文本；转写结果为空时返回空
     */
    private Optional<Content> buildVoiceContent(String userId, MessageItem item) {
        String transcription = item.getVoice_item().getText();
        if (StringUtils.isBlank(transcription)) {
            log.warn("微信语音消息未包含转写文本，userId={}", userId);
            return Optional.empty();
        }

        String normalizedTranscription = transcription.trim();
        log.info(
                "微信语音转文字成功，userId={}，textLength={}",
                userId,
                normalizedTranscription.length());
        return Optional.of(TextContent.from(normalizedTranscription));
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
     * 模型调用失败时发送统一提示，避免异常继续向上传播。
     *
     * @param userId 微信用户ID
     */
    private void sendFailureReply(String userId) {
        try {
            sendMessage(userId, MODEL_FAILURE_REPLY);
        } catch (RuntimeException exception) {
            log.error("微信模型失败提示发送失败，userId={}", userId, exception);
        }
    }
}
