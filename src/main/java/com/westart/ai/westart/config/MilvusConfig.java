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
import java.util.function.Consumer;

@Slf4j
@Configuration
public class MilvusConfig {

    private static final String DATABASE_NAME = "westart";
    private static final String TOOL_COLLECTION_NAME = "tool_collection";
    private static final String USER_TOPIC_MEMORY_COLLECTION_NAME = "user_topic_memory_collection";

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
        createCollectionIfAbsent(
                milvusClient, TOOL_COLLECTION_NAME, this::createToolCollection);
        createCollectionIfAbsent(
                milvusClient,
                USER_TOPIC_MEMORY_COLLECTION_NAME,
                this::createChatMessageCollection);
        log.info("milvus初始化完成");
    }

    /**
     * 当指定Collection不存在时执行初始化，已存在时直接跳过。
     *
     * @param milvusClient Milvus客户端
     * @param collectionName Collection名称
     * @param collectionCreator Collection创建方法
     */
    private void createCollectionIfAbsent(
            MilvusClientV2 milvusClient,
            String collectionName,
            Consumer<MilvusClientV2> collectionCreator) {
        boolean collectionExists = milvusClient.hasCollection(
                HasCollectionReq.builder()
                        .databaseName(DATABASE_NAME)
                        .collectionName(collectionName)
                        .build());
        if (collectionExists) {
            log.info("Milvus Collection已存在，跳过初始化，collectionName={}",
                    collectionName);
            return;
        }

        collectionCreator.accept(milvusClient);
        log.info("Milvus Collection初始化完成，collectionName={}", collectionName);
    }

    private void createDatabase(MilvusClientV2 milvusClient){
        milvusClient.createDatabase(CreateDatabaseReq.builder()
                .databaseName(DATABASE_NAME)
                .build());
    }

    /**
     * 创建工具搜索表
     * @param milvusClient
     */
    private void createToolCollection(MilvusClientV2 milvusClient){
        CreateCollectionReq.CollectionSchema schema = MilvusClientV2.CreateSchema();
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.VarChar)
                .fieldName("id")
                .isPrimaryKey(true)
                .autoID(false)
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
                .maxLength(128)
                .build());
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.VarChar)
                .fieldName("description")
                .maxLength(2048)
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

    /**
     * 创建用户最近聊天主题记忆Collection。
     *
     * @param milvusClient Milvus客户端
     */
    private void createChatMessageCollection(MilvusClientV2 milvusClient) {
        CreateCollectionReq.CollectionSchema schema = MilvusClientV2.CreateSchema();
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.Int64)
                .fieldName("topic_memory_id")
                .isPrimaryKey(true)
                .autoID(false)
                .build());
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.VarChar)
                .fieldName("wechat_user_id")
                .maxLength(128)
                .build());
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.VarChar)
                .fieldName("topic_summary")
                .maxLength(4096)
                .enableAnalyzer(true)
                .enableMatch(true)
                .build());
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.Int64)
                .fieldName("topic_occurred_at")
                .build());
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.FloatVector)
                .dimension(1024)
                .fieldName("topic_summary_dense_vector")
                .build());
        schema.addField(AddFieldReq.builder()
                .dataType(DataType.SparseFloatVector)
                .fieldName("topic_summary_sparse_vector")
                .build());
        schema.addFunction(CreateCollectionReq.Function.builder()
                .functionType(FunctionType.BM25)
                .name("topic_bm25")
                .inputFieldNames(Collections.singletonList("topic_summary"))
                .outputFieldNames(Collections.singletonList(
                        "topic_summary_sparse_vector"))
                .build());

        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder()
                .fieldName("topic_summary_dense_vector")
                .indexName("topic_summary_dense_index")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build());
        indexes.add(IndexParam.builder()
                .fieldName("topic_summary_sparse_vector")
                .indexName("topic_summary_sparse_index")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build());
        indexes.add(IndexParam.builder()
                .fieldName("wechat_user_id")
                .indexName("wechat_user_id_index")
                .indexType(IndexParam.IndexType.INVERTED)
                .build());
        indexes.add(IndexParam.builder()
                .fieldName("topic_occurred_at")
                .indexName("topic_occurred_at_index")
                .indexType(IndexParam.IndexType.INVERTED)
                .build());

        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(USER_TOPIC_MEMORY_COLLECTION_NAME)
                .collectionSchema(schema)
                .indexParams(indexes)
                .build());
    }
}
