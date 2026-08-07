package com.westart.ai.westart.memory.config;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverErrorContext;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverListener;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverRequestContext;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverResponseContext;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 用户主题记忆检索配置。
 *
 * <p>负责组装基于向量存储的内容检索器，并按当前微信用户隔离召回结果。</p>
 */
@Slf4j
@Configuration
public class RetrieverConfig {

    /** 用户主题记忆检索器名称。 */
    private static final String RETRIEVER_NAME = "user-topic-memory";

    /** 监听上下文中保存检索开始时间的属性名称。 */
    private static final String START_TIME_ATTRIBUTE =
            RetrieverConfig.class.getName() + ".startTimeNanos";

    /** 单次主题记忆最大召回数量。 */
    private static final int MAX_RESULTS = 3;

    /** 主题记忆最低召回分数。 */
    private static final double MIN_SCORE = 0.015D;

    /**
     * 配置Milvus用户主题记忆内容检索器。
     *
     * 使用聊天记忆ID作为微信用户ID过滤条件，确保只召回当前用户的主题记忆。
     *
     * @param userTopicEmbeddingStore 用户主题记忆向量存储
     * @param embeddingModel 主题记忆向量模型
     * @return 用户主题记忆内容检索器
     */
    @Bean
    public ContentRetriever milvusContentRetriever(
            EmbeddingStore<TextSegment> userTopicEmbeddingStore,
            QwenEmbeddingModel embeddingModel) {
        return EmbeddingStoreContentRetriever.builder()
                .displayName(RETRIEVER_NAME)
                .embeddingStore(userTopicEmbeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(MAX_RESULTS)
                .minScore(MIN_SCORE)
                .dynamicFilter(query -> new IsEqualTo(
                        "wechat_user_id",
                        query.metadata().chatMemoryId()))
                .build()
                .addListener(new ContentRetrieverListener() {
                    @Override
                    public void onRequest(ContentRetrieverRequestContext requestContext) {
                        requestContext.attributes().put(
                                START_TIME_ATTRIBUTE,
                                System.nanoTime());
                        log.info(
                                "用户主题记忆检索开始，retriever={}，invocationId={}，"
                                        + "queryLength={}，maxResults={}，minScore={}",
                                RETRIEVER_NAME,
                                resolveInvocationId(requestContext.query()),
                                StringUtils.length(requestContext.query().text()),
                                MAX_RESULTS,
                                MIN_SCORE);
                    }

                    @Override
                    public void onResponse(ContentRetrieverResponseContext responseContext) {
                        List<Content> contents = responseContext.contents() == null
                                ? List.of()
                                : responseContext.contents();
                        List<Object> topicMemoryIds = contents.stream()
                                .map(Content::metadata)
                                .map(metadata -> metadata.get(ContentMetadata.EMBEDDING_ID))
                                .filter(Objects::nonNull)
                                .toList();
                        List<Object> scores = contents.stream()
                                .map(Content::metadata)
                                .map(metadata -> metadata.get(ContentMetadata.SCORE))
                                .filter(Objects::nonNull)
                                .toList();
                        log.info(
                                "用户主题记忆检索完成，retriever={}，invocationId={}，"
                                        + "elapsedMs={}，resultCount={}，result={},topicMemoryIds={}，scores={}",
                                RETRIEVER_NAME,
                                resolveInvocationId(responseContext.query()),
                                elapsedMillis(responseContext.attributes()),
                                contents.size(),
                                contents,
                                topicMemoryIds,
                                scores);
                    }

                    @Override
                    public void onError(ContentRetrieverErrorContext errorContext) {
                        Throwable error = errorContext.error();
                        log.error(
                                "用户主题记忆检索失败，retriever={}，invocationId={}，"
                                        + "elapsedMs={}，exceptionType={}，reason={}",
                                RETRIEVER_NAME,
                                resolveInvocationId(errorContext.query()),
                                elapsedMillis(errorContext.attributes()),
                                error.getClass().getSimpleName(),
                                error.getMessage(),
                                error);
                    }
                });
    }

    /**
     * 获取当前检索所属的AI服务调用编号。
     *
     * @param query 主题记忆查询
     * @return AI服务调用编号；调用上下文不存在时返回unknown
     */
    private static String resolveInvocationId(Query query) {
        if (query == null
                || query.metadata() == null
                || query.metadata().invocationContext() == null
                || query.metadata().invocationContext().invocationId() == null) {
            return "unknown";
        }
        return query.metadata().invocationContext().invocationId().toString();
    }

    /**
     * 根据监听上下文中的开始时间计算检索耗时。
     *
     * @param attributes 同一次检索共享的监听属性
     * @return 检索耗时，单位毫秒；缺少开始时间时返回-1
     */
    private static long elapsedMillis(Map<Object, Object> attributes) {
        Object startTime = attributes.get(START_TIME_ATTRIBUTE);
        if (!(startTime instanceof Long startTimeNanos)) {
            return -1L;
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
    }
}
