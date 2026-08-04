package com.westart.ai.westart.entity;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

/**
 * 用户最近聊天主题记忆，对应Milvus的user_topic_memory Collection。
 *
 * <p>sparse_vector由Milvus BM25 Function根据searchable_content自动生成，
 * 不需要由业务侧写入。</p>
 *
 * @param memoryId 主题记忆唯一标识
 * @param wechatUserId 微信用户唯一标识，用于检索隔离
 * @param topic 简短的聊天主题名称
 * @param searchableContent 用于语义和BM25检索的主题摘要
 * @param occurredAt 主题发生时间，使用带时区的ISO 8601格式
 * @param expiresAt 主题过期时间，为null时表示长期有效
 * @param denseVector 主题摘要对应的稠密向量
 */
public record UserTopicMemory(
        @SerializedName("memory_id") String memoryId,
        @SerializedName("wechat_user_id") String wechatUserId,
        String topic,
        @SerializedName("searchable_content") String searchableContent,
        @SerializedName("occurred_at") String occurredAt,
        @SerializedName("expires_at") String expiresAt,
        @SerializedName("searchable_content_dense_vector") float[] denseVector) {

    public UserTopicMemory {
        Objects.requireNonNull(memoryId, "主题记忆ID不能为null");
        Objects.requireNonNull(wechatUserId, "微信用户ID不能为null");
        Objects.requireNonNull(topic, "聊天主题不能为null");
        Objects.requireNonNull(searchableContent, "主题检索内容不能为null");
        Objects.requireNonNull(occurredAt, "主题发生时间不能为null");
        denseVector = denseVector == null ? null : denseVector.clone();
    }
}
