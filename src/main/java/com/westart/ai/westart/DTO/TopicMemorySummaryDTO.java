package com.westart.ai.westart.DTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 第二阶段主题记忆模型JSON的结构化载体。
 *
 * <p>只保存模型能够生成的语义分块字段；主题记忆ID、微信用户ID和时间由后端补充。</p>
 *
 * @param chunks 按语义划分的主题块；空列表表示没有可保存的主题
 */
public record TopicMemorySummaryDTO(
        @NotNull
        List<@Valid TopicChunk> chunks) {

    /**
     * 一条语义主题块。
     *
     * @param topicName 简短明确的主题名称
     * @param topicSummary 可独立理解、用于检索的主题摘要
     * @param category 主题分类，如饮食/工作/技术/生活
     * @param sourceMessageIds 该主题块引用的原始消息ID
     */
    public record TopicChunk(
            @NotBlank
            @Size(max = 256)
            String topicName,

            @NotBlank
            @Size(max = 4096)
            String topicSummary,

            @NotBlank
            @Size(max = 32)
            String category,

            @NotEmpty
            List<@NotBlank String> sourceMessageIds) {
    }
}
