package com.westart.ai.westart.entity;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

/**
 * 用户主题记忆向量记录，对应Milvus的user_topic_memory_vector Collection。
 *
 * <p>稀疏向量由Milvus BM25 Function根据topic_summary自动生成，
 * 不需要由业务侧写入。</p>
 *
 * @param topicMemoryId MySQL主题记忆主键
 * @param wechatUserId 微信用户唯一标识，用于检索隔离
 * @param topicSummary 用于语义和BM25检索的主题摘要
 * @param topicOccurredAt 主题发生时间（Unix毫秒），用于Milvus内按时间过滤
 * @param topicSummaryDenseVector 主题摘要对应的稠密向量
 */
public record UserTopicMemory(
        @SerializedName("topic_memory_id") Long topicMemoryId,
        @SerializedName("wechat_user_id") String wechatUserId,
        @SerializedName("topic_summary") String topicSummary,
        @SerializedName("topic_occurred_at") Long topicOccurredAt,
        @SerializedName("topic_summary_dense_vector") float[] topicSummaryDenseVector) {

    public UserTopicMemory {
        Objects.requireNonNull(topicMemoryId, "主题记忆ID不能为null");
        Objects.requireNonNull(wechatUserId, "微信用户ID不能为null");
        Objects.requireNonNull(topicSummary, "主题摘要不能为null");
        topicSummaryDenseVector = topicSummaryDenseVector == null
                ? null
                : topicSummaryDenseVector.clone();
    }
}
