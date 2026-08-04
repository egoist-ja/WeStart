package com.westart.ai.westart.config;

import com.westart.ai.westart.entity.ToolEntity;
import com.westart.ai.westart.infra.ToolEmbeddingStore;
import com.westart.ai.westart.repository.ToolRepository;
import com.westart.ai.westart.service.tool.ToolRegistry;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreErrorContext;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreListener;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreRequestContext;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreResponseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * 本地工具配置。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ToolConfig {

    /**
     * 本地工具所在的基础包路径。
     */
    private static final String TOOL_PACKAGE =
            "com.westart.ai.westart.service.tool";

    /**
     * 创建带监听能力的工具向量存储。
     *
     * @param toolRepository 工具仓储
     * @return 带监听能力的工具向量存储
     */
    @Bean
    public EmbeddingStore<ToolEntity> toolEmbeddingStore(
            ToolRepository toolRepository) {
        return new ToolEmbeddingStore(toolRepository).addListener(new EmbeddingStoreListener() {

            @Override
            public void onRequest(EmbeddingStoreRequestContext<?> requestContext) {
                if (requestContext instanceof EmbeddingStoreRequestContext.Search<?> searchContext) {
                    EmbeddingSearchRequest request = searchContext.searchRequest();
                    log.info(
                            "工具搜索请求，查询语句={}，最大结果数={}，最低分数={}",
                            request.query(),
                            request.maxResults(),
                            request.minScore());
                    return;
                }
                if (requestContext instanceof EmbeddingStoreRequestContext.AddAll<?> addAllContext) {
                    log.info("工具向量批量写入请求，工具数量={}",
                            addAllContext.embeddedList().size());
                }
            }

            @Override
            public void onResponse(EmbeddingStoreResponseContext<?> responseContext) {
                if (responseContext instanceof EmbeddingStoreResponseContext.Search<?> searchContext) {
                    List<? extends EmbeddingMatch<?>> matches =
                            searchContext.searchResult().matches();
                    List<String> toolInformation = matches.stream()
                            .map(ToolConfig.this::formatToolMatch)
                            .toList();
                    log.info("工具搜索完成，工具数量={}，工具信息={}",
                            matches.size(), toolInformation);
                    return;
                }
                if (responseContext instanceof EmbeddingStoreResponseContext.AddAll<?> addAllContext) {
                    log.info("工具向量批量写入完成，工具数量={}",
                            addAllContext.returnedIds().size());
                }
            }

            @Override
            public void onError(EmbeddingStoreErrorContext<?> errorContext) {
                Throwable error = errorContext.error();
                log.error("工具向量存储操作失败，操作类型={}，失败原因={}",
                        errorContext.requestContext().getClass().getSimpleName(),
                        error.getMessage(),
                        error);
            }
        });
    }

    /**
     * 格式化工具匹配信息。
     *
     * @param match 工具匹配结果
     * @return 工具名称、类型和匹配分数组成的日志文本
     */
    private String formatToolMatch(EmbeddingMatch<?> match) {
        Object embedded = match.embedded();
        if (!(embedded instanceof ToolEntity toolEntity)) {
            return "未知工具(分数=" + match.score() + ")";
        }
        return toolEntity.name()
                + "(类型=" + toolEntity.type()
                + "，分数=" + match.score() + ")";
    }

    /**
     * 创建工具注册中心，并注册工具包中使用@Tool标记的方法。
     *
     * @param beanFactory Spring Bean工厂
     * @return 完成本地工具注册的工具注册中心
     */
    @Bean
    public ToolRegistry toolRegistry(ListableBeanFactory beanFactory) {
        ToolRegistry toolRegistry = new ToolRegistry();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> beanType = beanFactory.getType(beanName); //获取bean类型
            if (!isToolBean(beanType)) {
                continue;
            }
            toolRegistry.registerLocalTool(
                    beanName,
                    beanFactory.getBean(beanName));
        }
        return toolRegistry;
    }

    /**
     * 判断Bean类型是否属于工具包并包含工具方法。
     *
     * @param beanType Bean类型
     * @return 包含工具方法时返回true，否则返回false
     */
    private boolean isToolBean(Class<?> beanType) {
        if (beanType == null) {
            return false;
        }

        Class<?> userClass = ClassUtils.getUserClass(beanType);
        String packageName = userClass.getPackageName();
        if (!packageName.equals(TOOL_PACKAGE)
                && !packageName.startsWith(TOOL_PACKAGE + ".")) {
            return false;
        }
        return Arrays.stream(userClass.getMethods())
                .anyMatch(this::isToolMethod);
    }

    /**
     * 判断方法是否使用@Tool标记。
     *
     * @param method 待判断的方法
     * @return 使用@Tool标记时返回true，否则返回false
     */
    private boolean isToolMethod(Method method) {
        return method.isAnnotationPresent(Tool.class);
    }
}
