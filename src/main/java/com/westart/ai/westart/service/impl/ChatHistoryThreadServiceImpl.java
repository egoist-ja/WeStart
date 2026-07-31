package com.westart.ai.westart.service.impl;

import com.westart.ai.westart.service.ChatHistoryService;
import com.westart.ai.westart.service.ChatHistoryThreadService;
import com.westart.ai.westart.service.MemoryService;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * 聊天历史消费线程管理服务实现。
 *
 * <p>每个微信用户拥有独立的虚拟线程，持续从该用户的Redis Stream中
 * 批量读取消息、写入MySQL并触发用户画像分析。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryThreadServiceImpl implements ChatHistoryThreadService {

    private final ChatHistoryService chatHistoryService;
    private final MemoryService memoryService;
    @Qualifier("wechatUserMessageExecutor")
    private final ExecutorService executorService;

    /**
     * 当前各用户的消费线程任务，键为微信用户ID。
     */
    private final ConcurrentMap<String, Future<?>> userTaskMap = new ConcurrentHashMap<>();

    /**
     * 启动指定用户的消费线程，重复调用不会创建新线程。
     */
    @Override
    public void startUserProcessing(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }

        userTaskMap.computeIfAbsent(userId, ignored -> submitUserTask(userId));
    }

    /**
     * 停止指定用户的消费线程。
     */
    @Override
    public void stopUserProcessing(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }

        Future<?> task = userTaskMap.remove(userId);
        if (task != null) {
            task.cancel(true);
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
    private Future<?> submitUserTask(String userId) {
        try {
            Future<?> task = executorService.submit(() -> processUserStream(userId));
            log.info("用户聊天历史消费线程已启动，userId={}", userId);
            return task;
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
     * 3. 收到新消息后写入MySQL、触发画像分析、ACK确认</p>
     *
     * @param userId 微信用户ID
     */
    private void processUserStream(String userId) {
        try {
            while (!Thread.currentThread().isInterrupted() && isTaskAlive(userId)) {
                try {
                    processOneCycle(userId);
                } catch (RuntimeException exception) {
                    log.error("用户聊天历史消费异常，userId={}，reason={}",
                            userId, exception.getMessage(), exception);
                }
            }
        } finally {
            userTaskMap.remove(userId);
            log.info("用户聊天历史消费线程退出，userId={}", userId);
        }
    }

    /**
     * 处理一个消费周期：先重试Pending消息，再阻塞等待新消息。
     *
     * @param userId 微信用户ID
     */
    private void processOneCycle(String userId) {
        List<ChatHistoryService.StreamMessage> retryMessages =
                chatHistoryService.readUserRetryMessageBatch(userId);
        if (!retryMessages.isEmpty()) {
            memoryService.processMemoryBatch(retryMessages);
            chatHistoryService.acknowledgeUserMessages(userId, retryMessages.stream()
                    .map(ChatHistoryService.StreamMessage::recordId)
                    .toList());
            return;
        }

        List<ChatHistoryService.StreamMessage> messages =
                chatHistoryService.readUserMessageBatch(userId);
        if (messages.isEmpty()) {
            return;
        }

        memoryService.processMemoryBatch(messages);
        chatHistoryService.acknowledgeUserMessages(userId, messages.stream()
                .map(ChatHistoryService.StreamMessage::recordId)
                .toList());
    }

    /**
     * 判断指定用户的消费任务是否仍然存活。
     *
     * @param userId 微信用户ID
     * @return 任务存活且未被取消时返回true
     */
    private boolean isTaskAlive(String userId) {
        Future<?> task = userTaskMap.get(userId);
        return task != null && !task.isCancelled() && !task.isDone();
    }

    /**
     * 应用停止时取消全部消费线程。
     */
    @PreDestroy
    public void destroy() {
        userTaskMap.values().forEach(task -> task.cancel(true));
        userTaskMap.clear();
        log.info("全部用户聊天历史消费线程已停止");
    }
}
