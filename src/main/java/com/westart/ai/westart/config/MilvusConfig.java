package com.westart.ai.westart.config;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Configuration
public class MilvusConfig {

    private static final String DATABASE_NAME = "westart";
    private static final String TOOL_COLLECTION_NAME = "toolCollection";

    @Bean
    public MilvusClientV2 milvusClient() {
        MilvusClientV2 milvusClient = new MilvusClientV2(
                ConnectConfig.builder()
                        .uri("http://127.0.0.1:19530")
                        .build()
        );
        init(milvusClient);
        return milvusClient;
    }

    private void init(MilvusClientV2 milvusClient) {
        boolean databaseExists = milvusClient.listDatabases()
                .getDatabaseNames()
                .contains(DATABASE_NAME);
        if (!databaseExists) {
            createDatabase(milvusClient);
        }

        try {
            milvusClient.useDatabase(DATABASE_NAME);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("切换Milvus数据库时线程被中断", e);
        }
        boolean collectionExists = milvusClient.hasCollection(
                HasCollectionReq.builder()
                        .databaseName(DATABASE_NAME)
                        .collectionName(TOOL_COLLECTION_NAME)
                        .build());
        if (!collectionExists) {
            createCollection(milvusClient);
        }
        log.info("milvus初始化完成");
    }

    private void createDatabase(MilvusClientV2 milvusClient){
        milvusClient.createDatabase(CreateDatabaseReq.builder()
                .databaseName(DATABASE_NAME)
                .build());
    }

    private void createCollection(MilvusClientV2 milvusClient){
        CreateCollectionReq.CollectionSchema schema = MilvusClientV2.CreateSchema();
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.VarChar)
                .fieldName("id")
                .isPrimaryKey(true)
                .autoID(true)
                .maxLength(32)
                .build());
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.VarChar)
                .fieldName("type")
                .maxLength(8)
                .build());
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.VarChar)
                .fieldName("name")
                .maxLength(32)
                .build());
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.VarChar)
                .fieldName("description")
                .maxLength(512)
                .enableAnalyzer(true)
                .enableMatch(true)
                .build());
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.JSON)
                .fieldName("inputSchema")
                .build());
        //定义向量字段
        //稠密向量
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.FloatVector)
                .dimension(1024)
                .fieldName("description_dense_vector")
                .build());
        //稀疏向量
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.SparseFloatVector)
                .dimension(1024)
                .fieldName("description_sparse_vector")
                .build());
        //配置函数
        schema.addFunction(CreateCollectionReq.Function.builder()
                .functionType(FunctionType.BM25)
                .name("description_bm25_emb")
                .inputFieldNames(Collections.singletonList("description"))
                .outputFieldNames(Collections.singletonList("description_sparse_vector"))
                .build());
        //构建索引
        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder()
                .fieldName("description_dense_vector")
                .indexName("description_dense_vector_index")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build());
        indexes.add(IndexParam.builder()
                .fieldName("description_sparse_vector")
                .indexName("description_sparse_vector_index")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build());

        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(TOOL_COLLECTION_NAME)
                .collectionSchema(schema)
                .indexParams(indexes)
                .build());
    }
}
