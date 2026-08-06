package com.westart.ai.westart.repository.impl;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.westart.ai.westart.entity.ToolEntity;
import com.westart.ai.westart.entity.ToolType;
import com.westart.ai.westart.repository.ToolRepository;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ToolRepositoryImpl implements ToolRepository {

    private final MilvusClientV2 milvusClient;
    private final Gson gson;

    private static final String DATABASE_NAME = "westart";
    private static final String COLLECTION_NAME = "toolCollection";

    @Override
    public UpsertResp upsertBatch(List<ToolEntity> toolEntities) {
        List<JsonObject> list = toolEntities.stream()
                .map(gson::toJsonTree)
                .map(JsonElement::getAsJsonObject)
                .toList();
        return milvusClient.upsert(UpsertReq.builder()
                .databaseName(DATABASE_NAME)
                .collectionName(COLLECTION_NAME)
                .data(list)
                .build());
    }

    @Override
    public List<ToolEntity> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        QueryResp response = milvusClient.query(QueryReq.builder()
                .databaseName(DATABASE_NAME)
                .collectionName(COLLECTION_NAME)
                .filter("id in {ids}")
                .filterTemplateValues(Map.of("ids", ids))
                .outputFields(List.of(
                        "id",
                        "type",
                        "name",
                        "description",
                        "inputSchema"))
                .limit(ids.size())
                .build());
        if (response == null || response.getQueryResults() == null) {
            throw new IllegalStateException("查询已有工具未返回结果");
        }
        return response.getQueryResults().stream()
                .map(QueryResp.QueryResult::getEntity)
                .map(this::toToolEntity)
                .toList();
    }

    @Override
    public void deleteInactiveTools(List<String> activeIds) {
        if (activeIds == null || activeIds.isEmpty()) {
            throw new IllegalArgumentException("有效工具主键列表不能为空");
        }
        DeleteResp response = milvusClient.delete(DeleteReq.builder()
                .databaseName(DATABASE_NAME)
                .collectionName(COLLECTION_NAME)
                .filter("id not in {activeIds}")
                .filterTemplateValues(Map.of("activeIds", activeIds))
                .build());
        if (response == null) {
            throw new IllegalStateException("删除失效工具未返回结果");
        }
        log.info("失效工具清理完成，删除数量={}", response.getDeleteCnt());
    }

    @Override
    public SearchResp search(String query, int maxResults, float[] embedding) {
        //稠密向量场搜索
        var request1 = AnnSearchReq.builder()
                .metricType(IndexParam.MetricType.COSINE)
                .vectorFieldName("description_dense_vector")
                .vectors(List.of(new FloatVec(embedding)))
                .limit(maxResults)
                .build();
        //稀疏向量场搜索
        var request2 = AnnSearchReq.builder()
                .metricType(IndexParam.MetricType.BM25)
                .vectorFieldName("description_sparse_vector")
                .vectors(List.of(new EmbeddedText(query)))
                .limit(maxResults)
                .build();
        return milvusClient.hybridSearch(HybridSearchReq.builder()
                .databaseName(DATABASE_NAME)
                .collectionName(COLLECTION_NAME)
                .limit(maxResults)
                .ranker(CreateCollectionReq.Function.builder()
                        .name("rrf")
                        .functionType(FunctionType.RERANK)
                        .param("reranker", "rrf")
                        .build())
                .outFields(List.of(
                        "type",
                        "name",
                        "description","inputSchema"
                ))
                .searchRequests(List.of(request1, request2))
                .build());
    }

    /**
     * 将Milvus字段映射为工具实体。
     *
     * @param fields Milvus工具字段
     * @return 工具实体
     */
    private ToolEntity toToolEntity(Map<String, Object> fields) {
        Object inputSchema = fields.get("inputSchema");
        String inputSchemaJson = inputSchema instanceof String value
                ? value
                : gson.toJson(inputSchema);
        return new ToolEntity(
                String.valueOf(fields.get("id")),
                ToolType.valueOf(String.valueOf(fields.get("type"))),
                String.valueOf(fields.get("name")),
                String.valueOf(fields.get("description")),
                inputSchemaJson);
    }
}
