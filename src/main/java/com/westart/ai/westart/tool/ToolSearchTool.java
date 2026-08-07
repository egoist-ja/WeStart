package com.westart.ai.westart.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.westart.ai.westart.tool.entity.ToolEntity;
import com.westart.ai.westart.tool.service.ToolSearchService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.search.ToolSearchRequest;
import dev.langchain4j.service.tool.search.ToolSearchResult;
import dev.langchain4j.service.tool.search.ToolSearchStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于业务工具搜索服务的LangChain4j工具搜索策略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolSearchTool implements ToolSearchStrategy {

    private static final String SEARCH_TOOL_NAME = "tool_search_tool";
    private static final String QUERY_ARGUMENT_NAME = "query";
    private static final String NO_MATCHING_TOOL_MESSAGE = "没有找到符合需求的工具";

    private final ToolSearchService toolSearchService;

    /**
     * 返回向大模型暴露的工具搜索工具定义。
     *
     * @param invocationContext AI服务调用上下文
     * @return 工具搜索工具定义
     */
    @Override
    public List<ToolSpecification> getToolSearchTools(InvocationContext invocationContext) {
        return List.of(ToolSpecification.builder()
                .name(SEARCH_TOOL_NAME)
                .description("动态工具发现入口。每次收到新的用户请求时，先核对当前可见工具是否明确支持所需的业务对象、操作和结果；缺少任何必要能力时，调用本工具搜索缺失能力。领域相关不代表能力匹配，例如地点搜索工具不能查询门店菜单")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty(
                                QUERY_ARGUMENT_NAME,
                                "按照“业务对象或服务领域 + 操作能力 + 预期结果”描述缺失的工具能力。可以包含餐饮、酒店、饮品等用于区分工具的业务语义，不得包含具体地址、城市、日期、预算、第几家等执行值。例如：查询指定麦当劳门店的菜单商品、规格、价格和可售状态")
                        .required(QUERY_ARGUMENT_NAME)
                        .build())
                .build());
    }

    /**
     * 根据模型生成的查询语句搜索工具，并转换为框架可识别的工具名称。
     *
     * @param toolSearchRequest 工具搜索请求
     * @return 工具搜索结果
     * @throws IllegalArgumentException 工具搜索请求为空时抛出
     * @throws ToolExecutionException 搜索参数无效或工具搜索失败时抛出
     */
    @Override
    public ToolSearchResult search(ToolSearchRequest toolSearchRequest) {
        if (toolSearchRequest == null) {
            throw new IllegalArgumentException("工具搜索请求不能为空");
        }

        String query = extractQuery(
                toolSearchRequest.toolExecutionRequest().arguments());
        List<ToolEntity> matchedTools;
        try {
            matchedTools = toolSearchService.searchTools(query);
        } catch (RuntimeException e) {
            log.error("工具搜索执行失败", e);
            throw new ToolExecutionException("工具搜索暂时不可用", e);
        }

        List<String> foundToolNames = resolveToolNames(
                matchedTools,
                toolSearchRequest.searchableTools());
        String resultMessage = foundToolNames.isEmpty()
                ? NO_MATCHING_TOOL_MESSAGE
                : "已找到可用工具：" + String.join("、", foundToolNames);
        return new ToolSearchResult(foundToolNames, resultMessage);
    }

    /**
     * 从模型生成的工具参数中提取查询语句。
     *
     * @param argumentsJson 工具参数JSON
     * @return 非空的工具查询语句
     * @throws ToolExecutionException 参数格式错误或查询语句为空时抛出
     */
    private String extractQuery(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw new ToolExecutionException("工具搜索参数不能为空");
        }

        try {
            JsonObject arguments = JsonParser.parseString(argumentsJson)
                    .getAsJsonObject();
            JsonElement queryElement = arguments.get(QUERY_ARGUMENT_NAME);
            if (queryElement == null
                    || !queryElement.isJsonPrimitive()
                    || !queryElement.getAsJsonPrimitive().isString()) {
                throw new ToolExecutionException(
                        "工具搜索参数query必须是字符串");
            }

            String query = queryElement.getAsString().trim();
            if (query.isEmpty()) {
                throw new ToolExecutionException(
                        "工具搜索参数query不能为空");
            }
            return query;
        } catch (ToolExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ToolExecutionException("工具搜索参数格式错误", e);
        }
    }

    /**
     * 将业务工具实体转换为当前调用中真实可用的工具名称。
     *
     * @param matchedTools 业务工具搜索结果
     * @param searchableTools 当前调用中的可搜索工具
     * @return 去重后且保持检索顺序的工具名称
     */
    private List<String> resolveToolNames(
            List<ToolEntity> matchedTools,
            List<ToolSpecification> searchableTools) {
        if (matchedTools == null
                || matchedTools.isEmpty()
                || searchableTools == null
                || searchableTools.isEmpty()) {
            return List.of();
        }

        Set<String> searchableToolNames = new LinkedHashSet<>();
        for (ToolSpecification searchableTool : searchableTools) {
            if (searchableTool != null && searchableTool.name() != null) {
                searchableToolNames.add(searchableTool.name());
            }
        }

        Set<String> foundToolNames = new LinkedHashSet<>();
        for (ToolEntity matchedTool : matchedTools) {
            if (matchedTool == null) {
                continue;
            }
            if (searchableToolNames.contains(matchedTool.name())) {
                foundToolNames.add(matchedTool.name());
            }
        }
        return List.copyOf(foundToolNames);
    }
}
