package com.westart.ai.westart.DTO;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import io.micrometer.common.util.StringUtils;

import java.time.Instant;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * iLink客户端会话，封装一次扫码登录对应的固定客户端资源。
 *
 * @param sessionId 会话唯一标识，用于查询、管理和关闭指定用户的iLink客户端会话
 * @param client 当前会话独占的iLink客户端，用于登录、接收消息、下载媒体和发送回复
 * @param messageQueue 当前会话的非阻塞消息队列，用于解耦SDK消息接收线程和业务消息处理线程
 * @param createdAt 会话创建时间，用于统计会话存活时间及清理长期未完成登录的会话
 */
public record ILinkClientSession(
        String sessionId,
        ILinkClient client,
        Queue<WeixinMessage> messageQueue,
        Instant createdAt) {

    /**
     * 校验会话固定字段，避免创建不可用的客户端会话。
     */
    public ILinkClientSession {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        Objects.requireNonNull(client, "client不能为空");
        Objects.requireNonNull(messageQueue, "messageQueue不能为空");
        Objects.requireNonNull(createdAt, "createdAt不能为空");
    }

    /**
     * 使用当前时间创建iLink客户端会话。
     *
     * @param sessionId 会话唯一标识
     * @param client 当前会话独占的iLink客户端
     */
    public ILinkClientSession(
            String sessionId,
            ILinkClient client) {
        this(
                sessionId,
                client,
                new ConcurrentLinkedQueue<>(),
                Instant.now());
    }
}
