package com.westart.ai.westart.listener;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信助手模型调用监听器。
 */
@Slf4j
@Component
public class AssistantListener implements ChatModelListener {

    /**
     * 是否已经记录首次请求的工具注册快照。
     */
    private final AtomicBoolean toolSnapshotLogged = new AtomicBoolean();

    /**
     * 记录发送给模型的消息和当前可见工具。
     *
     * @param requestContext 模型请求上下文
     */
    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        ChatRequest request = requestContext.chatRequest();
        List<String> tools = request.toolSpecifications().stream()
                .map(this::formatTool)
                .toList();
        if (toolSnapshotLogged.compareAndSet(false, true)) {
            log.info("微信助手工具注册快照，model={}，toolChoice={}，toolCount={}，tools={}",
                    request.modelName(),
                    request.toolChoice(),
                    tools.size(),
                    tools);
        }
        log.info("微信助手模型请求，model={}，messageCount={}，toolChoice={}，toolCount={}，tools={}",
                request.modelName(),
                request.messages().size(),
                request.toolChoice(),
                tools.size(),
                tools);
    }

    /**
     * 格式化发送给模型的工具定义。
     *
     * @param toolSpecification 工具定义
     * @return 工具名称和描述组成的日志文本
     */
    private String formatTool(ToolSpecification toolSpecification) {
        return toolSpecification.name()
                + "(description=" + toolSpecification.description() + ")";
    }

    /**
     * 记录模型回复和模型发起的工具调用。
     *
     * @param responseContext 模型响应上下文
     */
    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        AiMessage aiMessage = responseContext.chatResponse().aiMessage();
        List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
        List<String> toolCalls = toolRequests.stream()
                .map(request -> request.name() + "(" + request.arguments() + ")")
                .toList();
        log.info("微信助手模型响应，model={}，finishReason={}，toolCallCount={}，toolCalls={}",
                responseContext.chatResponse().modelName(),
                responseContext.chatResponse().finishReason(),
                toolCalls.size(),
                toolCalls);
    }

    /**
     * 记录模型调用异常。
     *
     * @param errorContext 模型异常上下文
     */
    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Throwable error = errorContext.error();
        log.error("微信助手模型调用失败，model={}，reason={}",
                errorContext.chatRequest().modelName(),
                error.getMessage(),
                error);
    }
}
