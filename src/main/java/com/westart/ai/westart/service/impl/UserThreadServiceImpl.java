package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.westart.ai.westart.service.UserThreadService;
import com.westart.ai.westart.service.WeChatAgentService;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 用户消息调度服务实现，负责消息入队、按用户分流、批次收集和虚拟线程生命周期管理。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserThreadServiceImpl implements UserThreadService {

    private static final long MESSAGE_BATCH_GAP_MILLIS = 8_000L;
    private static final long COLLECTION_IDLE_TIMEOUT_NANOS =
            TimeUnit.MILLISECONDS.toNanos(MESSAGE_BATCH_GAP_MILLIS);
    private static final int GLOBAL_QUEUE_CAPACITY = 1_024;
    private static final int USER_QUEUE_CAPACITY = 256;

    private final ILinkClient iLinkClient;
    private final WeChatAgentService weChatAgentService;
    private final ExecutorService wechatUserMessageExecutor;
    private final BlockingQueue<IncomingMessage> globalMessageQueue =
            new ArrayBlockingQueue<>(GLOBAL_QUEUE_CAPACITY);
    private final ConcurrentHashMap<String, BlockingQueue<IncomingMessage>> userMessageQueues =
            new ConcurrentHashMap<>();
    private Future<?> messageDispatcherTask;

    /**
     * 启动全局微信消息分发任务。
     */
    @PostConstruct
    public void startMessageDispatcher() {
        try {
            messageDispatcherTask = wechatUserMessageExecutor.submit(this::dispatchMessages);
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("微信消息分发任务启动失败", exception);
        }
    }

    /**
     * 接收iLink SDK拉取到的消息，过滤无效消息并放入全局队列。
     *
     * @param messages iLink拉取到的消息集合
     */
    @Override
    public void handleMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (WeixinMessage message : messages) {
            if (message == null || StringUtils.isBlank(message.getFrom_user_id())) {
                continue;
            }
            enqueueIncomingMessage(message);
        }
    }

    /**
     * 将单条原始微信消息放入全局有界队列。
     *
     * @param message iLink原始微信消息
     */
    private void enqueueIncomingMessage(WeixinMessage message) {
        String userId = message.getFrom_user_id();
        try {
            globalMessageQueue.put(new IncomingMessage(
                    message,
                    resolveSentAtMillis(message),
                    System.nanoTime()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("微信消息入队被中断，userId={}", userId, exception);
        }
    }

    /**
     * 持续读取全局队列，并按用户ID分发到独立队列。
     */
    private void dispatchMessages() {
        log.info("微信消息分发任务已启动");
        try {
            while (!Thread.currentThread().isInterrupted()) {
                IncomingMessage incomingMessage = globalMessageQueue.take();
                String userId = incomingMessage.message().getFrom_user_id();
                BlockingQueue<IncomingMessage> userQueue = userMessageQueues.computeIfAbsent(
                        userId,
                        this::createUserMessageQueue);
                userQueue.put(incomingMessage);
                startTyping(userId);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            log.info("微信消息分发任务已停止");
        }
    }

    /**
     * 为首次出现的用户创建消息队列，并启动对应的虚拟线程任务。
     *
     * @param userId 微信用户ID
     * @return 用户独立消息队列
     */
    private BlockingQueue<IncomingMessage> createUserMessageQueue(String userId) {
        BlockingQueue<IncomingMessage> userQueue =
                new ArrayBlockingQueue<>(USER_QUEUE_CAPACITY);
        try {
            wechatUserMessageExecutor.execute(() -> processUserMessageLoop(userId, userQueue));
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("用户消息处理任务启动失败，userId=" + userId, exception);
        }
        log.info("微信用户消息队列已创建，userId={}", userId);
        return userQueue;
    }

    /**
     * 串行处理指定用户的消息队列，并按发送间隔收集消息批次。
     *
     * @param userId 微信用户ID
     * @param userQueue 用户独立消息队列
     */
    private void processUserMessageLoop(
            String userId,
            BlockingQueue<IncomingMessage> userQueue) {
        log.info("微信用户消息处理任务已启动，userId={}", userId);
        IncomingMessage deferredMessage = null;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                IncomingMessage firstMessage;
                if (deferredMessage == null) {
                    firstMessage = userQueue.take();
                } else {
                    firstMessage = deferredMessage;
                    deferredMessage = null;
                }

                List<IncomingMessage> batchMessages = new ArrayList<>();
                batchMessages.add(firstMessage);
                long lastSentAtMillis = firstMessage.sentAtMillis();
                long lastReceivedAtNanos = firstMessage.receivedAtNanos();

                while (!Thread.currentThread().isInterrupted()) {
                    long elapsedNanos = System.nanoTime() - lastReceivedAtNanos;
                    long remainingNanos = COLLECTION_IDLE_TIMEOUT_NANOS - elapsedNanos;
                    IncomingMessage nextMessage = userQueue.poll(
                            Math.max(remainingNanos, 0L),
                            TimeUnit.NANOSECONDS);
                    if (nextMessage == null) {
                        break;
                    }
                    if (nextMessage.sentAtMillis() - lastSentAtMillis > MESSAGE_BATCH_GAP_MILLIS) {
                        deferredMessage = nextMessage;
                        break;
                    }
                    batchMessages.add(nextMessage);
                    lastSentAtMillis = Math.max(lastSentAtMillis, nextMessage.sentAtMillis());
                    lastReceivedAtNanos = nextMessage.receivedAtNanos();
                }

                log.info(
                        "微信消息批次收集完成，userId={}，messageCount={}",
                        userId,
                        batchMessages.size());
                delegateMessageBatch(userId, userQueue, batchMessages, deferredMessage != null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            log.info("微信用户消息处理任务已停止，userId={}", userId);
        }
    }

    /**
     * 将收集完成的原始消息批次交给微信智能体处理，并刷新输入状态。
     *
     * @param userId 微信用户ID
     * @param userQueue 用户独立消息队列
     * @param batchMessages 完成防抖的内部消息批次
     * @param hasDeferredMessage 是否已有待处理的下一批消息
     */
    private void delegateMessageBatch(
            String userId,
            BlockingQueue<IncomingMessage> userQueue,
            List<IncomingMessage> batchMessages,
            boolean hasDeferredMessage) {
        try {
            List<WeixinMessage> messages = batchMessages.stream()
                    .map(IncomingMessage::message)
                    .toList();
            weChatAgentService.processMessageBatch(userId, messages);
        } catch (RuntimeException exception) {
            log.error("微信用户消息批次处理失败，userId={}", userId, exception);
        } finally {
            refreshTypingState(userId, userQueue, hasDeferredMessage);
        }
    }

    /**
     * 读取用户实际发送时间；SDK未提供时使用当前时间降级。
     *
     * @param message iLink原始微信消息
     * @return 用户发送时间，毫秒
     */
    private long resolveSentAtMillis(WeixinMessage message) {
        Long sentAtMillis = message.getCreate_time_ms();
        if (sentAtMillis != null && sentAtMillis > 0L) {
            return sentAtMillis;
        }
        log.warn(
                "微信消息缺少create_time_ms，使用本地时间降级，messageId={}",
                message.getMessage_id());
        return System.currentTimeMillis();
    }

    /**
     * 消息入队后开启微信输入状态。
     *
     * @param userId 微信用户ID
     */
    private void startTyping(String userId) {
        try {
            iLinkClient.startTyping(userId);
        } catch (IOException | ILinkException exception) {
            log.warn("开启微信输入状态失败，userId={}", userId, exception);
        }
    }

    /**
     * 完成当前批次后刷新微信输入状态。
     *
     * @param userId 微信用户ID
     * @param userQueue 用户独立消息队列
     * @param hasDeferredMessage 是否已有待处理的下一批消息
     */
    private void refreshTypingState(
            String userId,
            BlockingQueue<IncomingMessage> userQueue,
            boolean hasDeferredMessage) {
        try {
            iLinkClient.stopTyping(userId);
        } catch (IOException | ILinkException exception) {
            log.warn("关闭微信输入状态失败，userId={}", userId, exception);
        }

        if (hasDeferredMessage || !userQueue.isEmpty()) {
            startTyping(userId);
        }
    }

    /**
     * 停止消息分发任务并清理全部消息队列。
     */
    @PreDestroy
    public void destroy() {
        if (messageDispatcherTask != null) {
            messageDispatcherTask.cancel(true);
        }
        globalMessageQueue.clear();
        userMessageQueues.values().forEach(BlockingQueue::clear);
        userMessageQueues.clear();
    }

    /**
     * 进入调度队列的单条原始微信消息。
     *
     * @param message iLink原始微信消息
     * @param sentAtMillis 用户实际发送时间
     * @param receivedAtNanos 消息到达时的单调时间
     */
    private record IncomingMessage(
            WeixinMessage message,
            long sentAtMillis,
            long receivedAtNanos) {
    }
}
