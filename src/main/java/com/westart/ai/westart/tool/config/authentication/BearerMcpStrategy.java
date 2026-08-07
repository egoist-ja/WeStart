package com.westart.ai.westart.tool.config.authentication;

import com.westart.ai.westart.tool.config.McpProperties;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpHeadersSupplier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bearer Token 类型的 MCP 请求头策略。
 *
 * <p>所有使用 Bearer Token 认证的 MCP 服务均可复用该策略。
 */
public final class BearerMcpStrategy implements McpHeadersSupplier {

    private static final String AUTHORIZATION = "Authorization";
    private final McpProperties.ServerConfig serverConfig;

    /**
     * 创建 Bearer Token 请求头策略。
     *
     * @param serverConfig 当前 MCP 服务配置
     */
    public BearerMcpStrategy(McpProperties.ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
    }

    /**
     * 生成 Bearer Token 请求头。
     *
     * @param context MCP 调用上下文，Bearer Token 认证无需使用该参数
     * @return MCP 请求头
     */
    @Override
    public Map<String, String> apply(McpCallContext context) {
        String apiKey = serverConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Bearer MCP apiKey 不能为空");
        }
        Map<String, String> headers = new LinkedHashMap<>(serverConfig.getExtraHeaders());
        headers.put(AUTHORIZATION, "Bearer " + apiKey.trim());
        return headers;
    }
}
