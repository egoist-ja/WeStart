package com.westart.ai.westart.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 配置监听器
 */
@Slf4j
@Configuration
public class ListenersConfig {

    /**
     * 配置模型监听器
     * @return
     */
    @Bean
    public ChatModelListener qwenChatModelListener() {
        return new ChatModelListener() {

            @Override
            public void onRequest(ChatModelRequestContext requestContext) {
                ChatRequest request = requestContext.chatRequest();
                log.info("用户请求已发出");
                log.info("模型名称:{}",request.modelName());
                log.info("用户请求消息:{}", Arrays.toString(request.messages().toArray()));
                log.info("用户请求参数:{}",request.parameters());
            }

            @Override
            public void onResponse(ChatModelResponseContext responseContext) {
                ChatResponse response = responseContext.chatResponse();
                ChatResponseMetadata metadata = response.metadata();
                List<ToolExecutionRequest> toolRequest = null;
                if(response.aiMessage().hasToolExecutionRequests()){
                    toolRequest = response.aiMessage().toolExecutionRequests();
                }
                log.info("模型已回复");
                log.info("模型名:{}",metadata.modelName());
                log.info("模型工具调用列表:{}",toolRequest);
                log.info("消耗token数:{}",metadata.tokenUsage());
                log.info("结束原因:{}",metadata.finishReason());
            }

            @Override
            public void onError(ChatModelErrorContext errorContext) {
                log.error("模型调用错误，错误信息:{}",errorContext.error().getMessage());
            }
        };
    }
}
