package com.westart.ai.westart.repository.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.westart.ai.westart.DTO.ChatMemorySearchRequest;
import com.westart.ai.westart.entity.ChatMessage;
import com.westart.ai.westart.repository.ChatMessageRepository;
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

@Slf4j
@Repository
@RequiredArgsConstructor
public class ChatMessageRepositoryImpl implements ChatMessageRepository {

    private final MilvusClientV2 milvusClient;
    private final Gson gson;

    private static final String DATABASE_NAME = "westart";
    private static final String COLLECTION_NAME = "user_topic_memory";

    @Override
    public InsertResp insertBatch(List<ChatMessage> messages) {
        if(messages==null||messages.isEmpty()){
            log.info("插入列表为空");
            return InsertResp.builder()
                    .InsertCnt(0)
                    .build();
        }
        List<JsonObject> messagesJson = messages.stream()
                .map(message -> gson.toJsonTree(message).getAsJsonObject())
                .toList();
        return milvusClient.insert(InsertReq.builder()
                .databaseName(DATABASE_NAME)
                .collectionName(COLLECTION_NAME)
                .data(messagesJson)
                .build());
    }

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
                .vectorFieldName("searchable_content_dense_vector")
                .vectors(List.of(new FloatVec(searchRequest.embedding())))
                .limit(searchRequest.maxResults())
                .build();
        //稀疏向量场搜索
        var request2 = AnnSearchReq.builder()
                .metricType(IndexParam.MetricType.BM25)
                .vectorFieldName("searchable_content_sparse_vector")
                .vectors(List.of(new EmbeddedText(searchRequest.query())))
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
                        "memoryId",
                        "wechatUserId",
                        "topic","searchableContent",
                        "occurredAt","expiresAt"
                ))
                .searchRequests(List.of(request1, request2))
                .build());
    }
}
