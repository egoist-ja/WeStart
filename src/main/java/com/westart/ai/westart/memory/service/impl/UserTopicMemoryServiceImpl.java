package com.westart.ai.westart.memory.service.impl;

import com.westart.ai.westart.memory.domain.TopicMemoryCategory;
import com.westart.ai.westart.memory.domain.TopicMemoryIndexStatus;
import com.westart.ai.westart.memory.dto.MessageDTO;
import com.westart.ai.westart.memory.dto.TopicMemorySummaryDTO;
import com.westart.ai.westart.memory.entity.MemorySourceMessage;
import com.westart.ai.westart.memory.entity.UserTopicMemory;
import com.westart.ai.westart.memory.repository.UserTopicMemoryRepository;
import com.westart.ai.westart.memory.service.UserTopicMemoryService;
import com.westart.ai.westart.memory.service.ai.TopicMemoryAssistant;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.westart.ai.westart.memory.service.ChatHistoryService.ROLE_USER;

/**
 * 用户主题记忆业务流程实现。
 *
 * 负责调用模型生成主题、校验结构化结果，并持久化到MySQL。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserTopicMemoryServiceImpl implements UserTopicMemoryService {

    private static final Set<String> MEANINGLESS_CONTENTS = Set.of(
            "你好", "您好", "谢谢", "感谢", "多谢", "哈哈", "哈哈哈", "不客气",
            "好的", "好", "收到", "知道了", "嗯", "哦", "嗨", "哈喽", "hello",
            "hi", "谢谢你", "可以", "嗯嗯", "噢", "再见", "拜拜");
    private static final Pattern MEANINGFUL_TEXT_PATTERN = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern SENSITIVE_VALUE_PATTERN = Pattern.compile(
            "(?i)(?:password|passwd|token|access[_ -]?token|refresh[_ -]?token|"
                    + "api[_ -]?key|secret(?:[_ -]?key)?|密码|口令)"
                    + "\\s*[:：=]\\s*[a-z0-9+/_@#$%^&*!.~-]{4,}");
    private static final Pattern VERIFICATION_CODE_PATTERN = Pattern.compile(
            "(?i)(?:验证码|verification\\s*code|otp)\\s*[:：=]?\\s*\\d{4,8}");
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
            "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");
    private final TopicMemoryAssistant topicMemoryAssistant;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final UserTopicMemoryRepository userTopicMemoryRepository;

    /**
     * 完成幂等检查、主题总结、结果校验和MySQL事务持久化。
     *
     * 模型没有生成主题时仍保存来源消息，避免重试时重复调用模型。
     * 重试批次包含部分已处理消息时，只处理尚未持久化的消息。
     *
     * @param messages 本批待分析的业务消息
     */
    @Override
    public void updateTopicMemory(List<MessageDTO> messages) {
        if (messages == null) {
            throw new IllegalArgumentException("主题记忆消息列表不能为空");
        }
        if (messages.isEmpty()) {
            return;
        }

        Map<String, MessageDTO> sourceMessages = indexSourceMessages(messages);
        List<MemorySourceMessage> sourceMessageEntities = assembleSourceMessages(
                sourceMessages);
        List<MemorySourceMessage> unstoredSourceMessages =
                userTopicMemoryRepository.selectUnstoredSourceMessages(sourceMessageEntities);
        if (unstoredSourceMessages.isEmpty()) {
            log.info("来源消息已经完成主题处理，跳过重复提取，messageCount={}", messages.size());
            return;
        }

        Map<String, MessageDTO> unstoredMessageMap = new LinkedHashMap<>(
                unstoredSourceMessages.size());
        for (MemorySourceMessage sourceMessage : unstoredSourceMessages) {
            unstoredMessageMap.put(
                    sourceMessage.getMessageId(),
                    sourceMessages.get(sourceMessage.getMessageId()));
        }
        List<MessageDTO> unstoredMessages = List.copyOf(unstoredMessageMap.values());
        boolean containsUserMessage = unstoredMessages.stream()
                .anyMatch(message -> ROLE_USER.equals(message.role()));
        if (!containsUserMessage) {
            userTopicMemoryRepository.saveSourceMessagesAndTopics(
                    unstoredSourceMessages, List.of());
            log.info(
                    "未持久化消息中没有用户消息，仅保存来源消息，messageCount={}",
                    unstoredSourceMessages.size());
            return;
        }
        String summaryJson = summarizeTopic(unstoredMessages);
        TopicMemorySummaryDTO validatedSummary = validateTopicSummary(
                summaryJson, unstoredMessageMap);
        List<UserTopicMemory> topicMemories = assembleTopicMemories(
                validatedSummary, unstoredMessageMap);
        userTopicMemoryRepository.saveSourceMessagesAndTopics(
                unstoredSourceMessages, topicMemories);
        log.info(
                "主题记忆MySQL持久化完成，messageCount={}，topicMemoryCount={}",
                unstoredSourceMessages.size(),
                topicMemories.size());
    }

    /**
     * 调用主题模型总结当前消息批次。
     *
     * @param messages 细筛后的业务消息
     * @return 模型返回的主题总结JSON
     * @throws IllegalStateException 模型调用失败时抛出
     */
    private String summarizeTopic(List<MessageDTO> messages) {
        try {
            return topicMemoryAssistant.summarizeTopic(
                    serializeModelInput(toModelInputMessages(messages)));
        } catch (RuntimeException exception) {
            log.error("主题记忆总结模型调用失败，messageCount={}", messages.size(), exception);
            throw new IllegalStateException("主题记忆总结模型调用失败", exception);
        }
    }

    /**
     * 解析并校验主题模型返回结果。
     *
     * @param summaryJson 模型返回的主题总结JSON
     * @param sourceMessages 当前批次按消息ID索引的来源消息
     * @return 完成规范化和校验的主题总结
     * @throws IllegalStateException JSON无效或总结内容不符合规则时抛出
     */
    private TopicMemorySummaryDTO validateTopicSummary(
            String summaryJson, Map<String, MessageDTO> sourceMessages) {
        if (StringUtils.isBlank(summaryJson)) {
            throw invalidSummary("模型返回内容不能为空");
        }
        try {
            TopicMemorySummaryDTO summary = objectMapper
                    .readerFor(TopicMemorySummaryDTO.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(summaryJson);
            if (summary == null) {
                throw invalidSummary("JSON根节点不能为空");
            }
            TopicMemorySummaryDTO normalizedSummary = normalizeSummary(summary);
            validateSummaryConstraints(normalizedSummary);
            validateSummaryBusinessRules(normalizedSummary, sourceMessages);
            return normalizedSummary;
        } catch (IllegalStateException exception) {
            log.warn("主题记忆总结结果校验失败，messageCount={}，reason={}",
                    sourceMessages.size(), exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            log.warn("主题记忆总结结果不是合法JSON，messageCount={}",
                    sourceMessages.size(), exception);
            throw invalidSummary("模型返回内容不是合法JSON", exception);
        }
    }

    /**
     * 将校验后的主题分块及来源消息组装为MySQL实体。
     *
     * @param validatedSummary 完成校验的主题总结
     * @param sourceMessages 当前批次按消息ID索引的来源消息
     * @return 待持久化的主题记忆
     */
    private List<UserTopicMemory> assembleTopicMemories(
            TopicMemorySummaryDTO validatedSummary,
            Map<String, MessageDTO> sourceMessages) {
        List<UserTopicMemory> topicMemories =
                new ArrayList<>(validatedSummary.chunks().size());
        for (TopicMemorySummaryDTO.TopicChunk chunk : validatedSummary.chunks()) {
            TopicMemorySourceContext sourceContext = resolveChunkSourceContext(
                    chunk.sourceMessageIds(), sourceMessages);
            UserTopicMemory topicMemory = new UserTopicMemory();
            topicMemory.setWechatUserId(sourceContext.wechatUserId());
            topicMemory.setTopicName(chunk.topicName());
            topicMemory.setTopicSummary(chunk.topicSummary());
            topicMemory.setCategory(TopicMemoryCategory.fromValue(chunk.category()));
            topicMemory.setTopicOccurredAt(sourceContext.occurredAt());
            topicMemory.setSourceMessageIds(serializeSourceMessageIds(
                    chunk.sourceMessageIds()));
            topicMemory.setIndexStatus(TopicMemoryIndexStatus.PENDING);
            topicMemories.add(topicMemory);
        }
        return List.copyOf(topicMemories);
    }

    /**
     * 将细筛后的消息转换为MySQL来源消息实体。
     *
     * @param sourceMessages 按消息ID索引的来源消息
     * @return 保持原始顺序的来源消息实体
     */
    private List<MemorySourceMessage> assembleSourceMessages(
            Map<String, MessageDTO> sourceMessages) {
        List<MemorySourceMessage> entities = new ArrayList<>(sourceMessages.size());
        for (MessageDTO message : sourceMessages.values()) {
            MemorySourceMessage entity = new MemorySourceMessage();
            entity.setWechatUserId(message.wechatUserId());
            entity.setMessageId(message.messageId());
            entity.setRole(message.role());
            entity.setContent(message.content());
            entity.setOccurredAt(message.createdAt());
            entities.add(entity);
        }
        return List.copyOf(entities);
    }

    /**
     * 将主题引用的来源消息ID序列化为JSON数组。
     *
     * @param sourceMessageIds 来源消息ID
     * @return JSON数组字符串
     */
    private String serializeSourceMessageIds(List<String> sourceMessageIds) {
        try {
            return objectMapper.writeValueAsString(sourceMessageIds);
        } catch (Exception exception) {
            log.error("主题来源消息ID序列化失败", exception);
            throw new IllegalStateException("主题来源消息ID序列化失败", exception);
        }
    }

    /**
     * 根据主题引用的来源消息计算用户和主题发生时间。
     *
     * 同一主题只能引用同一用户的消息，主题发生时间取引用消息中的最晚时间。
     *
     * @param messageIds 主题引用的来源消息ID
     * @param sourceMessages 当前批次按消息ID索引的来源消息
     * @return 主题持久化所需的用户和发生时间
     * @throws IllegalArgumentException 来源消息缺少用户或发生时间时抛出
     * @throws IllegalStateException 主题引用当前批次之外的消息时抛出
     */
    private TopicMemorySourceContext resolveChunkSourceContext(
            List<String> messageIds, Map<String, MessageDTO> sourceMessages) {
        String wechatUserId = null;
        Instant occurredAt = null;
        for (String messageId : messageIds) {
            MessageDTO message = sourceMessages.get(messageId);
            if (message == null) {
                throw new IllegalStateException("分块包含当前消息批次之外的sourceMessageId");
            }
            if (StringUtils.isBlank(message.wechatUserId())) {
                throw new IllegalArgumentException("主题来源消息缺少wechatUserId");
            }
            if (wechatUserId == null) {
                wechatUserId = message.wechatUserId();
            } else if (!wechatUserId.equals(message.wechatUserId())) {
                throw new IllegalArgumentException("同一主题分块不能包含不同微信用户的消息");
            }
            if (message.createdAt() == null) {
                throw new IllegalArgumentException("主题来源消息缺少createdAt");
            }
            if (occurredAt == null || message.createdAt().isAfter(occurredAt)) {
                occurredAt = message.createdAt();
            }
        }
        return new TopicMemorySourceContext(wechatUserId, occurredAt);
    }

    /**
     * 校验消息ID并按原始顺序建立来源消息索引。
     *
     * @param messages 细筛后的业务消息
     * @return 以消息ID为键的有序来源消息
     * @throws IllegalArgumentException 消息为空、缺少消息ID或消息ID重复时抛出
     */
    private Map<String, MessageDTO> indexSourceMessages(List<MessageDTO> messages) {
        Map<String, MessageDTO> sourceMessages = new LinkedHashMap<>(messages.size());
        for (MessageDTO message : messages) {
            if (message == null || StringUtils.isBlank(message.messageId())) {
                throw new IllegalArgumentException("主题记忆消息缺少messageId");
            }
            if (sourceMessages.putIfAbsent(message.messageId(), message) != null) {
                throw new IllegalArgumentException(
                        "主题记忆消息包含重复messageId：" + message.messageId());
            }
        }
        return sourceMessages;
    }

    /**
     * 去除主题字段和来源消息ID两端的空白，并规范化主题分类。
     *
     * @param summary 原始主题总结
     * @return 规范化后的主题总结
     */
    private TopicMemorySummaryDTO normalizeSummary(TopicMemorySummaryDTO summary) {
        if (summary == null || summary.chunks() == null) {
            return summary;
        }
        List<TopicMemorySummaryDTO.TopicChunk> normalizedChunks = summary.chunks()
                .stream()
                .map(chunk -> {
                    if (chunk == null) {
                        return null;
                    }
                    List<String> sourceMessageIds = chunk.sourceMessageIds() == null
                            ? null
                            : chunk.sourceMessageIds().stream()
                                    .map(messageId -> messageId == null
                                            ? null
                                            : messageId.trim())
                                    .toList();
                    return new TopicMemorySummaryDTO.TopicChunk(
                            chunk.topicName() == null
                                    ? null
                                    : chunk.topicName().trim(),
                            chunk.topicSummary() == null
                                    ? null
                                    : chunk.topicSummary().trim(),
                            TopicMemoryCategory.fromValue(chunk.category()).value(),
                            sourceMessageIds);
                })
                .toList();
        return new TopicMemorySummaryDTO(normalizedChunks);
    }

    /**
     * 使用Jakarta Validation校验主题总结的数据结构。
     *
     * @param summary 待校验的主题总结
     * @throws IllegalStateException 任一字段违反约束时抛出
     */
    private void validateSummaryConstraints(TopicMemorySummaryDTO summary) {
        Set<ConstraintViolation<TopicMemorySummaryDTO>> violations =
                validator.validate(summary);
        if (violations.isEmpty()) {
            return;
        }
        String details = violations.stream()
                .map(violation -> violation.getPropertyPath()
                        + " "
                        + violation.getMessage())
                .sorted()
                .reduce((left, right) -> left + "；" + right)
                .orElse("DTO字段校验失败");
        throw invalidSummary(details);
    }

    /**
     * 校验主题内容安全性和来源消息引用规则。
     *
     * @param summary 待校验的主题总结
     * @param sourceMessages 当前批次按消息ID索引的来源消息
     * @throws IllegalStateException 主题内容或来源引用不符合规则时抛出
     */
    private void validateSummaryBusinessRules(
            TopicMemorySummaryDTO summary,
            Map<String, MessageDTO> sourceMessages) {
        for (int index = 0; index < summary.chunks().size(); index++) {
            TopicMemorySummaryDTO.TopicChunk chunk = summary.chunks().get(index);
            String location = "chunks[" + index + "]";
            validateMeaningfulContent(chunk.topicSummary(), location);
            validateSensitiveContent(chunk.topicName(), chunk.topicSummary(), location);
            validateSourceMessageIds(
                    chunk.sourceMessageIds(), location, sourceMessages);
        }
    }

    /**
     * 校验主题摘要包含有意义的文字或数字。
     *
     * @param summary 主题摘要
     * @param location 当前主题在模型结果中的位置
     * @throws IllegalStateException 主题摘要只有寒暄或无意义字符时抛出
     */
    private void validateMeaningfulContent(String summary, String location) {
        String normalizedSummary = summary.replaceAll("[\\p{P}\\p{S}\\s]+", "")
                .toLowerCase(Locale.ROOT);
        if (!MEANINGFUL_TEXT_PATTERN.matcher(normalizedSummary).find()
                || MEANINGLESS_CONTENTS.contains(normalizedSummary)) {
            throw invalidSummary(location + ".topicSummary不能只有寒暄或无意义字符");
        }
    }

    /**
     * 校验主题名称和摘要不包含禁止持久化的敏感值。
     *
     * @param name 主题名称
     * @param summary 主题摘要
     * @param location 当前主题在模型结果中的位置
     * @throws IllegalStateException 检测到敏感值时抛出
     */
    private void validateSensitiveContent(
            String name, String summary, String location) {
        String topicContent = name + System.lineSeparator() + summary;
        if (SENSITIVE_VALUE_PATTERN.matcher(topicContent).find()
                || VERIFICATION_CODE_PATTERN.matcher(topicContent).find()
                || PRIVATE_KEY_PATTERN.matcher(topicContent).find()) {
            throw invalidSummary(location + "包含密码、Token、验证码或私钥等敏感信息");
        }
    }

    /**
     * 校验主题引用的消息真实存在、没有重复且至少包含一条用户消息。
     *
     * @param messageIds 主题引用的来源消息ID
     * @param location 当前主题在模型结果中的位置
     * @param sourceMessages 当前批次按消息ID索引的来源消息
     * @throws IllegalStateException 来源消息引用不符合规则时抛出
     */
    private void validateSourceMessageIds(
            List<String> messageIds,
            String location,
            Map<String, MessageDTO> sourceMessages) {
        Set<String> uniqueMessageIds = new LinkedHashSet<>();
        boolean hasUserMessage = false;
        for (String messageId : messageIds) {
            if (!uniqueMessageIds.add(messageId)) {
                throw invalidSummary(
                        location + ".sourceMessageIds包含重复ID：" + messageId);
            }
            MessageDTO message = sourceMessages.get(messageId);
            if (message == null) {
                throw invalidSummary(
                        location
                                + ".sourceMessageIds包含当前选中消息之外的ID："
                                + messageId);
            }
            if (ROLE_USER.equals(message.role())) {
                hasUserMessage = true;
            }
        }
        if (!hasUserMessage) {
            throw invalidSummary(location + "至少需要关联一条USER消息");
        }
    }

    /**
     * 构造统一格式的主题总结校验异常。
     *
     * @param reason 校验失败原因
     * @return 主题总结校验异常
     */
    private IllegalStateException invalidSummary(String reason) {
        return new IllegalStateException("主题记忆总结结果校验失败：" + reason);
    }

    /**
     * 构造包含原始异常的主题总结校验异常。
     *
     * @param reason 校验失败原因
     * @param cause 原始异常
     * @return 主题总结校验异常
     */
    private IllegalStateException invalidSummary(String reason, Exception cause) {
        return new IllegalStateException("主题记忆总结结果校验失败：" + reason, cause);
    }

    /**
     * 将业务消息转换为主题模型需要的最小输入结构。
     *
     * @param messages 细筛后的业务消息
     * @return 主题模型输入消息
     */
    private List<ModelInputMessage> toModelInputMessages(List<MessageDTO> messages) {
        return messages.stream()
                .map(message -> new ModelInputMessage(
                        message.messageId(),
                        message.role(),
                        message.content(),
                        message.createdAt() == null
                                ? null
                                : message.createdAt().toString()))
                .toList();
    }

    /**
     * 将主题模型输入消息序列化为JSON。
     *
     * @param messages 主题模型输入消息
     * @return 模型输入JSON
     * @throws IllegalStateException 序列化失败时抛出
     */
    private String serializeModelInput(List<ModelInputMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception exception) {
            log.error("主题记忆模型输入序列化失败", exception);
            throw new IllegalStateException("主题记忆模型输入序列化失败", exception);
        }
    }

    /**
     * 发送给主题总结模型的最小消息结构。
     *
     * @param messageId 业务消息唯一标识
     * @param role 消息角色
     * @param content 消息内容
     * @param createdAt 消息创建时间
     */
    private record ModelInputMessage(
            String messageId,
            String role,
            String content,
            String createdAt) {
    }

    /**
     * 由主题来源消息计算得到的持久化上下文。
     *
     * @param wechatUserId 主题所属微信用户ID
     * @param occurredAt 主题最近一次发生时间
     */
    private record TopicMemorySourceContext(String wechatUserId, Instant occurredAt) {
    }
}
