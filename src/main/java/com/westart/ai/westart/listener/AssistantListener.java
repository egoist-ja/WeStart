package com.westart.ai.westart.listener;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 微信助手模型调用监听器。
 */
@Slf4j
@Component
public class AssistantListener implements ChatModelListener {

    private static final int MAX_LOG_CONTENT_LENGTH = 2000;

    /**
     * 记录模型请求的基本信息和当前可见工具数量。
     *
     * @param requestContext 模型请求上下文
     */
    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        ChatRequest request = requestContext.chatRequest();
        List<String> visibleToolNames = request.toolSpecifications().stream()
                .filter(Objects::nonNull)
                .map(ToolSpecification::name)
                .toList();
        log.info("微信助手模型请求，模型名称={}，消息数={}，工具选择策略={}，"
                        + "可见工具数={}，可见工具={}",
                request.modelName(),
                request.messages().size(),
                request.toolChoice(),
                visibleToolNames.size(),
                visibleToolNames);
        logLatestToolResults(request.messages());
    }

    /**
     * 记录模型回复和模型发起的工具调用。
     *
     * @param responseContext 模型响应上下文
     */
    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        AiMessage aiMessage = responseContext.chatResponse().aiMessage();
        log.info("微信助手模型响应，模型名称={}，结束原因={}，调用工具数={}",
                responseContext.chatResponse().modelName(),
                responseContext.chatResponse().finishReason(),
                aiMessage.toolExecutionRequests().size());
        for (ToolExecutionRequest toolRequest
                : aiMessage.toolExecutionRequests()) {
            log.info("微信助手工具调用，调用编号={}，工具名称={}，调用参数={}",
                    toolRequest.id(),
                    toolRequest.name(),
                    truncate(toolRequest.arguments()));
        }
    }

    /**
     * 记录模型调用异常。
     *
     * @param errorContext 模型异常上下文
     */
    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Throwable error = errorContext.error();
        log.error("微信助手模型调用失败，模型名称={}，失败原因={}",
                errorContext.chatRequest().modelName(),
                error.getMessage(),
                error);
    }

    /**
     * 记录本次模型请求前刚完成的工具执行结果。
     *
     * @param messages 模型请求消息
     */
    private void logLatestToolResults(List<ChatMessage> messages) {
        int firstResultIndex = messages.size();
        while (firstResultIndex > 0
                && messages.get(firstResultIndex - 1)
                instanceof ToolExecutionResultMessage) {
            firstResultIndex--;
        }

        for (int index = firstResultIndex; index < messages.size(); index++) {
            ToolExecutionResultMessage resultMessage =
                    (ToolExecutionResultMessage) messages.get(index);
            log.info("微信助手工具执行结果，调用编号={}，工具名称={}，执行结果={}",
                    resultMessage.id(),
                    resultMessage.toolName(),
                    truncate(resultMessage.text()));
        }
    }

    /**
     * 截断过长日志内容，防止工具参数或结果占用过多控制台输出。
     *
     * @param content 原始日志内容
     * @return 可安全输出的日志内容
     */
    private String truncate(String content) {
        if (content == null || content.length() <= MAX_LOG_CONTENT_LENGTH) {
            return content;
        }
        return content.substring(0, MAX_LOG_CONTENT_LENGTH)
                + "...(内容已截断，原始长度=" + content.length() + ")";
    }
}
