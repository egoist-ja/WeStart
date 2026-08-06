package com.westart.ai.westart.repository.impl;

import com.westart.ai.westart.entity.UserTopicMemory;
import com.westart.ai.westart.entity.UserTopicMemoryEntity;
import com.westart.ai.westart.mapper.impl.UserTopicMemoryMapperImpl;
import com.westart.ai.westart.repository.TopicMemoryRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
@Slf4j
@RequiredArgsConstructor
public class TopicMemoryRepositoryImpl implements TopicMemoryRepository {

    private static final Set<String> ASSEMBLED_ROOT_FIELDS = Set.of("chunks");
    private static final Set<String> ASSEMBLED_CHUNK_FIELDS = Set.of(
            "wechatUserId", "topicName", "topicSummary", "category", "topicOccurredAt");

    private final UserTopicMemoryMapperImpl userTopicMemoryMapper;
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<UserTopicMemory> topicMemoryEmbeddingStore;

    @Override
    public List<UserTopicMemoryEntity> saveToMysql(String assembledJson) {
        try {
            List<UserTopicMemoryEntity> entities = parseMysqlTopicMemories(assembledJson);
            if (entities.isEmpty()) { log.info("主题记忆MySQL写入跳过，chunkCount=0"); return List.of(); }
            int affectedRows = userTopicMemoryMapper.insertBatch(entities);
            log.info("主题记忆MySQL写入完成，chunkCount={}，affectedRows={}", entities.size(), affectedRows);
            return List.copyOf(entities);
        } catch (RuntimeException exception) {
            log.error("主题记忆MySQL写入失败", exception);
            throw new IllegalStateException("主题记忆MySQL写入失败", exception);
        }
    }

    @Override
    public void saveToMilvus(List<UserTopicMemoryEntity> persistedMemories) {
        try {
            List<UserTopicMemory> topicMemories = toMilvusTopicMemories(persistedMemories);
            if (topicMemories.isEmpty()) { log.info("主题记忆Milvus写入跳过，chunkCount=0"); return; }
            List<TextSegment> searchableSegments = topicMemories.stream()
                    .map(memory -> TextSegment.from(memory.topicSummary())).toList();
            List<Embedding> embeddings = embeddingModel.embedAll(searchableSegments).content();
            topicMemoryEmbeddingStore.addAll(embeddings, topicMemories);
            log.info("主题记忆Milvus写入完成，chunkCount={}", topicMemories.size());
        } catch (RuntimeException exception) {
            log.error("主题记忆Milvus写入失败", exception);
            throw new IllegalStateException("主题记忆Milvus写入失败", exception);
        }
    }

    private List<UserTopicMemoryEntity> parseMysqlTopicMemories(String assembledJson) {
        JsonNode chunks = parseAssembledChunks(assembledJson);
        List<UserTopicMemoryEntity> entities = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            JsonNode chunk = chunks.get(i);
            String loc = "chunks[" + i + "]";
            UserTopicMemoryEntity e = new UserTopicMemoryEntity();
            e.setWechatUserId(requiredAssembledText(chunk, "wechatUserId", loc));
            e.setTopicName(requiredAssembledText(chunk, "topicName", loc));
            e.setTopicSummary(requiredAssembledText(chunk, "topicSummary", loc));
            e.setCategory(requiredAssembledText(chunk, "category", loc));
            e.setTopicOccurredAt(parseTopicOccurredAt(chunk, loc));
            entities.add(e);
        }
        return entities;
    }

    private List<UserTopicMemory> toMilvusTopicMemories(List<UserTopicMemoryEntity> persistedMemories) {
        if (persistedMemories == null) throw new IllegalArgumentException("MySQL主题记忆列表不能为空");
        List<UserTopicMemory> list = new ArrayList<>(persistedMemories.size());
        for (UserTopicMemoryEntity p : persistedMemories) {
            if (p == null || p.getTopicMemoryId() == null)
                throw new IllegalArgumentException("MySQL主题记忆缺少topicMemoryId");
            list.add(new UserTopicMemory(p.getTopicMemoryId(), p.getWechatUserId(),
                    p.getTopicSummary(), toUnixMillis(p.getTopicOccurredAt()), null));
        }
        return list;
    }

    private JsonNode parseAssembledChunks(String assembledJson) {
        if (assembledJson == null || assembledJson.isBlank())
            throw new IllegalArgumentException("最终主题记忆JSON不能为空");
        try {
            JsonNode root = objectMapper.reader(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(assembledJson);
            validateAssembledFields(root, ASSEMBLED_ROOT_FIELDS, "JSON根节点");
            JsonNode chunks = root.get("chunks");
            if (chunks == null || !chunks.isArray())
                throw new IllegalArgumentException("最终主题记忆JSON的chunks必须是数组");
            for (int i = 0; i < chunks.size(); i++)
                validateAssembledFields(chunks.get(i), ASSEMBLED_CHUNK_FIELDS, "chunks[" + i + "]");
            return chunks;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("最终主题记忆JSON不是合法JSON", e); }
    }

    private Instant parseTopicOccurredAt(JsonNode chunk, String loc) {
        String s = requiredAssembledText(chunk, "topicOccurredAt", loc);
        try { return Instant.parse(s); }
        catch (DateTimeParseException e) {
            throw new IllegalArgumentException(loc + ".topicOccurredAt不是合法ISO 8601时间", e);
        }
    }

    private void validateAssembledFields(JsonNode node, Set<String> expected, String loc) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(loc + "必须是对象");
    }

    private String requiredAssembledText(JsonNode node, String field, String loc) {
        JsonNode f = node.get(field);
        if (f == null || !f.isTextual() || f.asText().isBlank())
            throw new IllegalArgumentException(loc + "." + field + "必须是非空字符串");
        return f.asText().trim();
    }

    private Long toUnixMillis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
