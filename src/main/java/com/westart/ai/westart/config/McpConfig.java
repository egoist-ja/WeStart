package com.westart.ai.westart.config;

import com.westart.ai.westart.config.strategy.BearerMcpStrategy;
import com.westart.ai.westart.config.strategy.FlyaiMcpStrategy;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MCP 配置。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class McpConfig {

    private final McpProperties mcpProperties;

    /**
     * 注册 MCP 工具提供者。
     *
     * @return MCP 工具提供者
     */
    @Bean
    public McpToolProvider mcpToolProvider() {
        List<McpClient> mcpClients = new ArrayList<>();
        mcpProperties.getServers().forEach((serverKey, serverConfig) -> {
            if (serverConfig == null || !serverConfig.isEnabled()) {
                return;
            }
            try {
                McpHeadersSupplier headersStrategy = createHeadersStrategy(serverConfig);
                McpTransport transport = StreamableHttpMcpTransport.builder()
                        .url(serverConfig.getUrl())
                        .customHeaders(headersStrategy)
                        .logRequests(serverConfig.isLogRequests())
                        .logResponses(serverConfig.isLogResponses())
                        .build();
                McpClient mcpClient = DefaultMcpClient.builder()
                        .key(serverKey)
                        .transport(transport)
                        .autoHealthCheckInterval(Duration.ofSeconds(30))
                        .reconnectInterval(Duration.ofSeconds(5))
                        .initializationTimeout(Duration.ofSeconds(30))
                        .pingTimeout(Duration.ofSeconds(10))
                        .build();
                mcpClients.add(mcpClient);
                log.info("MCP 客户端注册成功，serverKey: {}", serverKey);
            } catch (RuntimeException e) {
                log.error("MCP 客户端注册失败，serverKey: {}", serverKey, e);
            }
        });
        return McpToolProvider.builder()
                .mcpClients(mcpClients)
                .build();
    }

    /**
     * 根据认证类型创建 MCP 请求头策略。
     *
     * @param serverConfig MCP 服务配置
     * @return MCP 请求头策略
     */
    private McpHeadersSupplier createHeadersStrategy(McpProperties.ServerConfig serverConfig) {
        String authType = serverConfig.getAuthType();
        if (authType == null || authType.isBlank()) {
            throw new IllegalArgumentException("MCP authType 不能为空");
        }
        return switch (authType.trim().toLowerCase(Locale.ROOT)) {
            case "bearer" -> new BearerMcpStrategy(serverConfig);
            case "flyai" -> new FlyaiMcpStrategy(serverConfig);
            default -> throw new IllegalArgumentException(
                    "不支持的 MCP authType: " + authType);
        };
    }
}
