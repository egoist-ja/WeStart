package com.westart.ai.westart.service.impl;

import com.google.gson.Gson;
import com.westart.ai.westart.config.McpProperties;
import com.westart.ai.westart.entity.ToolEntity;
import com.westart.ai.westart.entity.ToolType;
import com.westart.ai.westart.infra.ToolEmbeddingStore;
import com.westart.ai.westart.service.ToolSearchService;
import com.westart.ai.westart.service.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.search.ToolSearchStrategy;
import dev.langchain4j.service.tool.search.vector.VectorToolSearchStrategy;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 工具搜索服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolSearchServiceImpl implements ToolSearchService {

    private static final String LOCAL_ID_PREFIX = "LOCAL:";
    private static final String MCP_ID_PREFIX = "MCP:";
    private static final String EMPTY_INPUT_SCHEMA = "{}";

    private final ToolRegistry toolRegistry;
    private final McpProperties mcpProperties;
    private final EmbeddingModel embeddingModel;
    private final ToolEmbeddingStore toolEmbeddingStore;
    private final Gson gson;

    @Override
    public List<ToolEntity> searchTools(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Embedding embedding = embeddingModel.embed(query).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .query(query)
                .queryEmbedding(embedding)
                .maxResults(5)
                .minScore(0.6)
                .build();

        return toolEmbeddingStore.search(request)
                .matches()
                .stream()
                .map(EmbeddingMatch::embedded)
                .toList();
    }

    /**
     * 应用启动完成后，将已注册的本地工具和MCP客户端写入向量数据库。
     *
     * @param event 应用启动完成事件
     * @throws IllegalArgumentException 应用启动完成事件为空时抛出
     * @throws IllegalStateException 工具实体构建、向量生成或批量写入失败时抛出
     */
    @Override
    @EventListener(ApplicationReadyEvent.class)
    public void initializeTools(ApplicationReadyEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("应用启动完成事件不能为空");
        }

        List<ToolEntity> toolEntities = buildToolEntities();
        if (toolEntities.isEmpty()) {
            log.info("没有需要写入向量数据库的工具");
            return;
        }

        try {
            List<TextSegment> segments = toolEntities.stream()
                    .map(tool -> TextSegment.from(tool.description()))
                    .toList();
            List<Embedding> embeddings = embeddingModel
                    .embedAll(segments)
                    .content();
            toolEmbeddingStore.addAll(embeddings, toolEntities);
            log.info("工具向量初始化完成，工具数量：{}", toolEntities.size());
        } catch (RuntimeException e) {
            log.error("工具向量初始化失败，工具数量：{}", toolEntities.size(), e);
            throw new IllegalStateException("工具向量初始化失败", e);
        }
    }

    /**
     * 将注册中心中的本地工具和MCP客户端转换为工具实体。
     *
     * @return 待写入向量数据库的工具实体列表
     */
    private List<ToolEntity> buildToolEntities() {
        List<ToolEntity> toolEntities = new ArrayList<>();
        toolRegistry.localTools().forEach((beanName, tools) ->
                tools.keySet().forEach(specification ->
                        toolEntities.add(toLocalToolEntity(specification))));
        toolRegistry.mcpClients().forEach((clientKey, mcpClient) ->
                toolEntities.add(toMcpEntity(clientKey, mcpClient)));
        return toolEntities;
    }

    /**
     * 将本地工具定义转换为工具实体。
     *
     * @param specification 本地工具定义
     * @return 本地工具实体
     */
    private ToolEntity toLocalToolEntity(ToolSpecification specification) {
        String toolName = specification.name();
        String description = specification.description();
        if (description == null || description.isBlank()) {
            description = toolName;
        }
        String inputSchema = specification.parameters() == null
                ? EMPTY_INPUT_SCHEMA
                : gson.toJson(specification.parameters());
        return new ToolEntity(
                stableId(LOCAL_ID_PREFIX + toolName),
                ToolType.LOCAL,
                toolName,
                description,
                inputSchema);
    }

    /**
     * 将MCP客户端转换为工具实体，不获取和保存远程工具列表。
     *
     * @param clientKey MCP客户端标识
     * @param mcpClient MCP客户端
     * @return MCP工具实体
     */
    private ToolEntity toMcpEntity(String clientKey, McpClient mcpClient) {
        McpProperties.ServerConfig serverConfig =
                mcpProperties.getServers().get(clientKey);
        String serverName = serverConfig == null
                ? clientKey
                : serverConfig.getName();
        if (serverName == null || serverName.isBlank()) {
            serverName = clientKey;
        }

        String instructions = mcpClient.instructions();
        String description = instructions == null || instructions.isBlank()
                ? serverName + " MCP服务"
                : serverName + "：" + instructions;
        return new ToolEntity(
                stableId(MCP_ID_PREFIX + clientKey),
                ToolType.MCP,
                clientKey,
                description,
                EMPTY_INPUT_SCHEMA);
    }

    /**
     * 根据工具业务标识生成长度固定的稳定主键。
     *
     * @param toolKey 工具业务标识
     * @return 由业务标识生成的32位主键
     */
    private String stableId(String toolKey) {
        return UUID.nameUUIDFromBytes(
                        toolKey.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }
}
