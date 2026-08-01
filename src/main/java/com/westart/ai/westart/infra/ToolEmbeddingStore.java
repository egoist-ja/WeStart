package com.westart.ai.westart.infra;


import com.westart.ai.westart.entity.ToolEntity;
import com.westart.ai.westart.entity.ToolType;
import com.westart.ai.westart.repository.ToolRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具向量存储适配器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolEmbeddingStore implements EmbeddingStore<ToolEntity> {

    /**
     * 工具仓储。
     */
    private final ToolRepository toolRepository;

    /**
     * 添加不包含工具实体的单个向量。
     *
     * @param embedding 工具向量
     * @return 向量主键
     */
    @Override
    public String add(Embedding embedding) {
        return "";
    }

    /**
     * 使用指定主键添加不包含工具实体的向量。
     *
     * @param id 向量主键
     * @param embedding 工具向量
     */
    @Override
    public void add(String id, Embedding embedding) {

    }

    /**
     * 添加工具实体及其向量。
     *
     * @param embedding 工具向量
     * @param toolEntity 工具实体
     * @return 工具主键
     */
    @Override
    public String add(Embedding embedding, ToolEntity toolEntity) {
        return "";
    }

    /**
     * 批量添加不包含工具实体的向量。
     *
     * @param embeddings 工具向量列表
     * @return 工具主键列表
     * @throws UnsupportedOperationException 工具向量未关联工具实体时抛出
     */
    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException("批量插入工具向量时必须提供工具实体");
    }

    /**
     * 批量添加工具实体及其向量。
     *
     * @param embeddings 工具向量列表
     * @param toolEntities 工具实体列表
     * @return 插入成功的工具主键列表
     * @throws IllegalArgumentException 参数为空、数量不一致或包含无效元素时抛出
     * @throws IllegalStateException Milvus写入失败或返回结果不符合预期时抛出
     */
    @Override
    public List<String> addAll(List<Embedding> embeddings, List<ToolEntity> toolEntities) {
        if (embeddings == null || toolEntities == null) {
            throw new IllegalArgumentException("向量列表和工具实体列表不能为空");
        }
        if (embeddings.size() != toolEntities.size()) {
            throw new IllegalArgumentException("向量数量与工具实体数量不一致");
        }
        if (embeddings.isEmpty()) {
            return List.of();
        }

        List<ToolEntity> entitiesToInsert = getToolEntities(embeddings, toolEntities);
        InsertResp response;
        try {
            response = toolRepository.insertBatch(entitiesToInsert);
        } catch (RuntimeException e) {
            throw new IllegalStateException("批量插入工具向量失败", e);
        }
        if (response == null) {
            throw new IllegalStateException("批量插入工具向量未返回结果");
        }
        if (response.getInsertCnt() != entitiesToInsert.size()) {
            throw new IllegalStateException(
                    "批量插入工具向量数量不一致，期望："
                            + entitiesToInsert.size()
                            + "，实际："
                            + response.getInsertCnt());
        }
        List<Object> primaryKeys = response.getPrimaryKeys();
        if (primaryKeys == null || primaryKeys.size() != entitiesToInsert.size()) {
            throw new IllegalStateException("批量插入工具向量返回的主键数量不一致");
        }
        return primaryKeys.stream()
                .map(String::valueOf)
                .toList();
    }

    /**
     * 将工具向量合并到对应的工具实体中。
     *
     * @param embeddings 工具向量列表
     * @param toolEntities 工具实体列表
     * @return 包含稠密向量的工具实体列表
     * @throws IllegalArgumentException 列表包含空元素或空向量时抛出
     */
    private static @NonNull List<ToolEntity> getToolEntities(List<Embedding> embeddings, List<ToolEntity> toolEntities) {
        List<ToolEntity> entitiesToInsert = new ArrayList<>(toolEntities.size());
        for (int index = 0; index < toolEntities.size(); index++) {
            Embedding embedding = embeddings.get(index);
            ToolEntity toolEntity = toolEntities.get(index);
            if (embedding == null || toolEntity == null) {
                throw new IllegalArgumentException("向量和工具实体不能包含空元素，位置：" + index);
            }
            if (embedding.vector().length == 0) {
                throw new IllegalArgumentException("工具向量不能为空，位置：" + index);
            }
            entitiesToInsert.add(new ToolEntity(
                    toolEntity.id(),
                    toolEntity.type(),
                    toolEntity.name(),
                    toolEntity.description(),
                    toolEntity.inputSchema(),
                    embedding.vector()));
        }
        return entitiesToInsert;
    }

    /**
     * 根据查询文本和查询向量混合搜索相关工具。
     *
     * @param request LangChain4j向量搜索请求
     * @return LangChain4j工具向量搜索结果
     */
    @Override
    public EmbeddingSearchResult<ToolEntity> search(EmbeddingSearchRequest request) {
        SearchResp response = toolRepository.search(
                request.query(),
                request.maxResults(),
                request.queryEmbedding().vector());
        List<EmbeddingMatch<ToolEntity>> matches =
                response.getSearchResults().stream()
                        .flatMap(List::stream)
                        .filter(result ->
                                result.getScore() >= request.minScore())
                        .map(this::toEmbeddingMatch)
                        .toList();
        return new EmbeddingSearchResult<>(matches);
    }

    /**
     * 将Milvus搜索结果转换为LangChain4j向量匹配结果。
     *
     * @param result Milvus单条搜索结果
     * @return LangChain4j工具向量匹配结果
     */
    private EmbeddingMatch<ToolEntity> toEmbeddingMatch(SearchResp.SearchResult result) {

        Map<String, Object> fields = result.getEntity();

        ToolEntity tool = new ToolEntity(
                result.getPrimaryKey(),
                ToolType.valueOf(String.valueOf(fields.get("type"))),
                String.valueOf(fields.get("name")),
                String.valueOf(fields.get("description")),
                String.valueOf(fields.get("inputSchema"))
        );

        return new EmbeddingMatch<>(
                result.getScore().doubleValue(),
                result.getPrimaryKey(),
                null,
                tool
        );
    }
}
