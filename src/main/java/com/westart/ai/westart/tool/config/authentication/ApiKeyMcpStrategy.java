package com.westart.ai.westart.tool.config.authentication;

import com.westart.ai.westart.tool.config.McpProperties;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpHeadersSupplier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API Key 请求头类型的 MCP 认证策略。
 *
 * <p>向请求头写入 {@code apiKey} 字段，适用于途牛等使用自定义 apiKey
 * 请求头认证的 MCP 服务。</p>
 */
public final class ApiKeyMcpStrategy implements McpHeadersSupplier {

    private static final String API_KEY_HEADER = "apiKey";

    private final McpProperties.ServerConfig serverConfig;

    /**
     * 创建 API Key 请求头认证策略。
     *
     * @param serverConfig 当前 MCP 服务配置
     */
    public ApiKeyMcpStrategy(McpProperties.ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
    }

    /**
     * 生成携带 apiKey 请求头的 MCP 请求头。
     *
     * @param context MCP 调用上下文，API Key 认证无需使用该参数
     * @return MCP 请求头
     */
    @Override
    public Map<String, String> apply(McpCallContext context) {
        String apiKey = serverConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("apiKey MCP apiKey 不能为空");
        }
        Map<String, String> headers = new LinkedHashMap<>(serverConfig.getExtraHeaders());
        headers.put(API_KEY_HEADER, apiKey.trim());
        return headers;
    }
}
