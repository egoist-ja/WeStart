package com.westart.ai.westart.memory.service.impl;

import com.westart.ai.westart.memory.domain.TopicMemoryCategory;
import com.westart.ai.westart.memory.dto.MessageDTO;
import com.westart.ai.westart.memory.dto.TopicMemorySummaryDTO;
import com.westart.ai.westart.memory.entity.UserTopicMemoryVector;
import com.westart.ai.westart.memory.entity.UserTopicMemory;
import com.westart.ai.westart.memory.repository.UserTopicMemoryRepository;
import com.westart.ai.westart.memory.repository.UserTopicMemoryVectorRepository;
import com.westart.ai.westart.memory.service.UserTopicMemoryService;
import com.westart.ai.westart.memory.service.ai.TopicMemoryAssistant;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.v2.service.vector.response.InsertResp;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>负责调用模型生成主题、校验结构化结果，并依次写入MySQL和Milvus。</p>
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
    private final UserTopicMemoryVectorRepository userTopicMemoryVectorRepository;
    private final EmbeddingModel embeddingModel;

    /**
     * 完成主题总结、结果校验和顺序持久化。
     *
     * <p>输入为空或没有生成有效主题分块时不执行持久化。</p>
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
        String summaryJson = summarizeTopic(messages);
        TopicMemorySummaryDTO validatedSummary = validateTopicSummary(summaryJson, sourceMessages);
        if (validatedSummary.chunks().isEmpty()) {
            log.info("没有生成有效主题分块，跳过记忆存储，messageCount={}", messages.size());
            return;
        }

        List<UserTopicMemory> topicMemories = assembleTopicMemories(
                validatedSummary, sourceMessages);
        persistTopicMemory(topicMemories);
    }

    private String summarizeTopic(List<MessageDTO> messages) {
        try {
            return topicMemoryAssistant.summarizeTopic(
                    serializeModelInput(toModelInputMessages(messages)));
        } catch (RuntimeException exception) {
            log.error("主题记忆总结模型调用失败，messageCount={}", messages.size(), exception);
            throw new IllegalStateException("主题记忆总结模型调用失败", exception);
        }
    }

    private TopicMemorySummaryDTO validateTopicSummary(
            String summaryJson, Map<String, MessageDTO> sourceMessages) {
        if (summaryJson == null || summaryJson.isBlank()) throw invalidSummary("模型返回内容不能为空");
        try {
            TopicMemorySummaryDTO summary = objectMapper
                    .readerFor(TopicMemorySummaryDTO.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(summaryJson);
            if (summary == null) throw invalidSummary("JSON根节点不能为空");
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
            topicMemories.add(topicMemory);
        }
        return List.copyOf(topicMemories);
    }

    /**
     * 先写入MySQL取得主题记忆ID，再生成向量并写入Milvus。
     *
     * @param topicMemories 待持久化的主题记忆
     */
    private void persistTopicMemory(List<UserTopicMemory> topicMemories) {
        if (topicMemories.isEmpty()) {
            return;
        }

        int affectedRows = userTopicMemoryRepository.insertBatch(topicMemories);
        if (affectedRows != topicMemories.size()) {
            throw new IllegalStateException("MySQL主题记忆写入数量不一致");
        }

        List<TextSegment> segments = topicMemories.stream()
                .map(memory -> TextSegment.from(memory.getTopicSummary()))
                .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        if (embeddings == null || embeddings.size() != topicMemories.size()) {
            throw new IllegalStateException("主题记忆向量生成数量不一致");
        }

        List<UserTopicMemoryVector> vectorMemories = new ArrayList<>(topicMemories.size());
        for (int index = 0; index < topicMemories.size(); index++) {
            UserTopicMemory topicMemory = topicMemories.get(index);
            vectorMemories.add(new UserTopicMemoryVector(
                    topicMemory.getTopicMemoryId(),
                    topicMemory.getWechatUserId(),
                    topicMemory.getTopicSummary(),
                    topicMemory.getTopicOccurredAt().toEpochMilli(),
                    embeddings.get(index).vector()));
        }
        InsertResp response = userTopicMemoryVectorRepository.insertBatch(vectorMemories);
        if (response == null || response.getInsertCnt() != vectorMemories.size()) {
            throw new IllegalStateException("Milvus主题记忆写入数量不一致");
        }
        log.info("主题记忆持久化完成，topicMemoryCount={}", topicMemories.size());
    }

    private TopicMemorySourceContext resolveChunkSourceContext(
            List<String> messageIds, Map<String, MessageDTO> sourceMessages) {
        String wechatUserId = null;
        Instant occurredAt = null;
        for (String messageId : messageIds) {
            MessageDTO message = sourceMessages.get(messageId);
            if (message == null) {
                throw new IllegalStateException("分块包含当前消息批次之外的sourceMessageId");
            }
            if (message.wechatUserId() == null || message.wechatUserId().isBlank()) {
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

    private Map<String, MessageDTO> indexSourceMessages(List<MessageDTO> messages) {
        Map<String, MessageDTO> sourceMessages = new LinkedHashMap<>(messages.size());
        for (MessageDTO message : messages) {
            if (message == null || message.messageId() == null || message.messageId().isBlank()) {
                throw new IllegalArgumentException("主题记忆消息缺少messageId");
            }
            if (sourceMessages.putIfAbsent(message.messageId(), message) != null) {
                throw new IllegalArgumentException(
                        "主题记忆消息包含重复messageId：" + message.messageId());
            }
        }
        return sourceMessages;
    }

    private TopicMemorySummaryDTO normalizeSummary(TopicMemorySummaryDTO s) {
        if (s == null || s.chunks() == null) return s;
        List<TopicMemorySummaryDTO.TopicChunk> nc = s.chunks().stream().map(c -> {
            if (c == null) return null;
            List<String> ids = c.sourceMessageIds() == null ? null :
                    c.sourceMessageIds().stream().map(id -> id == null ? null : id.trim()).toList();
            return new TopicMemorySummaryDTO.TopicChunk(
                    c.topicName() == null ? null : c.topicName().trim(),
                    c.topicSummary() == null ? null : c.topicSummary().trim(),
                    normalizeCategory(c.category()), ids);
        }).toList();
        return new TopicMemorySummaryDTO(nc);
    }

    private String normalizeCategory(String category) {
        return TopicMemoryCategory.fromValue(category).value();
    }

    private void validateSummaryConstraints(TopicMemorySummaryDTO s) {
        Set<ConstraintViolation<TopicMemorySummaryDTO>> vs = validator.validate(s);
        if (vs.isEmpty()) return;
        String d = vs.stream().map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted().reduce((l, r) -> l + "；" + r).orElse("DTO字段校验失败");
        throw invalidSummary(d);
    }

    private void validateSummaryBusinessRules(TopicMemorySummaryDTO s, Map<String, MessageDTO> src) {
        for (int i = 0; i < s.chunks().size(); i++) {
            TopicMemorySummaryDTO.TopicChunk c = s.chunks().get(i);
            String loc = "chunks[" + i + "]";
            validateMeaningfulContent(c.topicSummary(), loc);
            validateSensitiveContent(c.topicName(), c.topicSummary(), loc);
            validateSourceMessageIds(c.sourceMessageIds(), loc, src);
        }
    }

    private void validateMeaningfulContent(String summary, String loc) {
        String n = summary.replaceAll("[\\p{P}\\p{S}\\s]+", "").toLowerCase(Locale.ROOT);
        if (!MEANINGFUL_TEXT_PATTERN.matcher(n).find() || MEANINGLESS_CONTENTS.contains(n))
            throw invalidSummary(loc + ".topicSummary不能只有寒暄或无意义字符");
    }

    private void validateSensitiveContent(String name, String summary, String loc) {
        String c = name + System.lineSeparator() + summary;
        if (SENSITIVE_VALUE_PATTERN.matcher(c).find() || VERIFICATION_CODE_PATTERN.matcher(c).find()
                || PRIVATE_KEY_PATTERN.matcher(c).find())
            throw invalidSummary(loc + "包含密码、Token、验证码或私钥等敏感信息");
    }

    private void validateSourceMessageIds(List<String> ids, String loc, Map<String, MessageDTO> src) {
        Set<String> uniq = new LinkedHashSet<>(); boolean hasUser = false;
        for (String id : ids) {
            if (!uniq.add(id)) throw invalidSummary(loc + ".sourceMessageIds包含重复ID：" + id);
            MessageDTO m = src.get(id);
            if (m == null) throw invalidSummary(loc + ".sourceMessageIds包含当前选中消息之外的ID：" + id);
            if (ROLE_USER.equals(m.role())) hasUser = true;
        }
        if (!hasUser) throw invalidSummary(loc + "至少需要关联一条USER消息");
    }

    private IllegalStateException invalidSummary(String reason) {
        return new IllegalStateException("主题记忆总结结果校验失败：" + reason);
    }
    private IllegalStateException invalidSummary(String reason, Exception cause) {
        return new IllegalStateException("主题记忆总结结果校验失败：" + reason, cause);
    }

    private List<ModelInputMessage> toModelInputMessages(List<MessageDTO> msgs) {
        return msgs.stream().map(m -> new ModelInputMessage(
                m.messageId(), m.role(), m.content(),
                m.createdAt() == null ? null : m.createdAt().toString())).toList();
    }

    private String serializeModelInput(List<ModelInputMessage> msgs) {
        try { return objectMapper.writeValueAsString(msgs); }
        catch (Exception e) {
            log.error("主题记忆模型输入序列化失败", e);
            throw new IllegalStateException("主题记忆模型输入序列化失败", e);
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
