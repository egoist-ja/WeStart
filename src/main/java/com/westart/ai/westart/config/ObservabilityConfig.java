package com.westart.ai.westart.config;
import dev.langchain4j.observability.api.listener.AiServiceCompletedListener;
import dev.langchain4j.observability.api.listener.AiServiceErrorListener;
import dev.langchain4j.observability.api.listener.AiServiceStartedListener;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * AI 服务可观测性配置
 * 注册监听器以跟踪 AI 服务调用和工具执行
 */
@Configuration
@Slf4j
public class ObservabilityConfig {

    /**
     * AI 服务开始监听器
     */
    @Bean
    public AiServiceStartedListener aiServiceStartedListener() {
        return event -> {
            var context = event.invocationContext();
            log.info("[Observability] AI服务调用开始，interface={}，method={}，invocationId={}",
                    context.interfaceName(),
                    context.methodName(),
                    context.invocationId());
        };
    }

    /**
     * AI 服务完成监听器
     */
    @Bean
    public AiServiceCompletedListener aiServiceCompletedListener() {
        return event -> {
            var context = event.invocationContext();
            log.info("[Observability] AI服务调用完成，interface={}，method={}，invocationId={}，result={}",
                    context.interfaceName(),
                    context.methodName(),
                    context.invocationId(),
                    event.result().orElse("null"));
        };
    }

    /**
     * AI 服务错误监听器
     */
    @Bean
    public AiServiceErrorListener aiServiceErrorListener() {
        return event -> {
            var context = event.invocationContext();
            log.error("[Observability] AI服务调用失败，interface={}，method={}，invocationId={}，error={}",
                    context.interfaceName(),
                    context.methodName(),
                    context.invocationId(),
                    event.error().getMessage(),
                    event.error());
        };
    }

    /**
     * 工具执行监听器
     */
    @Bean
    public ToolExecutedEventListener toolExecutedEventListener() {
        return event -> {
            var context = event.invocationContext();
            log.info("[Observability] 工具执行完成，interface={}，method={}，invocationId={}",
                    context.interfaceName(),
                    context.methodName(),
                    context.invocationId());
        };
    }
}
