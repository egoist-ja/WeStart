package com.westart.ai.westart.tool.infra;


import com.westart.ai.westart.tool.entity.ToolEntity;
import com.westart.ai.westart.tool.domain.ToolType;
import com.westart.ai.westart.tool.repository.ToolRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具向量存储适配器。
 */
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
     * @return 不支持该操作
     * @throws UnsupportedOperationException 工具向量未关联工具实体时抛出
     */
    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException("插入工具向量时必须提供工具实体");
    }

    /**
     * 使用指定主键添加不包含工具实体的向量。
     *
     * @param id 向量主键
     * @param embedding 工具向量
     * @throws UnsupportedOperationException 工具向量未关联工具实体时抛出
     */
    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException("插入工具向量时必须提供工具实体");
    }

    /**
     * 添加工具实体及其向量。
     *
     * @param embedding 工具向量
     * @param toolEntity 工具实体
     * @return 不支持该操作
     * @throws UnsupportedOperationException 当前存储仅支持批量同步工具
     */
    @Override
    public String add(Embedding embedding, ToolEntity toolEntity) {
        throw new UnsupportedOperationException("工具向量仅支持批量同步");
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
     * 批量新增或更新工具实体及其向量。
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
        validateElements(embeddings, toolEntities);

        List<String> toolIds = toolEntities.stream()
                .map(ToolEntity::id)
                .toList();
        Map<String, ToolEntity> existingTools = new HashMap<>();
        toolRepository.findByIds(toolIds)
                .forEach(tool -> existingTools.put(tool.id(), tool));

        List<Embedding> changedEmbeddings = new ArrayList<>();
        List<ToolEntity> changedTools = new ArrayList<>();
        for (int index = 0; index < toolEntities.size(); index++) {
            ToolEntity tool = toolEntities.get(index);
            Embedding embedding = embeddings.get(index);
            if (!hasSameMetadata(tool, existingTools.get(tool.id()))) {
                changedEmbeddings.add(embedding);
                changedTools.add(tool);
            }
        }
        if (changedTools.isEmpty()) {
            return toolIds;
        }

        List<ToolEntity> entitiesToInsert = mergeEmbeddings(
                changedEmbeddings,
                changedTools);
        UpsertResp response;
        try {
            response = toolRepository.upsertBatch(entitiesToInsert);
        } catch (RuntimeException e) {
            throw new IllegalStateException("批量新增或更新工具向量失败", e);
        }
        if (response == null) {
            throw new IllegalStateException("批量新增或更新工具向量未返回结果");
        }
        if (response.getUpsertCnt() != entitiesToInsert.size()) {
            throw new IllegalStateException(
                    "批量新增或更新工具向量数量不一致，期望："
                            + entitiesToInsert.size()
                            + "，实际："
                            + response.getUpsertCnt());
        }
        List<Object> primaryKeys = response.getPrimaryKeys();
        if (primaryKeys == null || primaryKeys.size() != entitiesToInsert.size()) {
            throw new IllegalStateException("批量新增或更新工具向量返回的主键数量不一致");
        }
        return toolIds;
    }

    /**
     * 将工具向量合并到对应的工具实体中。
     *
     * @param embeddings 工具向量列表
     * @param toolEntities 工具实体列表
     * @return 包含稠密向量的工具实体列表
     */
    private static List<ToolEntity> mergeEmbeddings(
            List<Embedding> embeddings,
            List<ToolEntity> toolEntities) {
        List<ToolEntity> entitiesToInsert = new ArrayList<>(toolEntities.size());
        for (int index = 0; index < toolEntities.size(); index++) {
            Embedding embedding = embeddings.get(index);
            ToolEntity toolEntity = toolEntities.get(index);
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
     * 校验批量写入的工具实体和向量元素。
     *
     * @param embeddings 工具向量列表
     * @param toolEntities 工具实体列表
     * @throws IllegalArgumentException 列表包含空元素或空向量时抛出
     */
    private static void validateElements(
            List<Embedding> embeddings,
            List<ToolEntity> toolEntities) {
        for (int index = 0; index < toolEntities.size(); index++) {
            Embedding embedding = embeddings.get(index);
            ToolEntity toolEntity = toolEntities.get(index);
            if (embedding == null || toolEntity == null) {
                throw new IllegalArgumentException(
                        "向量和工具实体不能包含空元素，位置：" + index);
            }
            if (embedding.vector().length == 0) {
                throw new IllegalArgumentException(
                        "工具向量不能为空，位置：" + index);
            }
        }
    }

    /**
     * 判断工具检索元数据是否未发生变化。
     *
     * @param currentTool 当前工具实体
     * @param existingTool Milvus中的已有工具实体
     * @return 工具类型、名称、描述和入参Schema均相同时返回true
     */
    private boolean hasSameMetadata(
            ToolEntity currentTool,
            ToolEntity existingTool) {
        return existingTool != null
                && currentTool.type() == existingTool.type()
                && currentTool.name().equals(existingTool.name())
                && currentTool.description().equals(existingTool.description())
                && currentTool.inputSchema().equals(existingTool.inputSchema());
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
        String toolId = String.valueOf(result.getId());

        ToolEntity tool = new ToolEntity(
                toolId,
                ToolType.valueOf(String.valueOf(fields.get("type"))),
                String.valueOf(fields.get("name")),
                String.valueOf(fields.get("description")),
                String.valueOf(fields.get("inputSchema"))
        );

        return new EmbeddingMatch<>(
                result.getScore().doubleValue(),
                toolId,
                null,
                tool
        );
    }
}
