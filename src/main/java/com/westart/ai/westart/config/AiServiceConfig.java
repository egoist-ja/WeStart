package com.westart.ai.westart.config;

import com.westart.ai.westart.tool.handler.ToolErrorHandler;
import com.westart.ai.westart.wechat.service.ai.WeChatAssistant;
import com.westart.ai.westart.tool.runtime.JsonProcessingToolExecutor;
import com.westart.ai.westart.tool.runtime.ToolCallGuard;
import com.westart.ai.westart.tool.runtime.ToolRegistry;
import com.westart.ai.westart.tool.runtime.ToolResultJsonProcessor;
import com.westart.ai.westart.tool.ToolSearchTool;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.AiServiceTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AiServiceConfig {

    /**
     * 配置微信聊天助手。
     *
     * @param weChatAssistantModel 微信聊天模型
     * @param redisChatMemoryProvider Redis聊天记忆提供器
     * @param toolSearchTool 工具搜索策略
     * @param toolCallGuard 工具重复调用保护器
     * @param toolErrorHandler 工具异常处理器
     * @param mcpToolProvider MCP工具提供器
     * @param toolRegistry 工具注册中心
     * @param toolResultJsonProcessor 工具调用结果JSON处理器
     * @param toolExecutedEventListener 工具执行事件监听器
     * @param retrievalAugmentor 用户主题记忆检索增强器
     * @return 微信聊天助手
     */
    @Bean
    public WeChatAssistant weChatAssistant(
            ChatModel weChatAssistantModel,
            ChatMemoryProvider redisChatMemoryProvider,
            ToolSearchTool toolSearchTool,
            ToolCallGuard toolCallGuard,
            ToolErrorHandler toolErrorHandler,
            McpToolProvider mcpToolProvider,
            ToolRegistry toolRegistry,
            ToolResultJsonProcessor toolResultJsonProcessor,
            ToolExecutedEventListener toolExecutedEventListener,
            RetrievalAugmentor retrievalAugmentor) {
        List<AiServiceTool> localTools = toolRegistry.localAiServiceTools()
                .stream()
                .map(tool -> wrapLocalTool(tool, toolResultJsonProcessor))
                .toList();
        return AiServices.builder(WeChatAssistant.class)
                .chatModel(weChatAssistantModel)
                .chatMemoryProvider(redisChatMemoryProvider)
                .retrievalAugmentor(retrievalAugmentor)
                .tools(localTools)
                .toolProvider(mcpToolProvider)
                .toolSearchStrategy(toolSearchTool)
                .chatRequestTransformer(toolCallGuard::apply)
                .maxToolCallingRoundTrips(10)
                .registerListener(toolExecutedEventListener)
                .toolArgumentsErrorHandler(toolErrorHandler::handleArguments)
                .toolExecutionErrorHandler(toolErrorHandler::handleExecution)
                .build();
    }

    /**
     * 为需要返回模型的本地工具结果添加JSON处理能力。
     *
     * @param tool 本地AI服务工具
     * @param processor 工具调用结果JSON处理器
     * @return 立即返回工具保持不变，其他工具返回包装后的定义
     */
    private AiServiceTool wrapLocalTool(
            AiServiceTool tool,
            ToolResultJsonProcessor processor) {
        if (tool.returnBehavior() == ReturnBehavior.IMMEDIATE) {
            return tool;
        }
        return tool.toBuilder()
                .toolExecutor(new JsonProcessingToolExecutor(
                        tool.toolExecutor(),
                        processor))
                .build();
    }
}
