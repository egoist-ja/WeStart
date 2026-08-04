package com.westart.ai.westart.config;

import com.westart.ai.westart.service.ai.WeChatAssistant;
import com.westart.ai.westart.service.tool.DailyHotTool;
import com.westart.ai.westart.service.tool.FileFormatTool;
import com.westart.ai.westart.service.tool.FoodOrderTool;
import com.westart.ai.westart.service.tool.GaodeMapTool;
import com.westart.ai.westart.service.tool.ImageGenerateTool;
import com.westart.ai.westart.service.tool.LogisticsTool;
import com.westart.ai.westart.service.tool.ToolCallGuard;
import com.westart.ai.westart.service.tool.ToolSearchTool;
import com.westart.ai.westart.service.tool.WeatherTool;
import com.westart.ai.westart.service.tool.WebSearchTool;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiServiceConfig {

    /**
     * 配置微信聊天助手。
     *
     * @param weChatAssistantModel 微信聊天模型
     * @param redisChatMemoryProvider Redis聊天记忆提供器
     * @param toolSearchTool 工具搜索策略
     * @param toolCallGuard 工具重复调用保护器
     * @param mcpToolProvider MCP工具提供器
     * @param weatherTool 天气工具
     * @param logisticsTool 物流工具
     * @param webSearchTool 网络搜索工具
     * @param gaodeMapTool 高德地图工具
     * @param imageGenerateTool 图片生成工具
     * @param dailyHotTool 每日热点工具
     * @param fileFormatTool 文件格式工具
     * @param foodOrderTool 餐饮工具
     * @return 微信聊天助手
     */
    @Bean
    public WeChatAssistant weChatAssistant(
            ChatModel weChatAssistantModel,
            ChatMemoryProvider redisChatMemoryProvider,
            ToolSearchTool toolSearchTool,
            ToolCallGuard toolCallGuard,
            McpToolProvider mcpToolProvider,
            WeatherTool weatherTool,
            LogisticsTool logisticsTool,
            WebSearchTool webSearchTool,
            GaodeMapTool gaodeMapTool,
            ImageGenerateTool imageGenerateTool,
            DailyHotTool dailyHotTool,
            FileFormatTool fileFormatTool
            /**FoodOrderTool foodOrderTool8*/, ToolExecutedEventListener toolExecutedEventListener) {
        return AiServices.builder(WeChatAssistant.class)
                .chatModel(weChatAssistantModel)
                .chatMemoryProvider(redisChatMemoryProvider)
                .tools(
                        weatherTool,
                        logisticsTool,
                        webSearchTool,
                        gaodeMapTool,
                        imageGenerateTool,
                        dailyHotTool,
                        fileFormatTool)
                .toolProvider(mcpToolProvider)
                .toolSearchStrategy(toolSearchTool)
                .chatRequestTransformer(toolCallGuard::apply)
                .maxToolCallingRoundTrips(10)
                .registerListener(toolExecutedEventListener)
                .build();
    }
}
