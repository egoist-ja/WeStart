package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 工具调用结果JSON处理执行器。
 *
 * 装饰原始工具执行器，统一处理发送给模型的文本结果，同时保留原始
 * 结果对象、错误状态、属性以及图片等非文本内容。
 */
@Slf4j
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
        return processText(request.name(), rawResult);
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
                .map(content -> processContent(request.name(), content))
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
     * @param toolName 工具名称
     * @param content 工具结果内容
     * @return 文本清洗后的内容；非文本内容保持不变
     */
    private Content processContent(String toolName, Content content) {
        if (!(content instanceof TextContent textContent)) {
            return content;
        }
        return TextContent.from(processText(toolName, textContent.text()));
    }

    /**
     * 清洗文本并临时记录清洗前后的结果。
     *
     * @param toolName 工具名称
     * @param rawResult 工具原始文本结果
     * @return 清洗后的文本结果
     */
    private String processText(String toolName, String rawResult) {
        log.info("工具调用结果处理前，工具名称={}，结果={}",
                toolName, rawResult);
        String processedResult = processor.process(rawResult);
        log.info("工具调用结果处理后，工具名称={}，结果={}",
                toolName, processedResult);
        return processedResult;
    }
}
