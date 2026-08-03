package com.westart.ai.westart.service.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.westart.ai.westart.entity.ToolEntity;
import com.westart.ai.westart.entity.ToolType;
import com.westart.ai.westart.service.ToolSearchService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.search.ToolSearchRequest;
import dev.langchain4j.service.tool.search.ToolSearchResult;
import dev.langchain4j.service.tool.search.ToolSearchStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
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
    private static final String MCP_TOOL_NAME_SEPARATOR = "__";
    private static final String NO_MATCHING_TOOL_MESSAGE = "没有找到符合需求的工具";
    private static final String FOUND_TOOLS_ATTRIBUTE = "found_tools";

    private final ToolSearchService toolSearchService;
    private final ToolCallGuard toolCallGuard;

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
                .description("动态工具发现入口。当当前可见业务工具无法满足用户需求时调用。找到可用工具后继续调用对应业务工具；没有找到时再使用模型自身知识回答。在本工具实际返回失败前，不得声称工具不可用")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty(
                                QUERY_ARGUMENT_NAME,
                                "结合当前消息和对话上下文改写出的完整能力需求，包含目标、对象以及已知的关键限制")
                        .required(QUERY_ARGUMENT_NAME)
                        .build())
                .build());
    }

    /**
     * 根据当前对话阶段隔离可见工具并设置工具选择策略。
     *
     * @param request 模型请求
     * @return 应用工具隔离和选择策略后的模型请求
     * @throws IllegalArgumentException 模型请求为空时抛出
     */
    public ChatRequest applyToolPolicy(ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("模型请求不能为空");
        }
        if (request.messages().isEmpty()) {
            return request;
        }

        ToolExecutionResultMessage searchResult =
                findCurrentSearchResult(request.messages());
        ChatRequest policyRequest = searchResult == null
                ? retainToolSearch(request)
                : retainCurrentTools(request, searchResult);
        return toolCallGuard.apply(policyRequest);
    }

    /**
     * 根据记忆中是否已有业务工具设置新请求的工具策略。
     *
     * @param request 模型请求
     * @return 没有业务工具时强制搜索，否则允许复用或继续搜索
     */
    private ChatRequest retainToolSearch(ChatRequest request) {
        List<ToolSpecification> availableTools = request.toolSpecifications()
                .stream()
                .filter(tool -> tool != null)
                .toList();
        boolean businessToolAvailable = availableTools.stream()
                .anyMatch(tool -> !SEARCH_TOOL_NAME.equals(tool.name()));
        if (businessToolAvailable) {
            return request.toBuilder()
                    .toolSpecifications(availableTools)
                    .toolChoice(ToolChoice.AUTO)
                    .build();
        }

        return availableTools.stream()
                .filter(tool -> SEARCH_TOOL_NAME.equals(tool.name()))
                .findFirst()
                .map(tool -> request.toBuilder()
                        .toolSpecifications(List.of(tool))
                        .toolChoice(ToolChoice.REQUIRED)
                        .build())
                .orElse(request);
    }

    /**
     * 搜索刚完成时强制使用业务工具，后续允许复用或继续搜索。
     *
     * @param request 模型请求
     * @param resultMessage 工具搜索结果消息
     * @return 当前轮可见工具经过隔离后的模型请求
     */
    private ChatRequest retainCurrentTools(
            ChatRequest request,
            ToolExecutionResultMessage resultMessage) {
        List<ToolSpecification> availableTools = request.toolSpecifications().stream()
                .filter(tool -> tool != null)
                .toList();
        boolean searchJustCompleted = request.messages().getLast() == resultMessage;
        if (!searchJustCompleted) {
            return request.toBuilder()
                    .toolSpecifications(availableTools)
                    .toolChoice(ToolChoice.AUTO)
                    .build();
        }

        Set<String> foundToolNames = getFoundToolNames(resultMessage);
        List<ToolSpecification> businessTools = availableTools.stream()
                .filter(tool -> foundToolNames.contains(tool.name()))
                .toList();
        if (businessTools.isEmpty()) {
            return disableTools(request);
        }

        return request.toBuilder()
                .toolSpecifications(businessTools)
                .toolChoice(ToolChoice.REQUIRED)
                .build();
    }

    /**
     * 读取当前工具搜索调用发现的工具名称。
     *
     * @param resultMessage 工具搜索结果消息
     * @return 当前搜索发现的工具名称
     */
    private Set<String> getFoundToolNames(
            ToolExecutionResultMessage resultMessage) {
        Object foundTools = resultMessage.attributes().get(
                FOUND_TOOLS_ATTRIBUTE);
        if (!(foundTools instanceof Collection<?> toolNames)) {
            return Set.of();
        }

        Set<String> foundToolNames = new LinkedHashSet<>();
        for (Object toolName : toolNames) {
            if (toolName instanceof String name && !name.isBlank()) {
                foundToolNames.add(name);
            }
        }
        return foundToolNames;
    }

    /**
     * 清空当前请求中的可见工具并禁止模型调用工具。
     *
     * @param request 模型请求
     * @return 不包含可见工具的模型请求
     */
    private ChatRequest disableTools(ChatRequest request) {
        ChatRequestParameters parameters = ChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .toolSpecifications(List.of())
                .toolChoice(ToolChoice.NONE)
                .build();
        return ChatRequest.builder()
                .messages(request.messages())
                .parameters(parameters)
                .build();
    }

    /**
     * 查找最近一条用户消息之后产生的工具搜索结果。
     *
     * @param messages 当前模型请求消息
     * @return 当前轮工具搜索结果；尚未搜索时返回null
     */
    private ToolExecutionResultMessage findCurrentSearchResult(
            List<ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (message instanceof UserMessage) {
                return null;
            }
            if (message instanceof ToolExecutionResultMessage resultMessage
                    && SEARCH_TOOL_NAME.equals(resultMessage.toolName())) {
                return resultMessage;
            }
        }
        return null;
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
            if (matchedTool.type() == ToolType.LOCAL) {
                if (searchableToolNames.contains(matchedTool.name())) {
                    foundToolNames.add(matchedTool.name());
                }
                continue;
            }

            String toolNamePrefix = matchedTool.name()
                    + MCP_TOOL_NAME_SEPARATOR;
            searchableToolNames.stream()
                    .filter(name -> name.startsWith(toolNamePrefix))
                    .forEach(foundToolNames::add);
        }
        return List.copyOf(foundToolNames);
    }
}
