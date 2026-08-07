package com.westart.ai.westart.tool.config.authentication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.westart.ai.westart.tool.config.McpProperties;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpHeadersSupplier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞猪 MCP 请求头生成策略。
 *
 * <p>该策略根据当前 JSON-RPC 请求内容生成动态签名，并与配置中的扩展请求头合并。
 */
public final class FlyaiMcpStrategy implements McpHeadersSupplier {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpProperties.ServerConfig serverConfig;

    /**
     * 创建飞猪 MCP 请求头策略。
     *
     * @param serverConfig 当前 MCP 服务配置
     */
    public FlyaiMcpStrategy(McpProperties.ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
    }

    /**
     * 生成当前飞猪 MCP 调用所需的请求头。
     *
     * @param context MCP 调用上下文
     * @return 飞猪 MCP 请求头
     */
    @Override
    public Map<String, String> apply(McpCallContext context) {
        if (context == null || context.message() == null) {
            return Map.copyOf(serverConfig.getExtraHeaders());
        }
        try {
            String requestBody = OBJECT_MAPPER.writeValueAsString(context.message());
            Map<String, String> headers = new LinkedHashMap<>(serverConfig.getExtraHeaders());
            headers.putAll(FlyAiHeaders.of(
                    serverConfig.getUrl(), requestBody, serverConfig.getApiKey()));
            return headers;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化飞猪 MCP 请求失败", e);
        }
    }
}
