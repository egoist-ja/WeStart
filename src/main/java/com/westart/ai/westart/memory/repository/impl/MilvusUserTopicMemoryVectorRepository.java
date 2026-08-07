package com.westart.ai.westart.memory.repository.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.westart.ai.westart.memory.dto.ChatMemorySearchRequest;
import com.westart.ai.westart.memory.entity.UserTopicMemoryVector;
import com.westart.ai.westart.memory.repository.UserTopicMemoryVectorRepository;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 用户主题记忆Milvus仓储实现。
 *
 * 负责主题向量批量Upsert，以及稠密向量与BM25结果的混合检索。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MilvusUserTopicMemoryVectorRepository implements UserTopicMemoryVectorRepository {

    private final MilvusClientV2 milvusClient;
    private final Gson gson;

    private static final String DATABASE_NAME = "westart";
    private static final String COLLECTION_NAME = "user_topic_memory_collection";

    /**
     * 使用主题记忆ID作为固定主键批量写入Milvus。
     *
     * @param memories 用户主题记忆向量
     * @return Milvus Upsert结果
     */
    @Override
    public UpsertResp upsertBatch(List<UserTopicMemoryVector> memories) {
        if (memories == null || memories.isEmpty()) {
            log.info("主题记忆向量Upsert列表为空");
            return UpsertResp.builder()
                    .upsertCnt(0)
                    .build();
        }
        List<JsonObject> memoriesJson = memories.stream()
                .map(memory -> gson.toJsonTree(memory).getAsJsonObject())
                .toList();
        return milvusClient.upsert(UpsertReq.builder()
                .databaseName(DATABASE_NAME)
                .collectionName(COLLECTION_NAME)
                .data(memoriesJson)
                .build());
    }

    /**
     * 使用稠密向量和BM25稀疏向量混合检索用户主题记忆。
     *
     * @param searchRequest 主题记忆搜索请求
     * @return Milvus搜索结果
     */
    @Override
    public SearchResp search(ChatMemorySearchRequest searchRequest) {
        if(searchRequest == null){
            log.info("搜索请求为空");
            return SearchResp.builder()
                    .searchResults(List.of())
                    .build();
        }
        //稠密向量场搜索
        var request1 = AnnSearchReq.builder()
                .metricType(IndexParam.MetricType.COSINE)
                .vectorFieldName("topic_summary_dense_vector")
                .vectors(List.of(new FloatVec(searchRequest.embedding())))
                .filter(searchRequest.expr())
                .limit(searchRequest.maxResults())
                .build();
        //稀疏向量场搜索
        var request2 = AnnSearchReq.builder()
                .metricType(IndexParam.MetricType.BM25)
                .vectorFieldName("topic_summary_sparse_vector")
                .vectors(List.of(new EmbeddedText(searchRequest.query())))
                .filter(searchRequest.expr())
                .limit(searchRequest.maxResults())
                .build();

        return milvusClient.hybridSearch(HybridSearchReq.builder()
                .databaseName(DATABASE_NAME)
                .collectionName(COLLECTION_NAME)
                .limit(searchRequest.maxResults())
                .ranker(CreateCollectionReq.Function.builder()
                        .name("rrf")
                        .functionType(FunctionType.RERANK)
                        .param("reranker", "rrf")
                        .build())
                .outFields(List.of(
                        "wechat_user_id",
                        "topic_summary"
                ))
                .searchRequests(List.of(request1, request2))
                .build());
    }
}
