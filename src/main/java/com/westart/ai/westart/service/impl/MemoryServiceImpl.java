package com.westart.ai.westart.service.impl;

import com.westart.ai.westart.entity.UserMemory;
import com.westart.ai.westart.mapper.impl.UserMemoryMapperImpl;
import com.westart.ai.westart.service.ChatHistoryService;
import com.westart.ai.westart.service.MemoryService;
import com.westart.ai.westart.service.ai.MemoryAssistant;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 聊天记忆服务实现。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemoryServiceImpl implements MemoryService {

    private static final String ROLE_USER = "USER";

    /**
     * 当前阶段每个用户唯一的完整画像键。
     *
     * <p>数据库以wechat_user_id区分用户，以该固定键定位用户唯一画像行。</p>
     */
    private static final String USER_PROFILE_MEMORY_KEY = "user_profile";

    private final MemoryAssistant memoryAssistant;
    private final ObjectMapper objectMapper;
    private final UserMemoryMapperImpl userMemoryMapper;
    private final ChatHistoryService chatHistoryService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 当前一对一微信机器人直接使用from_user_id作为稳定记忆ID。
     * sessionId只管理扫码连接，不能参与聊天记忆计算。
     *
     * @param wechatUserId 微信消息发送者的from_user_id
     * @return 稳定的聊天记忆ID
     */
    @Override
    public String resolveMemoryId(String wechatUserId) {
        if (StringUtils.isBlank(wechatUserId)) {
            throw new IllegalArgumentException("微信用户ID不能为空");
        }
        return wechatUserId;
    }

    /**
     * 串联一个Stream批次的历史持久化、画像分析和消费确认流程。
     */
    @Override
    public void processMemoryBatch(List<ChatHistoryService.StreamMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        log.info("开始处理聊天记忆批次，messageCount={}", messages.size());
        try {
            chatHistoryService.saveMessageBatch(messages);

            List<String> messageIds = messages.stream()
                    .map(ChatHistoryService.StreamMessage::messageId)
                    .toList();
            Set<String> unprocessedMessageIds = Set.copyOf(
                    chatHistoryService.findUnprocessedMessageIds(messageIds));
            List<ChatHistoryService.StreamMessage> unprocessedMessages = messages.stream()
                    .filter(message -> unprocessedMessageIds.contains(message.messageId()))
                    .toList();
            if (unprocessedMessages.isEmpty()) {
                log.info("聊天记忆批次已全部处理过，跳过画像分析，messageCount={}",
                        messages.size());
                return;
            }

            List<ChatHistoryService.StreamMessage> candidateMessages =
                    filterUserProfileMessages(unprocessedMessages);
            List<String> profileContents = candidateMessages.isEmpty()
                    ? Collections.emptyList()
                    : summarizeUserProfile(candidateMessages);

            transactionTemplate.executeWithoutResult(status -> {
                if (!candidateMessages.isEmpty()) {
                    synchronizeUserProfile(candidateMessages, profileContents);
                }
                chatHistoryService.markMessagesMemoryProcessed(
                        unprocessedMessages.stream()
                                .map(ChatHistoryService.StreamMessage::messageId)
                                .toList());
            });

            log.info(
                    "聊天记忆批次处理完成，messageCount={}，candidateCount={}",
                    messages.size(),
                    candidateMessages.size());
        } catch (RuntimeException exception) {
            log.error(
                    "聊天记忆批次处理失败，当前批次不执行后续确认，messageCount={}",
                    messages.size(),
                    exception);
            throw exception;
        }
    }

    /**
     * 使用第一阶段模型筛选用户画像候选消息，并校验模型返回的消息引用。
     */
    @Override
    public List<ChatHistoryService.StreamMessage> filterUserProfileMessages(
            List<ChatHistoryService.StreamMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, ChatHistoryService.StreamMessage> userMessageMap = new LinkedHashMap<>();
        for (ChatHistoryService.StreamMessage message : messages) {
            if (message != null
                    && ROLE_USER.equals(message.role())
                    && !StringUtils.isBlank(message.messageId())) {
                userMessageMap.putIfAbsent(message.messageId(), message);
            }
        }
        if (userMessageMap.isEmpty()) {
            log.info("当前Stream批次不包含用户消息，跳过第一阶段记忆分析，messageCount={}",
                    messages.size());
            return Collections.emptyList();
        }

        MemoryAssistant.AnalysisResult analysisResult;
        try {
            analysisResult = memoryAssistant.filterUserProfileMessages(toAnalysisJson(messages));
        } catch (RuntimeException exception) {
            log.error("第一阶段记忆模型调用失败，messageCount={}", messages.size(), exception);
            throw new IllegalStateException("第一阶段记忆模型调用失败", exception);
        }

        List<MemoryAssistant.TaggedMessage> taggedMessages = analysisResult == null
                ? Collections.emptyList()
                : analysisResult.messages();
        if (taggedMessages == null || taggedMessages.isEmpty()) {
//            log.info("第一阶段记忆分析完成，messageCount={}，candidateCount=0", messages.size());
            return Collections.emptyList();
        }

        Map<String, ChatHistoryService.StreamMessage> candidateMessageMap = new LinkedHashMap<>();
        int invalidResultCount = 0;
        for (MemoryAssistant.TaggedMessage taggedMessage : taggedMessages) {
            if (taggedMessage == null || !ROLE_USER.equals(taggedMessage.memoryTag())) {
                invalidResultCount++;
                continue;
            }
            ChatHistoryService.StreamMessage userMessage =
                    userMessageMap.get(taggedMessage.messageId());
            if (userMessage == null) {
                invalidResultCount++;
                continue;
            }
            candidateMessageMap.putIfAbsent(userMessage.messageId(), userMessage);
        }

        return List.copyOf(candidateMessageMap.values());
    }

    /**
     * 将候选用户消息和已有画像交给第二阶段模型，生成完整用户画像内容。
     */
    @Override
    public List<String> summarizeUserProfile(
            List<ChatHistoryService.StreamMessage> candidateMessages) {
        if (candidateMessages == null || candidateMessages.isEmpty()) {
            return Collections.emptyList();
        }

        String wechatUserId = resolveCandidateWechatUserId(candidateMessages);
        List<UserMemory> existingMemories = getUserMemories(wechatUserId);
        String candidateMessagesJson = toAnalysisJson(candidateMessages);
        String existingMemoriesJson = toJson(
                existingMemories.stream()
                        .map(UserMemory::getContent)
                        .filter(content -> !StringUtils.isBlank(content))
                        .toList(),
                "已有用户画像");

        MemoryAssistant.ProfileResult profileResult;
        try {
            profileResult = memoryAssistant.summarizeUserProfile(
                    candidateMessagesJson,
                    existingMemoriesJson);
        } catch (RuntimeException exception) {
            log.error(
                    "第二阶段用户画像模型调用失败，candidateCount={}，existingMemoryCount={}",
                    candidateMessages.size(),
                    existingMemories.size(),
                    exception);
            throw new IllegalStateException("第二阶段用户画像模型调用失败", exception);
        }

        List<String> profileMemories = profileResult == null
                ? Collections.emptyList()
                : profileResult.memories();
        if (profileMemories == null || profileMemories.isEmpty()) {
            log.info(
                    "第二阶段用户画像总结完成，candidateCount={}，profileCount=0",
                    candidateMessages.size());
            return Collections.emptyList();
        }

        List<String> profileContents = profileMemories.stream()
                .filter(memory -> memory != null && !StringUtils.isBlank(memory))
                .map(String::trim)
                .distinct()
                .toList();
        log.info(
                "第二阶段用户画像总结完成，candidateCount={}，profileCount={}",
                candidateMessages.size(),
                profileContents.size());
        return profileContents;
    }

    /**
     * 使用后端固定的memoryKey同步该用户的完整画像。
     */
    @Override
    public void synchronizeUserProfile(
            List<ChatHistoryService.StreamMessage> candidateMessages,
            List<String> profileContents) {
        if (candidateMessages == null || candidateMessages.isEmpty()) {
            throw new IllegalArgumentException("画像候选消息不能为空");
        }
        String wechatUserId = resolveCandidateWechatUserId(candidateMessages);
        List<String> normalizedProfileContents = profileContents == null
                ? Collections.emptyList()
                : profileContents.stream()
                        .filter(content -> !StringUtils.isBlank(content))
                        .map(String::trim)
                        .distinct()
                        .toList();

        if (normalizedProfileContents.isEmpty()) {
            int deletedRows = userMemoryMapper.deleteByWechatUserIdAndMemoryKey(
                    wechatUserId,
                    USER_PROFILE_MEMORY_KEY);
            log.info("完整用户画像为空，已删除长期画像，deletedRows={}", deletedRows);
            return;
        }

        String sourceMessageId = candidateMessages.stream()
                .filter(message -> message.createdAt() != null)
                .max(Comparator.comparing(ChatHistoryService.StreamMessage::createdAt))
                .map(ChatHistoryService.StreamMessage::messageId)
                .orElseThrow(() -> new IllegalArgumentException("画像候选消息缺少有效创建时间"));
        String profileContent = normalizedProfileContents.stream()
                .map(content -> "- " + content)
                .collect(Collectors.joining(System.lineSeparator()));
        saveOrUpdateUserMemory(
                wechatUserId,
                USER_PROFILE_MEMORY_KEY,
                profileContent,
                sourceMessageId);
        log.info("完整用户画像同步完成，profileCount={}", normalizedProfileContents.size());
    }

    /**
     * 查询指定微信用户当前保存的长期记忆。
     */
    @Override
    public List<UserMemory> getUserMemories(String wechatUserId) {
        String memoryId = resolveMemoryId(wechatUserId);
        List<UserMemory> memories = userMemoryMapper.selectByWechatUserIdAndMemoryKey(
                memoryId, USER_PROFILE_MEMORY_KEY);
//        log.info("用户长期记忆查询完成，memoryCount={}", memories.size());
        return List.copyOf(memories);
    }

    /**
     * 将当前用户的长期画像组装为带固定边界的数据上下文。
     */
    @Override
    public String buildUserMemoryContext(String wechatUserId) {
        List<String> memoryContents = getUserMemories(wechatUserId).stream()
                .map(UserMemory::getContent)
                .filter(content -> !StringUtils.isBlank(content))
                .map(String::trim)
                .distinct()
                .toList();
        if (memoryContents.isEmpty()) {
            return "<user_memory>\n暂无已保存的用户长期画像\n</user_memory>";
        }
        return "<user_memory>\n"
                + String.join(System.lineSeparator(), memoryContents)
                + "\n</user_memory>";
    }

    /**
     * 按微信用户ID和memoryKey新增或更新长期记忆。
    */
    @Override
    public void saveOrUpdateUserMemory(
            String wechatUserId,
            String memoryKey,
            String content,
            String sourceMessageId) {
        String memoryId = resolveMemoryId(wechatUserId);
        if (StringUtils.isBlank(memoryKey)) {
            throw new IllegalArgumentException("memoryKey不能为空");
        }
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("用户画像内容不能为空");
        }

        UserMemory userMemory = new UserMemory();
        userMemory.setWechatUserId(memoryId);
        userMemory.setMemoryKey(memoryKey.trim());
        userMemory.setContent(content.trim());
        userMemory.setSourceMessageId(
                StringUtils.isBlank(sourceMessageId) ? null : sourceMessageId.trim());
        userMemoryMapper.upsertUserMemory(userMemory);
        log.info("用户长期记忆保存完成，memoryKey={}", userMemory.getMemoryKey());
    }

    /**
     * 只向记忆模型提供筛选所需字段，不发送Redis Record ID和用户标识。
     */
    private String toAnalysisJson(List<ChatHistoryService.StreamMessage> messages) {
        List<Map<String, Object>> analysisMessages = messages.stream()
                .filter(Objects::nonNull)
                .map(message -> {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("messageId", message.messageId());
                    fields.put("role", message.role());
                    fields.put("content", message.content());
                    fields.put("createdAt", message.createdAt());
                    return fields;
                })
                .toList();
        return toJson(analysisMessages, "聊天消息");
    }

    /**
     * 校验第二阶段候选消息均为同一微信用户的USER消息。
     */
    private String resolveCandidateWechatUserId(
            List<ChatHistoryService.StreamMessage> candidateMessages) {
        String wechatUserId = null;
        for (ChatHistoryService.StreamMessage message : candidateMessages) {
            if (message == null
                    || !ROLE_USER.equals(message.role())
                    || StringUtils.isBlank(message.memoryId())) {
                throw new IllegalArgumentException("画像候选列表只能包含有效的USER消息");
            }
            if (wechatUserId == null) {
                wechatUserId = message.memoryId();
                continue;
            }
            if (!wechatUserId.equals(message.memoryId())) {
                throw new IllegalArgumentException("画像候选列表不能混入不同微信用户的消息");
            }
        }
        return resolveMemoryId(wechatUserId);
    }

    /**
     * 将记忆模型输入序列化为JSON，不在日志中输出原始内容。
     */
    private String toJson(Object value, String dataName) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            log.error("{}序列化失败", dataName, exception);
            throw new IllegalStateException(dataName + "序列化失败", exception);
        }
    }
}
