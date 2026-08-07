package com.westart.ai.westart.memory.service.impl;

import com.westart.ai.westart.memory.dto.MessageDTO;
import com.westart.ai.westart.memory.service.ChatHistoryConsumerService;
import com.westart.ai.westart.memory.service.ChatHistoryService;
import com.westart.ai.westart.memory.service.MessageFilterService;
import com.westart.ai.westart.memory.service.UserTopicMemoryService;
import com.westart.ai.westart.memory.service.UserProfileService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天历史消费与长期记忆编排实现。
 *
 * <p>按用户持续消费Redis Stream，完成公共过滤后并行更新用户画像和主题记忆。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryConsumerServiceImpl implements ChatHistoryConsumerService {

    private final ChatHistoryService chatHistoryService;
    private final UserProfileService userProfileService;
    private final MemoryBatchPolicy memoryBatchPolicy;
    private final MessageFilterService messageFilterService;
    private final UserTopicMemoryService topicMemoryService;
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
        chatHistoryService.findRegisteredHistoryUserIds().forEach(this::startConsuming);
        log.info("用户聊天历史消费恢复完成，activeUserCount={}", userTaskMap.size());
    }

    /**
     * 启动指定用户的聊天历史消费，重复调用不会创建新任务。
     *
     * @param userId 微信用户ID
     */
    @Override
    public void startConsuming(String userId) {
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
     * 过滤一个消息批次，并行更新用户画像和主题记忆，全部成功后确认消费。
     *
     * @param userId 微信用户ID
     */
    private void processAndAcknowledge(
            String userId, List<ChatHistoryService.StreamMessage> messages) {
        List<MessageDTO> messageDTOs = messages.stream()
                .map(ChatHistoryService.StreamMessage::toMessageDTO)
                .toList();
        List<MessageDTO> filteredMessages = messageFilterService.strictFilter(
                messageFilterService.looseFilter(messageDTOs));
        if (!filteredMessages.isEmpty()) {
            processMemoriesInParallel(filteredMessages);
        }
        log.info(
                "记忆处理完成，准备确认Redis Stream消息，userId={}，messageCount={}，filteredCount={}",
                userId,
                messages.size(),
                filteredMessages.size());
        chatHistoryService.acknowledgeUserMessages(userId, messages.stream()
                .map(ChatHistoryService.StreamMessage::recordId)
                .toList());
    }

    /**
     * 使用虚拟线程并行更新主题记忆和用户画像。
     *
     * <p>等待两个任务全部结束，任一任务失败都会终止本批次确认。</p>
     *
     * @param messages 公共过滤后的消息
     */
    private void processMemoriesInParallel(List<MessageDTO> messages) {
        CompletableFuture<Void> topicMemoryFuture = CompletableFuture.runAsync(
                () -> topicMemoryService.updateTopicMemory(messages), executorService);
        CompletableFuture<Void> userProfileFuture = CompletableFuture.runAsync(
                () -> userProfileService.updateProfile(messages), executorService);
        try {
            CompletableFuture.allOf(topicMemoryFuture, userProfileFuture).join();
        } catch (CompletionException exception) {
            throw new IllegalStateException("并行处理主题记忆和用户画像失败", exception.getCause());
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
