package com.westart.ai.westart.memory.infra;

import com.westart.ai.westart.memory.adapter.RetrievalAugmentorAdapter;
import com.westart.ai.westart.memory.dto.ChatMemorySearchRequest;
import com.westart.ai.westart.memory.entity.UserTopicMemoryVector;
import com.westart.ai.westart.memory.repository.UserTopicMemoryVectorRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 用户主题记忆向量存储适配器。
 *
 * 负责实现LangChain4j向量存储协议，不承载记忆提取、合并和召回策略。
 */
@Component
@RequiredArgsConstructor
public class UserTopicEmbeddingStore implements EmbeddingStore<TextSegment> {

    /** LangChain4j过滤条件及Milvus过滤表达式使用的微信用户ID字段。 */
    private static final String WECHAT_USER_ID_FIELD = "wechat_user_id";

    /** Milvus主题发生时间字段。 */
    private static final String TOPIC_OCCURRED_AT_FIELD = "topic_occurred_at";

    /** 主题记忆检索时间窗口，仅召回最近十天内发生的主题。 */
    private static final Duration TOPIC_MEMORY_SEARCH_WINDOW = Duration.ofDays(10);

    /** 用户主题记忆Milvus仓储。 */
    private final UserTopicMemoryVectorRepository userTopicMemoryVectorRepository;

    /** LangChain4j模型与主题记忆仓储DTO的转换适配器。 */
    private final RetrievalAugmentorAdapter retrievalAugmentorAdapter;

