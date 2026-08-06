package com.westart.ai.westart.config;

import com.westart.ai.westart.config.strategy.ApiKeyMcpStrategy;
import com.westart.ai.westart.config.strategy.BearerMcpStrategy;
import com.westart.ai.westart.config.strategy.FlyaiMcpStrategy;
import com.westart.ai.westart.service.tool.JsonProcessingToolExecutor;
import com.westart.ai.westart.service.tool.ToolRegistry;
import com.westart.ai.westart.service.tool.ToolResultJsonProcessor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;
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
import java.util.Map;

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
    public McpToolProvider mcpToolProvider(
            List<McpClientListener> clientListeners,
            ToolRegistry toolRegistry,
            ToolResultJsonProcessor toolResultJsonProcessor) {
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
                        .addListeners(clientListeners)
                        .autoHealthCheckInterval(Duration.ofSeconds(30))
                        .reconnectInterval(Duration.ofSeconds(5))
                        .initializationTimeout(Duration.ofSeconds(30))
                        .pingTimeout(Duration.ofSeconds(10))
                        .build();
                List<ToolSpecification> mcpTools = mcpClient.listTools()
                        .stream()
                        .map(toolSpecification -> mapToolSpecification(
                                mcpClient,
                                toolSpecification))
                        .toList();
                mcpClients.add(mcpClient);
                toolRegistry.registerMcpTools(serverKey, mcpTools);
                log.info("MCP客户端注册成功，serverKey={}，工具数量={}",
                        serverKey, mcpTools.size());
            } catch (RuntimeException e) {
                log.error("MCP 客户端注册失败，serverKey: {}", serverKey, e);
            }
        });
        return McpToolProvider.builder()
                .mcpClients(mcpClients)
                .toolSpecificationMapper(this::mapToolSpecification)
                .toolWrapper(toolExecutor -> new JsonProcessingToolExecutor(
                        toolExecutor,
                        toolResultJsonProcessor))
                .build();
    }

    /**
     * 为MCP工具添加客户端命名空间，避免不同服务中的同名工具冲突。
     *
     * @param mcpClient MCP客户端
     * @param toolSpecification MCP原始工具定义
     * @return 使用客户端标识限定名称的工具定义
     */
    private ToolSpecification mapToolSpecification(
            McpClient mcpClient,
            ToolSpecification toolSpecification) {
        return toolSpecification.toBuilder()
                .name(mcpClient.key() + "__" + toolSpecification.name())
                .build();
    }

    /**
     * 根据认证类型创建 MCP 请求头策略。
     *
     * @param serverConfig MCP 服务配置
     * @return MCP 请求头策略
     */
    private McpHeadersSupplier createHeadersStrategy(
            McpProperties.ServerConfig serverConfig) {
        String authType = serverConfig.getAuthType();
        if (authType == null || authType.isBlank()) {
            throw new IllegalArgumentException("MCP authType 不能为空");
        }
        return switch (authType.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> context -> Map.of();
            case "bearer" -> new BearerMcpStrategy(serverConfig);
            case "flyai" -> new FlyaiMcpStrategy(serverConfig);
            case "apikey" -> new ApiKeyMcpStrategy(serverConfig);
            default -> throw new IllegalArgumentException(
                    "不支持的 MCP authType: " + authType);
        };
    }

    @Bean
    public List<McpClientListener> clientListeners() {
        List<McpClientListener> mcpClientListeners = new ArrayList<>();
        mcpClientListeners.add(new McpClientListener() {

            @Override
            public void afterInitialize(McpCallContext context) {
                log.info("MCP初始化成功");
            }

            @Override
            public void beforeExecuteTool(McpCallContext context) {
                InvocationContext invocationContext = context.invocationContext();
                log.info("准备执行工具:{}",invocationContext.methodName());
            }

        });
        return mcpClientListeners;
    }
}
