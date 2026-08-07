package com.westart.ai.westart.memory.adapter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.milvus.v2.service.vector.response.SearchResp;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

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

}
