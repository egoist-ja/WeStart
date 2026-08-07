package com.westart.ai.westart.tool.handler;

import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具调用异常处理器。
 *
 * <p>将工具参数异常和执行异常转换为模型可理解的简短结果，使模型能够
 * 修正参数或停止无效重试，同时避免向模型暴露框架调用参数等内部信息。</p>
 */
@Slf4j
@Component
public class ToolErrorHandler {

    private static final String UNKNOWN_TOOL_NAME = "未知工具";
    private static final String UNKNOWN_ERROR_MESSAGE = "未知异常";

    /**
     * 处理工具参数异常。
     *
     * @param error 参数异常
     * @param context 工具错误上下文
     * @return 供模型理解和修正参数的错误结果
     */
    public ToolErrorHandlerResult handleArguments(
            Throwable error,
            ToolErrorContext context) {
        String toolName = resolveToolName(context);
        String errorMessage = resolveErrorMessage(error);
        log.warn("工具调用参数错误，工具名称={}，原因={}", toolName, errorMessage);
        return new ToolErrorHandlerResult(
                "工具“" + toolName + "”调用参数错误：" + errorMessage
                        + "。请依据工具参数定义修正后再调用。");
    }

    /**
     * 处理工具执行异常。
     *
     * @param error 执行异常
     * @param context 工具错误上下文
     * @return 供模型理解执行失败的错误结果
     */
    public ToolErrorHandlerResult handleExecution(
            Throwable error,
            ToolErrorContext context) {
        String toolName = resolveToolName(context);
        String errorMessage = resolveErrorMessage(error);
        log.warn("工具执行失败，工具名称={}，原因={}", toolName, errorMessage, error);
        return new ToolErrorHandlerResult(
                "工具“" + toolName + "”执行失败：" + errorMessage
                        + "。请勿声称任务已经完成；如若异常可以修正时重试，否则如实告知用户。");
    }

    /**
     * 获取当前调用的工具名称。
     *
     * @param context 工具错误上下文
     * @return 工具名称，无法获取时返回默认名称
     */
    private String resolveToolName(ToolErrorContext context) {
        if (context == null) {
            return UNKNOWN_TOOL_NAME;
        }
        if (context.toolExecutionRequest() != null
                && context.toolExecutionRequest().name() != null
                && !context.toolExecutionRequest().name().isBlank()) {
            return context.toolExecutionRequest().name();
        }
        InvocationContext invocationContext = context.invocationContext();
        if (invocationContext != null
                && invocationContext.methodName() != null
                && !invocationContext.methodName().isBlank()) {
            return invocationContext.methodName();
        }
        return UNKNOWN_TOOL_NAME;
    }

    /**
     * 获取可安全返回给模型的异常消息。
     *
     * @param error 工具异常
     * @return 非空异常消息
     */
    private String resolveErrorMessage(Throwable error) {
        if (error == null
                || error.getMessage() == null
                || error.getMessage().isBlank()) {
            return UNKNOWN_ERROR_MESSAGE;
        }
        return error.getMessage();
    }
}
