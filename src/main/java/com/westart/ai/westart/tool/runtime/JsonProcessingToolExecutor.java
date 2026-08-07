package com.westart.ai.westart.tool.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 工具调用结果JSON处理执行器。
 *
 * 装饰原始工具执行器，统一处理发送给模型的文本结果，同时保留原始
 * 结果对象、错误状态、属性以及图片等非文本内容。
 */
@RequiredArgsConstructor
public class JsonProcessingToolExecutor implements ToolExecutor {

    private final ToolExecutor delegate;
    private final ToolResultJsonProcessor processor;

    /**
     * 执行工具并处理文本结果。
     *
     * @param request 工具执行请求
     * @param memoryId 聊天记忆标识
     * @return 清洗后的工具文本结果
     */
    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String rawResult = delegate.execute(request, memoryId);
        return processor.process(rawResult);
    }

    /**
     * 执行工具并处理发送给模型的文本内容。
     *
     * @param request 工具执行请求
     * @param context 工具调用上下文
     * @return 保留原始结果语义且文本内容经过清洗的执行结果
     */
    @Override
    public ToolExecutionResult executeWithContext(
            ToolExecutionRequest request,
            InvocationContext context) {
        ToolExecutionResult rawResult = delegate.executeWithContext(
                request,
                context);
        List<Content> processedContents = rawResult.resultContents()
                .stream()
                .map(this::processContent)
                .toList();

        return ToolExecutionResult.builder()
                .isError(rawResult.isError())
                .result(rawResult.result())
                .resultContents(processedContents)
                .attributes(rawResult.attributes())
                .build();
    }

    /**
     * 处理单个工具结果内容。
     *
     * @param content 工具结果内容
     * @return 文本清洗后的内容；非文本内容保持不变
     */
    private Content processContent(Content content) {
        if (!(content instanceof TextContent textContent)) {
            return content;
        }
        return TextContent.from(processor.process(textContent.text()));
    }
}
