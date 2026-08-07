package com.westart.ai.westart.wechat.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.westart.ai.westart.wechat.dto.ILinkClientSession;
import com.westart.ai.westart.wechat.service.UserMessageService;
import com.westart.ai.westart.wechat.service.UserThreadService;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 用户消息线程服务实现，负责按iLink客户端会话接收和串行处理消息。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserThreadServiceImpl implements UserThreadService {

    private static final int MAX_MESSAGE_BATCH_SIZE = 20;
    private static final long EMPTY_QUEUE_WAIT_MILLIS = 100L;

    private final ILinkClientSessionRegistry sessionRegistry;
    private final UserMessageService userMessageService;
    private final ExecutorService wechatUserMessageExecutor;

    /**
     * 当前应用实例中各会话的消息处理任务，键为会话唯一标识。
     */
    private final ConcurrentMap<String, Future<?>> sessionTaskMap =
            new ConcurrentHashMap<>();

    /**
     * 接收指定iLink客户端拉取到的消息并放入对应会话队列。
     *
     * @param sessionId 会话ID
     * @param messages iLink拉取到的消息集合
     */
    @Override
    public void handleMessages(String sessionId, List<WeixinMessage> messages) {
        if (StringUtils.isBlank(sessionId) || messages == null || messages.isEmpty()) {
            return;
        }

        ILinkClientSession session = sessionRegistry.find(sessionId).orElse(null);
        if (session == null) {
            log.warn("忽略已失效会话收到的微信消息，sessionId={}", sessionId);
            return;
        }

        Queue<WeixinMessage> messageQueue = session.messageQueue();
        for (WeixinMessage message : messages) {
            if (message == null || StringUtils.isBlank(message.getFrom_user_id())) {
                continue;
            }
            messageQueue.offer(message);
        }
    }

    /**
     * 为指定会话启动独立的消息处理虚拟线程。
     *
     * @param sessionId 会话ID
     */
    @Override
    public void startSession(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        ILinkClientSession session = sessionRegistry.getRequired(sessionId);

        sessionTaskMap.computeIfAbsent(sessionId, ignored -> submitSessionTask(session));
    }

    /**
     * 停止指定会话的消息处理任务。
     *
     * @param sessionId 会话ID
     */
    @Override
    public void stopSession(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId不能为空");
        }

        Future<?> sessionTask = sessionTaskMap.remove(sessionId);
        if (sessionTask != null) {
            sessionTask.cancel(true);
        }
    }

    /**
     * 提交指定会话的消息处理任务。
     *
     * @param session 当前iLink客户端会话
     * @return 消息处理任务句柄
     */
    private Future<?> submitSessionTask(ILinkClientSession session) {
        try {
            Future<?> sessionTask = wechatUserMessageExecutor.submit(
                    () -> processSessionMessages(session));
            log.info("微信会话消息处理任务已启动，sessionId={}", session.sessionId());
            return sessionTask;
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException(
                    "微信会话消息处理任务启动失败，sessionId=" + session.sessionId(),
                    exception);
        }
    }

    /**
     * 串行处理指定会话的消息。
     *
     * @param session 当前iLink客户端会话
     */
    private void processSessionMessages(ILinkClientSession session) {
        String sessionId = session.sessionId();
        try {
            while (!Thread.currentThread().isInterrupted()
                    && isCurrentSession(session)) {
                WeixinMessage message = session.messageQueue().poll();
                if (message == null) {
                    TimeUnit.MILLISECONDS.sleep(EMPTY_QUEUE_WAIT_MILLIS);
                    continue;
                }

                String userId = message.getFrom_user_id();
                List<WeixinMessage> messages = collectAvailableMessages(session.messageQueue(), message);
                startTyping(session.client(), userId, sessionId);
                delegateMessageBatch(session, userId, messages);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            log.info("微信会话消息处理任务已停止，sessionId={}", sessionId);
        }
    }

    /**
     * 收集队列中已经积压的消息，不等待后续消息到达。
     *
     * @param messageQueue 当前会话消息队列
     * @param firstMessage 当前批次首条消息
     * @return 不超过批次上限的不可变消息列表
     */
    private List<WeixinMessage> collectAvailableMessages(Queue<WeixinMessage> messageQueue,
             WeixinMessage firstMessage) {
        List<WeixinMessage> messages = new ArrayList<>(MAX_MESSAGE_BATCH_SIZE);
        messages.add(firstMessage);
        while (messages.size() < MAX_MESSAGE_BATCH_SIZE) {
            WeixinMessage message = messageQueue.poll();
            if (message == null) {
                break;
            }
            messages.add(message);
        }
        return List.copyOf(messages);
    }

    /**
     * 将消息批次交给用户消息服务处理，并刷新微信输入状态。
     *
     * @param session 当前iLink客户端会话
     * @param userId 微信用户ID
     * @param messages 待处理的消息集合
     */
    private void delegateMessageBatch(ILinkClientSession session, String userId,
            List<WeixinMessage> messages) {
        try {
            log.info("微信消息开始处理，sessionId={}，userId={}，messageCount={}",
                    session.sessionId(), userId, messages.size());
            userMessageService.processMessageBatch(session.sessionId(), userId, messages);
        } catch (RuntimeException exception) {
            log.error("微信会话消息批次处理失败，sessionId={}，userId={}",
                    session.sessionId(), userId, exception);
        } finally {
            stopTyping(session, userId);
        }
    }

    /**
     * 判断会话是否仍为注册表中的当前有效会话。
     *
     * @param session 待检查的会话
     * @return 会话仍有效时返回true，否则返回false
     */
    private boolean isCurrentSession(ILinkClientSession session) {
        return sessionRegistry.find(session.sessionId())
                .filter(currentSession -> currentSession == session)
                .isPresent();
    }

    /**
     * 开启指定会话的微信输入状态。
     *
     * @param client 当前会话独占的iLink客户端
     * @param userId 微信用户ID
     * @param sessionId 会话ID
     */
    private void startTyping(
            ILinkClient client,
            String userId,
            String sessionId) {
        try {
            client.startTyping(userId);
        } catch (IOException | ILinkException exception) {
            log.warn(
                    "开启微信输入状态失败，sessionId={}，userId={}",
                    sessionId,
                    userId,
                    exception);
        }
    }

    /**
     * 完成当前批次后关闭微信输入状态。
     *
     * @param session 当前iLink客户端会话
     * @param userId 微信用户ID
     */
    private void stopTyping(
            ILinkClientSession session,
            String userId) {
        try {
            session.client().stopTyping(userId);
        } catch (IOException | ILinkException exception) {
            log.warn(
                    "关闭微信输入状态失败，sessionId={}，userId={}",
                    session.sessionId(),
                    userId,
                    exception);
        }
    }

    /**
     * 应用停止时取消全部会话消息处理任务。
     */
    @PreDestroy
    public void destroy() {
        sessionTaskMap.values().forEach(sessionTask -> sessionTask.cancel(true));
        sessionTaskMap.clear();
    }
}
