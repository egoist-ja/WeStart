package com.westart.ai.westart.repository.impl;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.westart.ai.westart.entity.ToolEntity;
import com.westart.ai.westart.repository.ToolRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ToolRepositoryImpl implements ToolRepository {

    private final MilvusClientV2 milvusClient;
    private final Gson gson;

    private static final String DATABASE_NAME = "westart";
    private static final String COLLECTION_NAME = "toolCollection";

    @Override
    public InsertResp insertBatch(List<ToolEntity> toolEntities) {
        List<JsonObject> list = toolEntities.stream()
                .map(gson::toJsonTree)
                .map(JsonElement::getAsJsonObject)
                .toList();
        return milvusClient.insert(InsertReq.builder()
                .databaseName(DATABASE_NAME)
                .collectionName(COLLECTION_NAME)
                .data(list)
                .build());
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
}
