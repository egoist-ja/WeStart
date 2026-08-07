package com.westart.ai.westart.tool.runtime;

import com.google.gson.JsonParser;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单轮工具调用保护器，负责限制调用次数并阻止无进展的重复调用。
 */
@Slf4j
@Component
public class ToolCallGuard {

    private static final int MAX_TOOL_CALL_COUNT = 10;
    private static final String TOOL_CALL_SIGNATURE_SEPARATOR = "\u0000";

    /**
     * 限制单轮工具调用次数，并阻止相同工具使用相同参数重复调用。
     *
     * @param request 已应用工具可见性策略的模型请求
     * @return 应用工具调用保护后的模型请求
     */
    public ChatRequest apply(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        int currentTurnStartIndex = findCurrentTurnStartIndex(messages);
        Set<String> toolCallSignatures = new HashSet<>();
        Set<ToolExecutionRequest> duplicateRequests = new HashSet<>();
        Set<String> duplicateRequestIds = new HashSet<>();
        Set<String> blockedToolNames = new LinkedHashSet<>();
        int currentToolCallCount = 0;

        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof UserMessage) {
                toolCallSignatures.clear();
                continue;
            }
            if (!(message instanceof AiMessage aiMessage)
                    || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }

            for (ToolExecutionRequest toolRequest
                    : aiMessage.toolExecutionRequests()) {
                boolean currentTurn = index > currentTurnStartIndex;
                if (currentTurn) {
                    currentToolCallCount++;
                }

                String signature = buildToolCallSignature(toolRequest);
                if (toolCallSignatures.add(signature)) {
                    continue;
                }

                duplicateRequests.add(toolRequest);
                if (toolRequest.id() != null && !toolRequest.id().isBlank()) {
                    duplicateRequestIds.add(toolRequest.id());
                }
                if (currentTurn && toolRequest.name() != null) {
                    blockedToolNames.add(toolRequest.name());
                }
            }
        }

        List<ChatMessage> guardedMessages = duplicateRequests.isEmpty()
                ? messages
                : removeDuplicateToolCalls(
                        messages, duplicateRequests, duplicateRequestIds);
        if (currentToolCallCount >= MAX_TOOL_CALL_COUNT) {
            log.warn("单轮工具调用已达到上限，toolCallCount={}", currentToolCallCount);
            return disableTools(request, guardedMessages);
        }
        if (blockedToolNames.isEmpty()) {
            return guardedMessages == messages
                    ? request
                    : request.toBuilder().messages(guardedMessages).build();
        }

        List<ToolSpecification> availableTools = request.toolSpecifications()
                .stream()
                .filter(tool -> tool != null)
                .filter(tool -> !blockedToolNames.contains(tool.name()))
                .toList();
        log.warn("检测到重复工具调用，本轮禁用相关工具，tools={}",
                blockedToolNames);
        if (availableTools.isEmpty()) {
            return disableTools(request, guardedMessages);
        }
        return request.toBuilder()
                .messages(guardedMessages)
                .toolSpecifications(availableTools)
                .toolChoice(ToolChoice.AUTO)
                .build();
    }

    /**
     * 清空当前请求中的可见工具并禁止模型继续调用工具。
     *
     * @param request 模型请求
     * @param messages 清理后的模型消息
     * @return 不包含可见工具的模型请求
     */
    private ChatRequest disableTools(
            ChatRequest request,
            List<ChatMessage> messages) {
        ChatRequestParameters parameters = ChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .toolSpecifications(List.of())
                .toolChoice(ToolChoice.NONE)
                .build();
        return ChatRequest.builder()
                .messages(messages)
                .parameters(parameters)
                .build();
    }

    /**
     * 查找当前用户轮次的起始位置。
     *
     * @param messages 模型请求消息
     * @return 最近一条用户消息的索引；不存在时返回-1
     */
    private int findCurrentTurnStartIndex(List<ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 构建能够唯一表示工具名称及参数的调用签名。
     *
     * @param toolRequest 工具调用请求
     * @return 工具调用签名
     */
    private String buildToolCallSignature(ToolExecutionRequest toolRequest) {
        String toolName = toolRequest.name() == null
                ? ""
                : toolRequest.name();
        String arguments = normalizeArguments(toolRequest.arguments());
        return toolName + TOOL_CALL_SIGNATURE_SEPARATOR + arguments;
    }

    /**
     * 规范化工具参数，消除无意义的JSON格式差异。
     *
     * @param arguments 工具参数
     * @return 规范化后的参数
     */
    private String normalizeArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            return JsonParser.parseString(arguments).toString();
        } catch (RuntimeException e) {
            return arguments.trim();
        }
    }

    /**
     * 从模型请求中移除重复工具调用及对应的执行结果。
     *
     * @param messages 模型请求消息
     * @param duplicateRequests 重复工具调用请求
     * @param duplicateRequestIds 重复工具调用请求标识
     * @return 清理后的模型请求消息
     */
    private List<ChatMessage> removeDuplicateToolCalls(
            List<ChatMessage> messages,
            Set<ToolExecutionRequest> duplicateRequests,
            Set<String> duplicateRequestIds) {
        List<ChatMessage> guardedMessages = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage resultMessage
                    && duplicateRequestIds.contains(resultMessage.id())) {
                continue;
            }
            if (!(message instanceof AiMessage aiMessage)
                    || !aiMessage.hasToolExecutionRequests()) {
                guardedMessages.add(message);
                continue;
            }

            List<ToolExecutionRequest> retainedRequests = aiMessage
                    .toolExecutionRequests()
                    .stream()
                    .filter(toolRequest -> !duplicateRequests.contains(
                            toolRequest))
                    .toList();
            if (retainedRequests.size()
                    == aiMessage.toolExecutionRequests().size()) {
                guardedMessages.add(message);
            } else if (!retainedRequests.isEmpty()
                    || aiMessage.text() != null && !aiMessage.text().isBlank()) {
                guardedMessages.add(aiMessage.toBuilder()
                        .toolExecutionRequests(retainedRequests)
                        .build());
            }
        }
        return List.copyOf(guardedMessages);
    }
}
