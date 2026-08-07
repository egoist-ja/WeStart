package com.westart.ai.westart.memory.service.impl;

import com.westart.ai.westart.memory.domain.MemoryMessagePlaceholder;
import com.westart.ai.westart.memory.dto.MessageDTO;
import com.westart.ai.westart.memory.service.MessageFilterService;
import com.westart.ai.westart.memory.service.ai.MemoryAssistant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.westart.ai.westart.memory.service.ChatHistoryService.ROLE_USER;

/**
 * 记忆消息过滤实现，依次支持本地粗筛和模型语义细筛。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageFilterServiceImpl implements MessageFilterService {

    private static final Set<String> LOW_VALUE_TEXTS = Set.of(
            "你好", "您好", "谢谢", "感谢", "多谢", "哈哈", "哈哈哈",
            "不客气", "好的", "好", "收到", "知道了", "嗯", "哦");
    private static final Pattern TRAILING_PUNCTUATION =
            Pattern.compile("[。！？!?…~～，,.]+$");

    private final MemoryAssistant memoryAssistant;
    private final ObjectMapper objectMapper;

    /**
     * 过滤空内容、简单寒暄和仅包含消息占位符的内容。
     */
    @Override
    public List<MessageDTO> looseFilter(List<MessageDTO> messages) {
        if (messages == null) {
            throw new IllegalArgumentException("待粗筛消息列表不能为空");
        }
        if (messages.isEmpty()) {
            return List.of();
        }
        List<MessageDTO> filteredMessages = new ArrayList<>(messages.size());
        for (MessageDTO message : messages) {
            validateMessage(message);
            if (isPotentiallyValuable(message.content())) {
                filteredMessages.add(message);
            }
        }
        return List.copyOf(filteredMessages);
    }

    /**
     * 根据模型返回的消息ID筛选消息，并保持输入消息的原始顺序。
     */
    @Override
    public List<MessageDTO> strictFilter(List<MessageDTO> messages) {
        if (messages == null) {
            throw new IllegalArgumentException("待细筛消息列表不能为空");
        }
        if (messages.isEmpty()) {
            return List.of();
        }

        Map<String, MessageDTO> messageMap = indexMessages(messages);
        MemoryAssistant.FilterResult filterResult;
        try {
            filterResult = memoryAssistant.strictFilterMessages(toFilterJson(messages));
        } catch (RuntimeException exception) {
            log.error("记忆消息语义细筛失败，messageCount={}", messages.size(), exception);
            throw new IllegalStateException("记忆消息语义细筛失败", exception);
        }

        List<String> selectedMessageIds = filterResult == null
                ? List.of()
                : filterResult.selectedMessageIds();
        selectedMessageIds = selectedMessageIds == null ? List.of() : selectedMessageIds;

        Set<String> validMessageIds = new LinkedHashSet<>();
        for (String messageId : selectedMessageIds) {
            if (StringUtils.isNotBlank(messageId) && messageMap.containsKey(messageId)) {
                validMessageIds.add(messageId);
            }
        }
        boolean containsUserMessage = validMessageIds.stream()
                .map(messageMap::get)
                .anyMatch(message -> ROLE_USER.equals(message.role()));
        List<MessageDTO> filteredMessages = containsUserMessage
                ? messages.stream()
                        .filter(message -> validMessageIds.contains(message.messageId()))
                        .toList()
                : List.of();
        log.info(
                "记忆消息语义细筛完成，inputCount={}，modelSelectedCount={}，"
                        + "filteredCount={}，selectedMessageIds={}",
                messages.size(),
                selectedMessageIds.size(),
                filteredMessages.size(),
                validMessageIds);
        return filteredMessages;
    }

    private boolean isPotentiallyValuable(String content) {
        if (StringUtils.isBlank(content)) {
            return false;
        }
        String normalizedContent = TRAILING_PUNCTUATION.matcher(content.trim())
                .replaceFirst("")
                .trim();
        if (LOW_VALUE_TEXTS.contains(normalizedContent)) {
            return false;
        }
        List<String> contentLines = content.lines()
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
        return !contentLines.stream().allMatch(MemoryMessagePlaceholder::isPlaceholder);
    }

    private Map<String, MessageDTO> indexMessages(List<MessageDTO> messages) {
        Map<String, MessageDTO> messageMap = new LinkedHashMap<>(messages.size());
        for (MessageDTO message : messages) {
            validateMessage(message);
            if (messageMap.putIfAbsent(message.messageId(), message) != null) {
                throw new IllegalArgumentException(
                        "待细筛消息包含重复messageId：" + message.messageId());
            }
        }
        return messageMap;
    }

    private void validateMessage(MessageDTO message) {
        if (message == null) {
            throw new IllegalArgumentException("待过滤消息列表不能包含空元素");
        }
        if (StringUtils.isBlank(message.messageId())) {
            throw new IllegalArgumentException("待过滤消息缺少messageId");
        }
        if (StringUtils.isBlank(message.role())) {
            throw new IllegalArgumentException("待过滤消息缺少role");
        }
    }

    /**
     * 只向过滤模型提供判断所需字段，不发送微信用户ID。
     */
    private String toFilterJson(List<MessageDTO> messages) {
        List<Map<String, Object>> filterMessages = messages.stream()
                .map(message -> {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("messageId", message.messageId());
                    fields.put("role", message.role());
                    fields.put("content", message.content());
                    fields.put("createdAt", message.createdAt());
                    return fields;
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(filterMessages);
        } catch (Exception exception) {
            log.error("记忆消息细筛输入序列化失败", exception);
            throw new IllegalStateException("记忆消息细筛输入序列化失败", exception);
        }
    }
}
