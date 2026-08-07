package com.westart.ai.westart.tool.service.impl;

import com.google.gson.Gson;
import com.westart.ai.westart.tool.entity.ToolEntity;
import com.westart.ai.westart.tool.domain.ToolType;
import com.westart.ai.westart.tool.repository.ToolRepository;
import com.westart.ai.westart.tool.service.ToolSearchService;
import com.westart.ai.westart.tool.runtime.ToolDescriptionNormalizer;
import com.westart.ai.westart.tool.runtime.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
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
    private final ToolDescriptionNormalizer toolDescriptionNormalizer;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<ToolEntity> toolEmbeddingStore;
    private final ToolRepository toolRepository;
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
                .maxResults(3)
                .minScore(0.0)
                .build();

        return toolEmbeddingStore.search(request)
                .matches()
                .stream()
                .map(EmbeddingMatch::embedded)
                .toList();
    }

    /**
     * 应用启动完成后，将已注册的本地工具和MCP工具写入向量数据库。
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
            List<String> activeIds = toolEntities.stream()
                    .map(ToolEntity::id)
                    .toList();
            toolRepository.deleteInactiveTools(activeIds);
            log.info("工具向量初始化完成，工具数量：{}", toolEntities.size());
        } catch (RuntimeException e) {
            log.error("工具向量初始化失败，工具数量：{}", toolEntities.size(), e);
            throw new IllegalStateException("工具向量初始化失败", e);
        }
    }

    /**
     * 将注册中心中的本地工具和MCP工具转换为工具实体。
     *
     * @return 待写入向量数据库的工具实体列表
     */
    private List<ToolEntity> buildToolEntities() {
        List<ToolEntity> toolEntities = new ArrayList<>();
        toolRegistry.localTools().values().forEach(tools ->
                tools.forEach(tool -> toolEntities.add(toToolEntity(
                        tool.toolSpecification(),
                        ToolType.LOCAL,
                        LOCAL_ID_PREFIX))));
        toolRegistry.mcpTools().values().forEach(tools ->
                tools.forEach(specification ->
                        toolEntities.add(toToolEntity(
                                specification,
                                ToolType.MCP,
                                MCP_ID_PREFIX))));
        return toolEntities;
    }

    /**
     * 将工具定义转换为工具实体。
     *
     * @param specification 工具定义
     * @param toolType 工具类型
     * @param idPrefix 工具主键前缀
     * @return 工具实体
     */
    private ToolEntity toToolEntity(
            ToolSpecification specification,
            ToolType toolType,
            String idPrefix) {
        String toolName = specification.name();
        String description = toolDescriptionNormalizer.normalize(specification);
        String inputSchema = specification.parameters() == null
                ? EMPTY_INPUT_SCHEMA
                : gson.toJson(specification.parameters());
        return new ToolEntity(
                stableId(idPrefix + toolName),
                toolType,
                toolName,
                description,
                inputSchema);
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
