package com.westart.ai.westart.service.impl;

import com.westart.ai.westart.DTO.MessageContent;
import com.westart.ai.westart.DTO.SegmentResult;
import com.westart.ai.westart.DTO.batch.SegmentResultBatch;
import com.westart.ai.westart.service.MessageRouteService;
import com.westart.ai.westart.service.ai.WeChatMessageRouter;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息路由服务实现，负责调用路由模型、校验分类结果并按索引提取语义片段。
 */
@Service
@RequiredArgsConstructor
public class MessageRouteServiceImpl implements MessageRouteService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageRouteServiceImpl.class);

    private final WeChatMessageRouter weChatMessageRouter;

    /**
     * 调用消息路由模型，对当前用户批次进行语义分块和任务分类。
     *
     * @param userId 微信用户ID
     * @param messages 带原始消息索引的批次内容
     * @return 按用户原始表达顺序排列的有效路由片段
     */
    @Override
    public List<SegmentResult> classifyMessages(
            String userId,
            List<MessageContent> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Content> routerContents = new ArrayList<>(messages.size() * 2);
        Set<Integer> validIndexes = new HashSet<>(messages.size());
        for (MessageContent message : messages) {
            if (message == null || message.content() == null) {
                LOGGER.warn("路由输入包含空消息内容，userId={}", userId);
                continue;
            }
            if (message.index() < 0 || !validIndexes.add(message.index())) {
                throw new IllegalArgumentException(
                        "路由输入包含非法或重复的消息索引，userId=" + userId);
            }
            routerContents.add(TextContent.from("[消息索引：" + message.index() + "]"));
            routerContents.add(message.content());
        }
        if (routerContents.isEmpty()) {
            return List.of();
        }

        SegmentResultBatch resultBatch = weChatMessageRouter.route(List.copyOf(routerContents));
        if (resultBatch == null
                || resultBatch.segmentResults() == null
                || resultBatch.segmentResults().isEmpty()) {
            throw new IllegalStateException("消息路由模型未返回有效分类结果，userId=" + userId);
        }

        List<SegmentResult> validSegments = new ArrayList<>(resultBatch.segmentResults().size());
        for (SegmentResult segment : resultBatch.segmentResults()) {
            if (segment == null
                    || segment.type() == null
                    || segment.content() == null
                    || segment.content().isEmpty()) {
                LOGGER.warn("忽略结构不完整的消息路由片段，userId={}", userId);
                continue;
            }

            List<Integer> indexes = segment.content().stream()
                    .filter(validIndexes::contains)
                    .distinct()
                    .toList();
            if (indexes.isEmpty()) {
                LOGGER.warn("忽略未引用有效消息索引的路由片段，userId={}", userId);
                continue;
            }
            if (indexes.size() != segment.content().size()) {
                LOGGER.warn("消息路由片段包含非法或重复索引，已完成过滤，userId={}", userId);
            }

            String context = segment.context() == null ? "" : segment.context().trim();
            String clarification = segment.clarification() == null
                    ? ""
                    : segment.clarification().trim();
            validSegments.add(new SegmentResult(
                    segment.type(),
                    indexes,
                    context,
                    segment.executable(),
                    clarification));
        }
        if (validSegments.isEmpty()) {
            throw new IllegalStateException("消息路由模型返回的分类结果不可用，userId=" + userId);
        }

        LOGGER.info(
                "微信消息分类完成，userId={}，messageCount={}，segmentCount={}",
                userId,
                messages.size(),
                validSegments.size());
        return List.copyOf(validSegments);
    }

    /**
     * 创建原始消息索引，供路由结果按索引选择内容。
     *
     * @param messages 带索引的批次消息
     * @return 以原始消息索引为键的不可变映射
     */
    private Map<Integer, MessageContent> buildMessageIndex(List<MessageContent> messages) {
        Map<Integer, MessageContent> messageIndex = new HashMap<>(messages.size());
        for (MessageContent message : messages) {
            MessageContent previous = messageIndex.put(message.index(), message);
            if (previous != null) {
                throw new IllegalArgumentException("批次中存在重复的消息索引：" + message.index());
            }
        }
        return Map.copyOf(messageIndex);
    }

    /**
     * 按路由模型返回的索引顺序提取原始模型内容。
     *
     * @param messages 当前批次的索引化消息
     * @param selectedIndexes 路由片段引用的消息索引
     * @return 当前语义片段对应的不可变内容列表
     */
    @Override
    public List<Content> selectSegmentContents(
            List<MessageContent> messages,
            List<Integer> selectedIndexes) {
        Map<Integer, MessageContent> messageIndex = buildMessageIndex(messages);
        List<Content> contents = new ArrayList<>(selectedIndexes.size());
        for (Integer selectedIndex : selectedIndexes) {
            MessageContent message = messageIndex.get(selectedIndex);
            if (message == null) {
                throw new IllegalArgumentException("路由结果引用了不存在的消息索引：" + selectedIndex);
            }
            contents.add(message.content());
        }
        return List.copyOf(contents);
    }
}
