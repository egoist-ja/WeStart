package com.westart.ai.westart.memory.adapter;

import com.westart.ai.westart.memory.entity.UserTopicMemoryVector;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.milvus.v2.service.vector.response.SearchResp;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Retrieval Augmentor与主题记忆存储模型之间的适配器。
 *
 * 统一维护{@link TextSegment}与Milvus主题记忆DTO之间的字段映射，
 * 避免框架模型进入仓储层。
 */
@Component
public class RetrievalAugmentorAdapter {

    /** TextSegment元数据中的主题记忆主键。 */
    private static final String TOPIC_MEMORY_ID_FIELD = "topic_memory_id";

    /** TextSegment元数据中的微信用户ID。 */
    private static final String WECHAT_USER_ID_FIELD = "wechat_user_id";

    /** Milvus实体中的主题摘要字段。 */
    private static final String TOPIC_SUMMARY_FIELD = "topic_summary";

    /** TextSegment元数据及Milvus实体中的主题发生时间。 */
    private static final String TOPIC_OCCURRED_AT_FIELD = "topic_occurred_at";

    /**
     * 将文本片段及其向量转换为Milvus主题记忆DTO。
     *
     * @param embeddings 主题摘要向量列表
     * @param segments 主题摘要文本片段列表
     * @return 可交给主题记忆仓储写入的DTO列表
     * @throws IllegalArgumentException 参数为空、数量不一致或缺少必需字段时抛出
     */
    public List<UserTopicMemoryVector> toMilvusMemories(
            List<Embedding> embeddings,
            List<TextSegment> segments) {
        validateBatchArguments(embeddings, segments);

        List<UserTopicMemoryVector> memories = new ArrayList<>(segments.size());
        for (int index = 0; index < segments.size(); index++) {
            memories.add(toMilvusMemory(embeddings.get(index), segments.get(index), index));
        }
        return memories;
    }

    /**
     * 将Milvus搜索结果转换为Retrieval Augmentor可使用的文本片段匹配结果。
     *
     * @param result Milvus单条搜索结果
     * @return 文本片段匹配结果
     * @throws IllegalStateException 搜索结果缺少必需字段时抛出
     */
    public EmbeddingMatch<TextSegment> toEmbeddingMatch(SearchResp.SearchResult result) {
        if (result == null || result.getEntity() == null) {
            throw new IllegalStateException("用户主题记忆搜索结果不能为空");
        }

        String topicMemoryId = String.valueOf(result.getId());
        Map<String, Object> fields = result.getEntity();
        Metadata metadata = new Metadata()
                .put(TOPIC_MEMORY_ID_FIELD, parseTopicMemoryId(topicMemoryId))
                .put(WECHAT_USER_ID_FIELD, requiredField(fields, WECHAT_USER_ID_FIELD));
        Long topicOccurredAt = optionalLongField(fields, TOPIC_OCCURRED_AT_FIELD);
        if (topicOccurredAt != null) {
            metadata.put(TOPIC_OCCURRED_AT_FIELD, topicOccurredAt);
        }

        TextSegment segment = TextSegment.from(
                requiredField(fields, TOPIC_SUMMARY_FIELD),
                metadata);
        return new EmbeddingMatch<>(
                result.getScore().doubleValue(),
                topicMemoryId,
                null,
                segment);
    }

    /**
     * 将单组向量和文本片段转换为Milvus主题记忆DTO。
     *
     * 主题摘要取自{@link TextSegment#text()}，业务标识及发生时间取自元数据。
     *
     * @param embedding 主题摘要向量
     * @param segment 主题摘要文本片段
     * @param index 当前元素在批次中的位置，用于定位无效数据
     * @return Milvus主题记忆DTO
     * @throws IllegalArgumentException 向量、文本片段或必需元数据无效时抛出
     */
    private static UserTopicMemoryVector toMilvusMemory(
            Embedding embedding,
            TextSegment segment,
            int index) {
        if (embedding == null || segment == null) {
            throw new IllegalArgumentException("向量和文本片段不能包含空元素，位置：" + index);
        }
        float[] vector = embedding.vector();
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("主题记忆向量不能为空，位置：" + index);
        }

        Metadata metadata = segment.metadata();
        Long topicMemoryId = metadata.getLong(TOPIC_MEMORY_ID_FIELD);
        String wechatUserId = metadata.getString(WECHAT_USER_ID_FIELD);
        if (topicMemoryId == null) {
            throw new IllegalArgumentException("文本片段缺少主题记忆ID，位置：" + index);
        }
        if (StringUtils.isBlank(wechatUserId)) {
            throw new IllegalArgumentException("文本片段缺少微信用户ID，位置：" + index);
        }
        if (StringUtils.isBlank(segment.text())) {
            throw new IllegalArgumentException("主题摘要不能为空，位置：" + index);
        }
        return new UserTopicMemoryVector(
                topicMemoryId,
                wechatUserId,
                segment.text(),
                metadata.getLong(TOPIC_OCCURRED_AT_FIELD),
                vector);
    }

    /**
     * 校验批量转换参数。
     *
     * @param embeddings 主题摘要向量列表
     * @param segments 主题摘要文本片段列表
     * @throws IllegalArgumentException 列表为空引用或元素数量不一致时抛出
     */
    private static void validateBatchArguments(
            List<Embedding> embeddings,
            List<TextSegment> segments) {
        if (embeddings == null || segments == null) {
            throw new IllegalArgumentException("向量列表和文本片段列表不能为空");
        }
        if (embeddings.size() != segments.size()) {
            throw new IllegalArgumentException("向量数量与文本片段数量不一致");
        }
    }

    /**
     * 将Milvus返回的主题记忆主键转换为业务主键类型。
     *
     * @param topicMemoryId Milvus主题记忆主键
     * @return Long类型的主题记忆主键
     * @throws IllegalStateException 主键无法转换为Long时抛出
     */
    private static Long parseTopicMemoryId(String topicMemoryId) {
        try {
            return Long.valueOf(topicMemoryId);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("用户主题记忆搜索结果主键无效", exception);
        }
    }

    /**
     * 读取Milvus搜索结果中的必需字符串字段。
     *
     * @param fields Milvus实体字段
     * @param fieldName 字段名称
     * @return 字段字符串值
     * @throws IllegalStateException 字段不存在或内容为空时抛出
     */
    private static String requiredField(Map<String, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);
        String fieldValue = value == null ? null : String.valueOf(value);
        if (StringUtils.isBlank(fieldValue)) {
            throw new IllegalStateException("搜索结果缺少字段：" + fieldName);
        }
        return fieldValue;
    }

    /**
     * 读取Milvus搜索结果中的可选Long字段。
     *
     * @param fields Milvus实体字段
     * @param fieldName 字段名称
     * @return Long类型的字段值；字段不存在或不是数值时返回null
     */
    private static Long optionalLongField(Map<String, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
