package com.westart.ai.westart.service.impl;

import com.westart.ai.westart.DTO.MessageDTO;
import com.westart.ai.westart.DTO.TopicMemorySummaryDTO;
import com.westart.ai.westart.adapter.TopicMemoryMessageAdapter;
import com.westart.ai.westart.service.TopicMemoryService;
import static com.westart.ai.westart.service.ChatHistoryService.ROLE_USER;
import com.westart.ai.westart.service.ai.TopicMemoryAssistant;
import dev.langchain4j.data.segment.TextSegment;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Slf4j(topic = "TOPIC_MEMORY_FILTER_LOG")
@RequiredArgsConstructor
public class TopicMemoryServiceImpl implements TopicMemoryService {

    private static final String EMPTY_CHUNKS_JSON = "{\"chunks\":[]}";
    private static final Set<String> EXACT_LOW_VALUE_TEXTS = Set.of(
            "你好", "您好", "谢谢", "感谢", "多谢", "哈哈", "哈哈哈",
            "不客气", "好的", "好", "收到", "知道了", "嗯", "哦");
    private static final Set<String> CONTENT_PLACEHOLDERS = Set.of(
            "[图片消息]", "[视频消息]", "[不支持的微信消息]");
    private static final Set<String> MEANINGLESS_CONTENTS = buildMeaninglessContents();
    private static Set<String> buildMeaninglessContents() {
        Set<String> words = new LinkedHashSet<>(EXACT_LOW_VALUE_TEXTS);
        words.addAll(Set.of("嗨", "哈喽", "hello", "hi", "谢谢你", "可以", "嗯嗯", "噢", "再见", "拜拜"));
        return Collections.unmodifiableSet(words);
    }
    private static final Pattern MEANINGFUL_TEXT_PATTERN = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern SENSITIVE_VALUE_PATTERN = Pattern.compile(
            "(?i)(?:password|passwd|token|access[_ -]?token|refresh[_ -]?token|"
                    + "api[_ -]?key|secret(?:[_ -]?key)?|密码|口令)"
                    + "\\s*[:：=]\\s*[a-z0-9+/_@#$%^&*!.~-]{4,}");
    private static final Pattern VERIFICATION_CODE_PATTERN = Pattern.compile(
            "(?i)(?:验证码|verification\\s*code|otp)\\s*[:：=]?\\s*\\d{4,8}");
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
            "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[。！？!?…~～，,.]+$");
    private static final DateTimeFormatter TOPIC_MEMORY_TIME_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "饮食", "工作", "技术", "生活", "健康", "娱乐", "其他");

    private final TopicMemoryAssistant topicMemoryAssistant;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final com.westart.ai.westart.listener.TopicMemoryFilterLogListener filterLog;

    @Override
    public List<String> selectTopicMessageIds(List<TextSegment> segments) {
        if (segments == null) throw new IllegalArgumentException("文本片段列表不能为空");
        if (segments.isEmpty()) return Collections.emptyList();
        filterLog.begin();
        Map<String, ModelInputMessage> batchMessages = indexInputMessages(segments);
        List<TextSegment> filteredSegments = retainKeywordCandidates(segments);
        if (filteredSegments.isEmpty()) { filterLog.end(); return Collections.emptyList(); }
        Map<String, ModelInputMessage> modelInputMessages = selectModelInputMessages(filteredSegments, batchMessages);
        TopicMemoryAssistant.SelectionResult selectionResult;
        try {
            selectionResult = topicMemoryAssistant.selectTopicMessageIds(
                    serializeModelInput(modelInputMessages.values().stream().toList(), "第一阶段"));
        } catch (RuntimeException exception) {
            log.error("第一阶段主题记忆模型调用失败，messageCount={}，filteredCount={}",
                    segments.size(), filteredSegments.size(), exception);
            filterLog.end();
            throw new IllegalStateException("第一阶段主题记忆模型调用失败", exception);
        }
        return validateSelectedMessageIds(selectionResult, modelInputMessages);
    }

    private List<TextSegment> retainKeywordCandidates(List<TextSegment> segments) {
        List<TextSegment> candidates = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            String msgId = requiredMetadata(segment, TopicMemoryMessageAdapter.MESSAGE_ID_METADATA_KEY);
            // 已处理过的消息直接跳过，不进入模型（Redis重试去重）
            if (filterLog.isDuplicate(msgId)) {
                log.info("消息已处理，跳过 messageId={}", msgId);
                continue;
            }
            String role = requiredMetadata(segment, TopicMemoryMessageAdapter.ROLE_METADATA_KEY);
            String rejectionReason = certainlyLowValueReason(segment);
            boolean retained = rejectionReason == null;
            filterLog.filter(msgId, role, segment.text(), retained, retained ? null : rejectionReason);
            if (retained) candidates.add(segment);
        }
        return List.copyOf(candidates);
    }

    private String certainlyLowValueReason(TextSegment segment) {
        if (segment == null) throw new IllegalArgumentException("文本片段不能为空");
        String content = segment.text();
        if (content.isBlank()) return "BLANK_CONTENT";
        if (EXACT_LOW_VALUE_TEXTS.contains(normalizeForComparison(content))) return "EXACT_LOW_VALUE_TEXT";
        if (containsOnlyPlaceholders(content)) return "ONLY_PLACEHOLDER";
        return null;
    }

    private String normalizeForComparison(String content) {
        return TRAILING_PUNCTUATION.matcher(content.strip()).replaceFirst("").stripTrailing();
    }

    private boolean containsOnlyPlaceholders(String content) {
        List<String> lines = content.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        return !lines.isEmpty() && lines.stream().allMatch(CONTENT_PLACEHOLDERS::contains);
    }

    @Override
    public String summarizeTopic(List<MessageDTO> messages, List<String> selectedMessageIds) {
        List<MessageDTO> originalMessages = findOriginalMessages(messages, selectedMessageIds);
        if (originalMessages.isEmpty()) return EMPTY_CHUNKS_JSON;
        try {
            return topicMemoryAssistant.summarizeTopic(
                    serializeModelInput(toModelInputMessages(originalMessages), "第二阶段"));
        } catch (RuntimeException exception) {
            log.error("第二阶段主题记忆模型调用失败，messageCount={}", originalMessages.size(), exception);
            throw new IllegalStateException("第二阶段主题记忆模型调用失败", exception);
        }
    }

    @Override
    public TopicMemorySummaryDTO validateTopicSummary(
            String summaryJson, List<MessageDTO> messages, List<String> selectedMessageIds) {
        if (summaryJson == null || summaryJson.isBlank()) throw invalidSummary("模型返回内容不能为空");
        List<MessageDTO> originalMessages = findOriginalMessages(messages, selectedMessageIds);
        Map<String, MessageDTO> sourceMessages = indexSourceMessages(originalMessages);
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
            for (MessageDTO m : originalMessages) {
                List<String> topics = normalizedSummary.chunks().stream()
                        .filter(c -> c.sourceMessageIds().contains(m.messageId()))
                        .map(TopicMemorySummaryDTO.TopicChunk::topicName).toList();
                filterLog.stage2(m.messageId(), topics);
            }
            filterLog.flush();
            filterLog.topics(normalizedSummary.chunks());
            filterLog.flushStage2();
            // 标记已处理，Redis重试时跳过
            for (MessageDTO m : originalMessages) filterLog.markSeen(m.messageId());
            filterLog.end();
            return normalizedSummary;
        } catch (IllegalStateException exception) {
            log.warn("第二阶段主题记忆结果校验失败，selectedMessageCount={}，reason={}",
                    originalMessages.size(), exception.getMessage());
            filterLog.end(); throw exception;
        } catch (Exception exception) {
            log.warn("第二阶段主题记忆结果不是合法JSON，selectedMessageCount={}", originalMessages.size(), exception);
            filterLog.end(); throw invalidSummary("模型返回内容不是合法JSON", exception);
        }
    }

    @Override
    public String assembleTopicMemoryJson(
            TopicMemorySummaryDTO validatedSummary, List<MessageDTO> messages, List<String> selectedMessageIds) {
        if (validatedSummary == null) throw new IllegalArgumentException("已校验主题记忆DTO不能为空");
        List<MessageDTO> originalMessages = findOriginalMessages(messages, selectedMessageIds);
        Map<String, MessageDTO> sourceMessages = indexSourceMessages(originalMessages);
        try {
            ObjectNode root = objectMapper.createObjectNode();
            var arr = root.putArray("chunks");
            for (TopicMemorySummaryDTO.TopicChunk chunk : validatedSummary.chunks()) {
                TopicMemorySourceContext ctx = resolveChunkSourceContext(chunk.sourceMessageIds(), sourceMessages);
                ObjectNode obj = arr.addObject();
                obj.put("wechatUserId", ctx.wechatUserId());
                obj.put("topicName", chunk.topicName());
                obj.put("topicSummary", chunk.topicSummary());
                obj.put("category", chunk.category());
                obj.put("topicOccurredAt", TOPIC_MEMORY_TIME_FORMATTER.format(ctx.occurredAt()));
            }
            return objectMapper.writeValueAsString(root);
        } catch (RuntimeException exception) {
            log.warn("最终主题记忆JSON组装失败，selectedMessageCount={}", originalMessages.size(), exception);
            throw new IllegalStateException("最终主题记忆JSON组装失败", exception);
        } catch (Exception exception) {
            log.warn("已校验主题记忆JSON序列化失败，selectedMessageCount={}", originalMessages.size(), exception);
            throw new IllegalStateException("已校验主题记忆JSON序列化失败", exception);
        }
    }

    private TopicMemorySourceContext resolveChunkSourceContext(List<String> ids, Map<String, MessageDTO> src) {
        if (ids == null || ids.isEmpty()) throw new IllegalStateException("分块缺少sourceMessageIds");
        List<MessageDTO> ms = ids.stream().map(src::get).toList();
        if (ms.stream().anyMatch(m -> m == null))
            throw new IllegalStateException("分块包含当前选中消息之外的sourceMessageId");
        return buildSourceContext(ms);
    }

    private Map<String, MessageDTO> indexSourceMessages(List<MessageDTO> src) {
        Map<String, MessageDTO> m = new LinkedHashMap<>(); src.forEach(s -> m.put(s.messageId(), s)); return m;
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

    private String normalizeCategory(String cat) {
        if (cat == null || cat.isBlank()) return "其他";
        String t = cat.trim(); return VALID_CATEGORIES.contains(t) ? t : "其他";
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
        return new IllegalStateException("第二阶段主题记忆结果校验失败：" + reason);
    }
    private IllegalStateException invalidSummary(String reason, Exception cause) {
        return new IllegalStateException("第二阶段主题记忆结果校验失败：" + reason, cause);
    }

    private TopicMemorySourceContext buildSourceContext(List<MessageDTO> msgs) {
        if (msgs == null || msgs.isEmpty()) throw new IllegalArgumentException("选中原始消息不能为空");
        String wuid = null; Set<String> ids = new LinkedHashSet<>(); Instant occurred = null;
        for (MessageDTO m : msgs) {
            if (m == null) throw new IllegalArgumentException("选中原始消息不能包含空值");
            if (m.messageId() == null || m.messageId().isBlank())
                throw new IllegalArgumentException("选中原始消息缺少messageId");
            if (!ids.add(m.messageId()))
                throw new IllegalArgumentException("选中原始消息包含重复messageId：" + m.messageId());
            if (m.wechatUserId() == null || m.wechatUserId().isBlank())
                throw new IllegalArgumentException("选中原始消息缺少wechatUserId");
            if (wuid == null) wuid = m.wechatUserId();
            else if (!wuid.equals(m.wechatUserId()))
                throw new IllegalArgumentException("选中原始消息不能属于不同微信用户");
            if (m.createdAt() == null) throw new IllegalArgumentException("选中原始消息缺少createdAt");
            if (occurred == null || m.createdAt().isAfter(occurred)) occurred = m.createdAt();
        }
        return new TopicMemorySourceContext(wuid, ids.stream().toList(), occurred);
    }

    private List<MessageDTO> findOriginalMessages(List<MessageDTO> msgs, List<String> ids) {
        if (msgs == null) throw new IllegalArgumentException("原始消息列表不能为空");
        if (ids == null) throw new IllegalArgumentException("选中消息ID列表不能为空");
        if (msgs.isEmpty() || ids.isEmpty()) return Collections.emptyList();
        Set<String> set = new LinkedHashSet<>(ids);
        return msgs.stream().filter(m -> m != null && set.contains(m.messageId())).toList();
    }

    private List<ModelInputMessage> toModelInputMessages(List<MessageDTO> msgs) {
        return msgs.stream().map(m -> new ModelInputMessage(
                m.messageId(), m.role(), m.content(),
                m.createdAt() == null ? null : m.createdAt().toString())).toList();
    }

    private Map<String, ModelInputMessage> indexInputMessages(List<TextSegment> segs) {
        Map<String, ModelInputMessage> map = new LinkedHashMap<>(); String batchWuid = null;
        for (TextSegment seg : segs) {
            if (seg == null) throw new IllegalArgumentException("文本片段不能为空");
            String mid = requiredMetadata(seg, TopicMemoryMessageAdapter.MESSAGE_ID_METADATA_KEY);
            String wuid = requiredMetadata(seg, TopicMemoryMessageAdapter.WECHAT_USER_ID_METADATA_KEY);
            if (batchWuid == null) batchWuid = wuid;
            else if (!batchWuid.equals(wuid))
                throw new IllegalArgumentException("文本片段批次不能混入不同微信用户的消息");
            ModelInputMessage m = new ModelInputMessage(mid,
                    requiredMetadata(seg, TopicMemoryMessageAdapter.ROLE_METADATA_KEY),
                    seg.text(), requiredMetadata(seg, TopicMemoryMessageAdapter.CREATED_AT_METADATA_KEY));
            if (map.putIfAbsent(mid, m) != null)
                throw new IllegalArgumentException("文本片段包含重复messageId：" + mid);
        }
        return map;
    }

    private Map<String, ModelInputMessage> selectModelInputMessages(
            List<TextSegment> filtered, Map<String, ModelInputMessage> batch) {
        Map<String, ModelInputMessage> map = new LinkedHashMap<>();
        for (TextSegment seg : filtered) {
            String mid = requiredMetadata(seg, TopicMemoryMessageAdapter.MESSAGE_ID_METADATA_KEY);
            ModelInputMessage m = batch.get(mid);
            if (m == null) throw new IllegalStateException("本地过滤结果包含批次外消息：" + mid);
            map.put(mid, m);
        }
        return map;
    }

    private List<String> validateSelectedMessageIds(
            TopicMemoryAssistant.SelectionResult result, Map<String, ModelInputMessage> modelInputMessages) {
        List<String> selected = result == null ? Collections.emptyList() : result.selectedMessageIds();
        if (selected == null) selected = Collections.emptyList();
        Set<String> valid = new LinkedHashSet<>();
        for (String id : selected) if (id != null && modelInputMessages.containsKey(id)) valid.add(id);
        boolean hasSelectedUser = valid.stream().map(modelInputMessages::get)
                .anyMatch(m -> ROLE_USER.equals(m.role()));
        if (!hasSelectedUser) valid.clear();
        boolean noUser = !hasSelectedUser;
        for (ModelInputMessage m : modelInputMessages.values())
            filterLog.stage1(m.messageId(), valid.contains(m.messageId()), noUser);
        return modelInputMessages.keySet().stream().filter(valid::contains).toList();
    }

    private String requiredMetadata(TextSegment segment, String key) {
        String v = segment.metadata().getString(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("文本片段缺少必填元数据：" + key);
        return v;
    }

    private String serializeModelInput(List<ModelInputMessage> msgs, String stage) {
        try { return objectMapper.writeValueAsString(msgs); }
        catch (Exception e) {
            log.error("{}主题记忆模型输入序列化失败", stage, e);
            throw new IllegalStateException(stage + "主题记忆模型输入序列化失败", e);
        }
    }

    private record ModelInputMessage(String messageId, String role, String content, String createdAt) {}
    private record TopicMemorySourceContext(String wechatUserId, List<String> sourceMessageIds, Instant occurredAt) {
        TopicMemorySourceContext { sourceMessageIds = List.copyOf(sourceMessageIds); }
    }
}
