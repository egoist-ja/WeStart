package com.westart.ai.westart.service.impl;

import com.westart.ai.westart.DTO.MessageDTO;
import com.westart.ai.westart.DTO.TopicMemorySummaryDTO;
import com.westart.ai.westart.adapter.TopicMemoryMessageAdapter;
import com.westart.ai.westart.entity.UserTopicMemoryEntity;
import com.westart.ai.westart.repository.TopicMemoryRepository;
import com.westart.ai.westart.service.ChatHistoryService;
import static com.westart.ai.westart.service.ChatHistoryService.ROLE_USER;
import com.westart.ai.westart.service.ChatHistoryThreadService;
import com.westart.ai.westart.service.MemoryService;
import com.westart.ai.westart.service.TopicMemoryService;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天历史消费线程管理服务实现。
 *
 * <p>每个微信用户拥有独立的虚拟线程，持续从该用户的Redis Stream中
 * 批量读取消息、处理用户画像与主题记忆，并在全部成功后确认消费。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryThreadServiceImpl implements ChatHistoryThreadService {

    private final ChatHistoryService chatHistoryService;
    private final MemoryService memoryService;
    private final MemoryBatchPolicy memoryBatchPolicy;
    private final TopicMemoryMessageAdapter topicMemoryMessageAdapter;
    private final TopicMemoryService topicMemoryService;
    private final TopicMemoryRepository topicMemoryRepository;
    @Qualifier("wechatUserMessageExecutor")
    private final ExecutorService executorService;

    /**
     * 当前各用户的消费线程任务，键为微信用户ID。
     */
    private final ConcurrentMap<String, UserProcessingTask> userTaskMap =
            new ConcurrentHashMap<>();

    /**
     * 应用启动完成后恢复所有已经登记的用户Stream消费者。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverRegisteredUserProcessing() {
        chatHistoryService.findRegisteredHistoryUserIds().forEach(this::startUserProcessing);
        log.info("用户聊天历史消费恢复完成，activeUserCount={}", userTaskMap.size());
    }

    /**
     * 启动指定用户的消费线程，重复调用不会创建新线程。
     */
    @Override
    public void startUserProcessing(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }

        UserProcessingTask newTask = new UserProcessingTask();
        UserProcessingTask existingTask = userTaskMap.putIfAbsent(userId, newTask);
        if (existingTask != null) {
            return;
        }
        try {
            newTask.bind(submitUserTask(userId, newTask));
        } catch (RuntimeException exception) {
            userTaskMap.remove(userId, newTask);
            throw exception;
        }
    }

    /**
     * 停止指定用户的消费线程。
     */
    @Override
    public void stopUserProcessing(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }

        UserProcessingTask task = userTaskMap.remove(userId);
        if (task != null) {
            task.cancel();
            log.info("用户聊天历史消费线程已停止，userId={}", userId);
        }
    }

    /**
     * 获取当前活跃的消费线程数量。
     */
    @Override
    public int getActiveUserCount() {
        return userTaskMap.size();
    }

    /**
     * 提交用户消费线程任务。
     *
     * @param userId 微信用户ID
     * @return 任务句柄
     */
    private Future<?> submitUserTask(String userId, UserProcessingTask task) {
        try {
            Future<?> future = executorService.submit(() -> processUserStream(userId, task));
            log.info("用户聊天历史消费线程已启动，userId={}", userId);
            return future;
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException(
                    "用户聊天历史消费线程启动失败，userId=" + userId, exception);
        }
    }

    /**
     * 持续消费指定用户Redis Stream中的消息。
     *
     * <p>循环逻辑：
     * 1. 尝试重新领取超时的Pending消息，有则处理
     * 2. 阻塞等待新消息到达（XREADGROUP BLOCK），超时则回到步骤1
     * 3. 收到新消息后处理用户画像与主题记忆，全部成功后ACK确认</p>
     *
     * @param userId 微信用户ID
     */
    private void processUserStream(String userId, UserProcessingTask task) {
        List<ChatHistoryService.StreamMessage> bufferedMessages = new ArrayList<>();
        Instant firstReceivedAt = null;
        Instant lastReceivedAt = null;
        try {
            while (!Thread.currentThread().isInterrupted() && task.isRunning()) {
                try {
                    if (bufferedMessages.isEmpty()) {
                        List<ChatHistoryService.StreamMessage> retryMessages =
                                chatHistoryService.readUserRetryMessageBatch(userId);
                        if (!retryMessages.isEmpty()) {
                            processAndAcknowledge(userId, retryMessages);
                            continue;
                        }
                    }

                    List<ChatHistoryService.StreamMessage> newMessages =
                            chatHistoryService.readUserMessageBatch(userId);
                    Instant now = Instant.now();
                    if (!newMessages.isEmpty()) {
                        if (bufferedMessages.isEmpty()) {
                            firstReceivedAt = now;
                        }
                        bufferedMessages.addAll(newMessages);
                        lastReceivedAt = now;
                    }
                    if (memoryBatchPolicy.shouldProcess(
                            bufferedMessages, firstReceivedAt, lastReceivedAt, now)) {
                        processAndAcknowledge(userId, List.copyOf(bufferedMessages));
                        bufferedMessages.clear();
                        firstReceivedAt = null;
                        lastReceivedAt = null;
                    }
                } catch (RuntimeException exception) {
                    bufferedMessages.clear();
                    firstReceivedAt = null;
                    lastReceivedAt = null;
                    log.error("用户聊天历史消费异常，userId={}，reason={}",
                            userId, exception.getMessage(), exception);
                }
            }
        } finally {
            userTaskMap.remove(userId, task);
            log.info("用户聊天历史消费线程退出，userId={}", userId);
        }
    }

    /**
     * 处理一个消息批次，并在用户画像和主题记忆全部成功后确认消费。
     *
     * @param userId 微信用户ID
     */
    private void processAndAcknowledge(
            String userId, List<ChatHistoryService.StreamMessage> messages) {
        processTopicMemoryBatch(messages);
        log.info(
                "记忆处理与存储完成，准备确认Redis Stream消息，userId={}，messageCount={}",
                userId,
                messages.size());
        chatHistoryService.acknowledgeUserMessages(userId, messages.stream()
                .map(ChatHistoryService.StreamMessage::recordId)
                .toList());
    }

    /**
     * 按固定阶段处理一个主题记忆批次。
     */
    private void processTopicMemoryBatch(List<ChatHistoryService.StreamMessage> messages) {
        List<MessageDTO> messageDTOs = messages.stream()
                .map(ChatHistoryService.StreamMessage::toMessageDTO)
                .toList();
        List<String> selectedMessageIds = topicMemoryService.selectTopicMessageIds(
                topicMemoryMessageAdapter.adaptAll(messageDTOs));
        if (selectedMessageIds.isEmpty()) {
            log.info("主题记忆批次没有候选消息，messageCount={}", messages.size());
            return;
        }

        String summaryJson = topicMemoryService.summarizeTopic(
                messageDTOs,
                selectedMessageIds);
        TopicMemorySummaryDTO validatedSummary = topicMemoryService.validateTopicSummary(
                summaryJson,
                messageDTOs,
                selectedMessageIds);
        if (validatedSummary.chunks().isEmpty()) {
            log.info("第二阶段没有生成有效主题分块，跳过记忆存储，messageCount={}", messages.size());
            return;
        }
        String assembledJson = topicMemoryService.assembleTopicMemoryJson(
                validatedSummary,
                messageDTOs,
                selectedMessageIds);

        List<ChatHistoryService.StreamMessage> selectedUserMessages =
                findSelectedUserMessages(messages, selectedMessageIds);
        List<ChatHistoryService.StreamMessage> profileCandidateMessages =
                memoryService.filterUserProfileMessages(selectedUserMessages);
        List<String> profileContents = profileCandidateMessages.isEmpty()
                ? List.of()
                : memoryService.summarizeUserProfile(profileCandidateMessages);

        persistMemoriesInSequence(
                assembledJson,
                profileCandidateMessages,
                profileContents);
    }

    /**
     * 从第一阶段主题模型选中的原始消息中保留USER消息，供用户画像模型处理。
     */
    private List<ChatHistoryService.StreamMessage> findSelectedUserMessages(
            List<ChatHistoryService.StreamMessage> messages,
            List<String> selectedMessageIds) {
        Set<String> selectedMessageIdSet = new LinkedHashSet<>(selectedMessageIds);
        return messages.stream()
                .filter(message -> message != null
                        && ROLE_USER.equals(message.role())
                        && selectedMessageIdSet.contains(message.messageId()))
                .toList();
    }

    /**
     * 使用两个独立虚拟线程顺序持久化记忆，原消费线程等待完成后继续执行。
     *
     * <p>第一个线程写入MySQL中的主题记忆和用户画像，并取得数据库生成的
     * topicMemoryId；成功后第二个线程使用这些记录写入Milvus。</p>
     */
    private void persistMemoriesInSequence(
            String assembledJson,
            List<ChatHistoryService.StreamMessage> profileCandidateMessages,
            List<String> profileContents) {
        int profileCandidateCount = profileCandidateMessages.size();
        int profileCount = profileContents.size();
        log.info(
                "记忆持久化开始，profileCandidateCount={}，profileCount={}",
                profileCandidateCount,
                profileCount);
        try {
            CompletableFuture<List<UserTopicMemoryEntity>> mysqlFuture = CompletableFuture
                    .supplyAsync(
                            () -> {
                                log.info(
                                        "MySQL记忆写入阶段开始，profileCandidateCount={}，profileCount={}",
                                        profileCandidateCount,
                                        profileCount);
                                List<UserTopicMemoryEntity> persistedTopicMemories =
                                        topicMemoryRepository.saveToMysql(assembledJson);
                                if (!profileCandidateMessages.isEmpty()) {
                                    memoryService.synchronizeUserProfile(
                                            profileCandidateMessages,
                                            profileContents);
                                }
                                log.info(
                                    "MySQL记忆写入阶段完成，profileSyncRequested={}",
                                    !profileCandidateMessages.isEmpty());
                                return persistedTopicMemories;
                            },
                            executorService);
            mysqlFuture
                    .thenAcceptAsync(
                            persistedTopicMemories -> {
                                log.info("Milvus主题记忆写入阶段开始");
                                topicMemoryRepository.saveToMilvus(persistedTopicMemories);
                                log.info("Milvus主题记忆写入阶段完成");
                            },
                            executorService)
                    .join();
            log.info(
                    "记忆持久化全部完成，profileCandidateCount={}，profileCount={}",
                    profileCandidateCount,
                    profileCount);
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            log.error(
                    "记忆持久化失败，profileCandidateCount={}，profileCount={}",
                    profileCandidateCount,
                    profileCount,
                    cause);
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("记忆顺序异步写入失败", cause);
        }
    }

    /**
     * 应用停止时取消全部消费线程。
     */
    @PreDestroy
    public void destroy() {
        userTaskMap.values().forEach(UserProcessingTask::cancel);
        userTaskMap.clear();
        log.info("全部用户聊天历史消费线程已停止");
    }

    /**
     * 单个用户消费任务的生命周期状态。
     */
    private static final class UserProcessingTask {

        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile Future<?> future;

        private void bind(Future<?> submittedFuture) {
            future = submittedFuture;
            if (!running.get()) {
                submittedFuture.cancel(true);
            }
        }

        private boolean isRunning() {
            return running.get();
        }

        private void cancel() {
            running.set(false);
            Future<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(true);
            }
        }
    }
}
