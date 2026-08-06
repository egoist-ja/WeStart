package com.westart.ai.westart.infra;

import com.westart.ai.westart.DTO.ChatMemorySearchRequest;
import com.westart.ai.westart.entity.UserTopicMemory;
import com.westart.ai.westart.repository.ChatMessageRepository;
import dev.langchain4j.data.embedding.Embedding;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户主题记忆向量存储适配器。
 *
 * 负责LangChain4j对象与用户主题记忆仓储DTO之间的转换，
 * 不承载记忆提取、合并和召回策略。
 */
@Component
@RequiredArgsConstructor
public class ChatMessageEmbeddingStore implements EmbeddingStore<UserTopicMemory> {

    private static final String WECHAT_USER_ID_FIELD = "wechat_user_id";

    private final ChatMessageRepository chatMessageRepository;

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
     * @param userTopicMemory 用户主题记忆
     * @return 不会正常返回
     * @throws UnsupportedOperationException 当前仅开放批量写入链路时抛出
     */
    @Override
    public String add(Embedding embedding, UserTopicMemory userTopicMemory) {
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
     * @param memories 用户主题记忆列表
     * @return 成功插入的主题记忆主键列表
     * @throws IllegalArgumentException 参数为空、数量不一致或包含无效元素时抛出
     * @throws IllegalStateException Milvus插入结果为空或数量不一致时抛出
     */
    @Override
    public List<String> addAll(List<Embedding> embeddings, List<UserTopicMemory> memories) {
        validateBatchArguments(embeddings, memories);
        if (embeddings.isEmpty()) {
            return List.of();
        }

        List<UserTopicMemory> memoriesToInsert = mergeEmbeddings(embeddings, memories);
        InsertResp response = chatMessageRepository.insertBatch(memoriesToInsert);
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
    public EmbeddingSearchResult<UserTopicMemory> search(EmbeddingSearchRequest request) {
        ChatMemorySearchRequest searchRequest = toSearchRequest(request);
        SearchResp response = chatMessageRepository.search(searchRequest);
        if (response == null || response.getSearchResults() == null) {
            throw new IllegalStateException("搜索用户主题记忆未返回结果");
        }

        List<EmbeddingMatch<UserTopicMemory>> matches = response.getSearchResults().stream()
                .flatMap(List::stream)
                .filter(result -> result.getScore() >= request.minScore())
                .map(this::toEmbeddingMatch)
                .toList();
        return new EmbeddingSearchResult<>(matches);
    }

    /**
     * 校验批量写入参数的非空性和数量一致性。
     *
     * @param embeddings 稠密向量列表
     * @param memories 用户主题记忆列表
     * @throws IllegalArgumentException 列表为空引用或数量不一致时抛出
     */
    private static void validateBatchArguments(
            List<Embedding> embeddings,
            List<UserTopicMemory> memories) {
        if (embeddings == null || memories == null) {
            throw new IllegalArgumentException("向量列表和主题记忆列表不能为空");
        }
        if (embeddings.size() != memories.size()) {
            throw new IllegalArgumentException("向量数量与主题记忆数量不一致");
        }
    }

    /**
     * 将稠密向量合并到对应的用户主题记忆中。
     *
     * @param embeddings 稠密向量列表
     * @param memories 用户主题记忆列表
     * @return 包含稠密向量、可直接交给仓储写入的主题记忆列表
     * @throws IllegalArgumentException 列表包含空元素或空向量时抛出
     */
    private static List<UserTopicMemory> mergeEmbeddings(
            List<Embedding> embeddings,
            List<UserTopicMemory> memories) {
        List<UserTopicMemory> memoriesToInsert = new ArrayList<>(memories.size());
        for (int index = 0; index < memories.size(); index++) {
            Embedding embedding = embeddings.get(index);
            UserTopicMemory memory = memories.get(index);
            if (embedding == null || memory == null) {
                throw new IllegalArgumentException(
                        "向量和主题记忆不能包含空元素，位置：" + index);
            }
            float[] vector = embedding.vector();
            if (vector == null || vector.length == 0) {
                throw new IllegalArgumentException("主题记忆向量不能为空，位置：" + index);
            }
            memoriesToInsert.add(new UserTopicMemory(
                    memory.topicMemoryId(),
                    memory.wechatUserId(),
                    memory.topicSummary(),
                    memory.topicOccurredAt(),
                    vector));
        }
        return memoriesToInsert;
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
                buildUserFilter(wechatUserId),
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
     * 构建Milvus用户隔离过滤表达式，并转义字符串特殊字符。
     *
     * @param wechatUserId 微信用户ID
     * @return Milvus过滤表达式
     */
    private static String buildUserFilter(String wechatUserId) {
        String escapedUserId = wechatUserId
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return WECHAT_USER_ID_FIELD + " == \"" + escapedUserId + "\"";
    }

    /**
     * 将Milvus单条搜索结果转换为LangChain4j向量匹配结果。
     *
     * @param result Milvus单条搜索结果
     * @return 用户主题记忆向量匹配结果
     * @throws IllegalStateException 搜索结果缺少必需字段时抛出
     */
    private EmbeddingMatch<UserTopicMemory> toEmbeddingMatch(
            SearchResp.SearchResult result) {
        Map<String, Object> fields = result.getEntity();
        UserTopicMemory memory = new UserTopicMemory(
                Long.valueOf(String.valueOf(result.getPrimaryKey())),
                requiredField(fields, WECHAT_USER_ID_FIELD),
                requiredField(fields, "topic_summary"),
                topicOccurredAtField(fields),
                null);
        return new EmbeddingMatch<>(
                result.getScore().doubleValue(),
                String.valueOf(result.getPrimaryKey()),
                null,
                memory);
    }

    /**
     * 读取Milvus搜索结果中的必需字段。
     *
     * @param fields Milvus实体字段
     * @param fieldName 字段名称
     * @return 字段字符串值
     * @throws IllegalStateException 字段不存在或值为null时抛出
     */
    private static Long topicOccurredAtField(Map<String, Object> fields) {
        Object value = fields.get("topic_occurred_at");
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private static String requiredField(Map<String, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);
        if (value == null) {
            throw new IllegalStateException("搜索结果缺少字段：" + fieldName);
        }
        return String.valueOf(value);
    }

}