    /**
     * 添加未关联主题记忆的单个向量。
     *
     * 当前主题记忆必须携带完整业务数据，因此不支持该写入方式。
     *
     * @param embedding 稠密向量
     * @return 不会正常返回
     * @throws UnsupportedOperationException 未提供主题记忆时抛出
     */
    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException("插入主题记忆向量时必须提供主题记忆");
    }

    /**
     * 使用指定主键添加未关联主题记忆的单个向量。
     *
     * 当前主题记忆必须携带完整业务数据，因此不支持该写入方式。
     *
     * @param id 主题记忆主键
     * @param embedding 稠密向量
     * @throws UnsupportedOperationException 未提供主题记忆时抛出
     */
    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException("插入主题记忆向量时必须提供主题记忆");
    }

    /**
     * 添加单条用户主题记忆及其稠密向量。
     *
     * 当前适配器仅开放批量写入链路，因此不支持该写入方式。
     *
     * @param embedding 稠密向量
     * @param segment 主题记忆文本片段
     * @return 不会正常返回
     * @throws UnsupportedOperationException 当前仅开放批量写入链路时抛出
     */
    @Override
    public String add(Embedding embedding, TextSegment segment) {
        throw new UnsupportedOperationException("主题记忆仅支持批量写入");
    }

    /**
     * 批量添加未关联主题记忆的向量。
     *
     * @param embeddings 稠密向量列表
     * @return 不直接返回结果
     * @throws UnsupportedOperationException 向量未关联主题记忆时抛出
     */
    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException("批量插入主题记忆向量时必须提供主题记忆");
    }

    /**
     * 批量插入用户主题记忆及其稠密向量。
     *
     * @param embeddings 稠密向量列表
     * @param segments 主题记忆文本片段列表
     * @return 成功插入的主题记忆主键列表
     * @throws IllegalArgumentException 参数为空、数量不一致或包含无效元素时抛出
     * @throws IllegalStateException Milvus插入结果为空或数量不一致时抛出
     */
    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        List<UserTopicMemoryVector> memoriesToInsert =
                retrievalAugmentorAdapter.toMilvusMemories(embeddings, segments);
        if (memoriesToInsert.isEmpty()) {
            return List.of();
        }

        InsertResp response = userTopicMemoryVectorRepository.insertBatch(memoriesToInsert);
        validateInsertResponse(response, memoriesToInsert.size());
        return response.getPrimaryKeys().stream()
                .map(String::valueOf)
                .toList();
    }

    /**
     * 将LangChain4j搜索请求转换为仓储请求，并转换Milvus搜索结果。
     *
     * @param request LangChain4j搜索请求
     * @return 用户主题记忆搜索结果
     * @throws IllegalArgumentException 搜索请求不完整或缺少用户过滤条件时抛出
     * @throws IllegalStateException 仓储未返回有效搜索结果时抛出
     */
    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        ChatMemorySearchRequest searchRequest = toSearchRequest(request);
        SearchResp response = userTopicMemoryVectorRepository.search(searchRequest);
        if (response == null || response.getSearchResults() == null) {
            throw new IllegalStateException("搜索用户主题记忆未返回结果");
        }

        List<EmbeddingMatch<TextSegment>> matches = response.getSearchResults().stream()
                .flatMap(List::stream)
                .filter(result -> result.getScore() >= request.minScore())
                .map(retrievalAugmentorAdapter::toEmbeddingMatch)
                .toList();
        return new EmbeddingSearchResult<>(matches);
    }

    /**
     * 校验Milvus批量插入结果及返回主键数量。
     *
     * @param response Milvus批量插入结果
     * @param expectedCount 期望插入数量
     * @throws IllegalStateException 响应为空、插入数量或主键数量不一致时抛出
     */
    private static void validateInsertResponse(InsertResp response, int expectedCount) {
        if (response == null) {
            throw new IllegalStateException("批量插入用户主题记忆未返回结果");
        }
        if (response.getInsertCnt() != expectedCount) {
            throw new IllegalStateException(
                    "批量插入用户主题记忆数量不一致，期望："
                            + expectedCount
                            + "，实际："
                            + response.getInsertCnt());
        }
        if (response.getPrimaryKeys() == null
                || response.getPrimaryKeys().size() != expectedCount) {
            throw new IllegalStateException("批量插入用户主题记忆返回的主键数量不一致");
        }
    }

    /**
     * 将LangChain4j搜索请求转换为主题记忆仓储搜索DTO。
     *
     * @param request LangChain4j搜索请求
     * @return 主题记忆仓储搜索请求
     * @throws IllegalArgumentException 请求缺少文本、向量或用户过滤条件时抛出
     */
    private static ChatMemorySearchRequest toSearchRequest(
            EmbeddingSearchRequest request) {
        if (request == null || request.queryEmbedding() == null) {
            throw new IllegalArgumentException("搜索请求和查询向量不能为空");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("搜索文本不能为空");
        }
        String wechatUserId = extractWechatUserId(request.filter());
        return new ChatMemorySearchRequest(
                wechatUserId,
                request.query(),
                request.maxResults(),
                buildSearchFilter(wechatUserId),
                request.queryEmbedding().vector());
    }

    /**
     * 从LangChain4j等值过滤器中提取微信用户ID。
     *
     * @param filter LangChain4j过滤条件
     * @return 微信用户ID
     * @throws IllegalArgumentException 过滤条件不是wechat_user_id等值过滤时抛出
     */
    private static String extractWechatUserId(Filter filter) {
        if (!(filter instanceof IsEqualTo equalTo)
                || !WECHAT_USER_ID_FIELD.equals(equalTo.key())) {
            throw new IllegalArgumentException(
                    "搜索主题记忆必须使用wechat_user_id等值过滤");
        }
        String wechatUserId = String.valueOf(equalTo.comparisonValue());
        if (wechatUserId.isBlank() || "null".equals(wechatUserId)) {
            throw new IllegalArgumentException("微信用户ID不能为空");
        }
        return wechatUserId;
    }

    /**
     * 构建Milvus用户隔离和主题发生时间过滤表达式。
     *
     * 仅允许召回当前微信用户最近十天内发生的主题记忆，并转义用户ID中的特殊字符。
     *
     * @param wechatUserId 微信用户ID
     * @return Milvus过滤表达式
     */
    private static String buildSearchFilter(String wechatUserId) {
        String escapedUserId = wechatUserId
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        long earliestOccurredAt = Instant.now()
                .minus(TOPIC_MEMORY_SEARCH_WINDOW)
                .toEpochMilli();
        return WECHAT_USER_ID_FIELD
                + " == \""
                + escapedUserId
                + "\" && "
                + TOPIC_OCCURRED_AT_FIELD
                + " >= "
                + earliestOccurredAt;
    }
}
