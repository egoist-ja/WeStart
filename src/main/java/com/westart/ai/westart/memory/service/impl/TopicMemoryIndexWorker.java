package com.westart.ai.westart.memory.service.impl;

import com.westart.ai.westart.memory.entity.UserTopicMemory;
import com.westart.ai.westart.memory.entity.UserTopicMemoryVector;
import com.westart.ai.westart.memory.repository.UserTopicMemoryRepository;
import com.westart.ai.westart.memory.repository.UserTopicMemoryVectorRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.milvus.v2.service.vector.response.UpsertResp;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 主题记忆异步索引任务。
 *
 * 定期将MySQL中的PENDING主题生成向量并Upsert到Milvus，成功后标记为DONE。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicMemoryIndexWorker {

    /** 用户主题记忆MySQL仓储。 */
    private final UserTopicMemoryRepository userTopicMemoryRepository;

    /** 用户主题记忆Milvus仓储。 */
    private final UserTopicMemoryVectorRepository userTopicMemoryVectorRepository;

    /** 主题摘要向量模型。 */
    private final EmbeddingModel embeddingModel;

    /** 单轮索引任务处理的最大主题数量。 */
    @Value("${westart.memory.topic-index-batch-size}")
    private int batchSize;

    /** 相邻两轮索引任务的等待时间。 */
    @Value("${westart.memory.topic-index-poll-delay}")
    private Duration pollDelay;

    /**
     * 校验异步索引配置，避免无效配置进入运行阶段。
     */
    @PostConstruct
    public void validateConfiguration() {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("主题索引批量大小必须大于0");
        }
        if (pollDelay == null || pollDelay.isZero() || pollDelay.isNegative()) {
            throw new IllegalArgumentException("主题索引轮询间隔必须大于0");
        }
    }

    /**
     * 执行一轮主题索引任务。
     *
     * 任一步失败都不更新MySQL状态，下一轮继续处理PENDING主题。
     */
    @Scheduled(fixedDelayString = "${westart.memory.topic-index-poll-delay}")
    public void indexPendingTopics() {
        try {
            List<UserTopicMemory> pendingTopics =
                    userTopicMemoryRepository.selectPendingIndex(batchSize);
            if (pendingTopics.isEmpty()) {
                return;
            }

            List<Embedding> embeddings = generateEmbeddings(pendingTopics);
            List<UserTopicMemoryVector> topicVectors =
                    assembleTopicVectors(pendingTopics, embeddings);
            UpsertResp response = userTopicMemoryVectorRepository.upsertBatch(topicVectors);
            validateUpsertResponse(response, topicVectors.size());

            List<Long> topicMemoryIds = pendingTopics.stream()
                    .map(UserTopicMemory::getTopicMemoryId)
                    .toList();
            userTopicMemoryRepository.markIndexed(topicMemoryIds);
            log.info("主题记忆异步索引完成，topicMemoryCount={}", topicMemoryIds.size());
        } catch (RuntimeException exception) {
            log.error("主题记忆异步索引失败，PENDING状态保持不变", exception);
        }
    }

    /**
     * 校验待索引主题并批量生成稠密向量。
     *
     * @param pendingTopics 待索引主题
     * @return 与主题顺序一致的稠密向量
     */
    private List<Embedding> generateEmbeddings(List<UserTopicMemory> pendingTopics) {
        List<TextSegment> segments = pendingTopics.stream()
                .map(topic -> {
                    validatePendingTopic(topic);
                    return TextSegment.from(topic.getTopicSummary());
                })
                .toList();
        Response<List<Embedding>> response = embeddingModel.embedAll(segments);
        List<Embedding> embeddings = response == null ? null : response.content();
        if (embeddings == null || embeddings.size() != pendingTopics.size()) {
            throw new IllegalStateException("主题记忆向量生成数量不一致");
        }
        return embeddings;
    }

    /**
     * 将待索引主题和稠密向量组装为Milvus存储对象。
     *
     * @param pendingTopics 待索引主题
     * @param embeddings 与主题顺序一致的稠密向量
     * @return Milvus主题记忆向量
     */
    private List<UserTopicMemoryVector> assembleTopicVectors(
            List<UserTopicMemory> pendingTopics,
            List<Embedding> embeddings) {
        List<UserTopicMemoryVector> topicVectors =
                new ArrayList<>(pendingTopics.size());
        for (int index = 0; index < pendingTopics.size(); index++) {
            UserTopicMemory topic = pendingTopics.get(index);
            Embedding embedding = embeddings.get(index);
            if (embedding == null
                    || embedding.vector() == null
                    || embedding.vector().length == 0) {
                throw new IllegalStateException("主题记忆向量不能为空，位置：" + index);
            }
            topicVectors.add(new UserTopicMemoryVector(
                    topic.getTopicMemoryId(),
                    topic.getWechatUserId(),
                    topic.getTopicSummary(),
                    topic.getTopicOccurredAt().toEpochMilli(),
                    embedding.vector()));
        }
        return List.copyOf(topicVectors);
    }

    /**
     * 校验待索引主题包含Milvus写入需要的业务字段。
     *
     * @param topic 待索引主题
     */
    private void validatePendingTopic(UserTopicMemory topic) {
        if (topic == null) {
            throw new IllegalStateException("待索引主题列表不能包含空元素");
        }
        if (topic.getTopicMemoryId() == null) {
            throw new IllegalStateException("待索引主题缺少主题记忆ID");
        }
        if (StringUtils.isBlank(topic.getWechatUserId())) {
            throw new IllegalStateException("待索引主题缺少微信用户ID");
        }
        if (StringUtils.isBlank(topic.getTopicSummary())) {
            throw new IllegalStateException("待索引主题缺少主题摘要");
        }
        if (topic.getTopicOccurredAt() == null) {
            throw new IllegalStateException("待索引主题缺少发生时间");
        }
    }

    /**
     * 校验Milvus Upsert结果数量。
     *
     * @param response Milvus Upsert结果
     * @param expectedCount 期望写入数量
     */
    private void validateUpsertResponse(UpsertResp response, int expectedCount) {
        if (response == null || response.getUpsertCnt() != expectedCount) {
            throw new IllegalStateException("Milvus主题记忆Upsert数量不一致");
        }
    }
}
