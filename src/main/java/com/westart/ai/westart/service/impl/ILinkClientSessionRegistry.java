package com.westart.ai.westart.service.impl;

import com.westart.ai.westart.DTO.ILinkClientSession;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * iLink客户端会话注册表，负责会话的注册、查询、关闭和资源回收。
 */
@Service
@Slf4j
public class ILinkClientSessionRegistry {

    /**
     * 当前应用实例持有的iLink客户端会话，键为会话唯一标识。
     */
    private final ConcurrentMap<String, ILinkClientSession> sessionMap =
            new ConcurrentHashMap<>();

    /**
     * 注册iLink客户端会话。
     *
     * @param session 待注册的客户端会话
     * @throws IllegalStateException 相同会话标识已经存在时抛出
     */
    public void register(ILinkClientSession session) {
        Objects.requireNonNull(session, "session不能为空");

        ILinkClientSession existingSession = sessionMap.putIfAbsent(session.sessionId(), session);
        if (existingSession != null) {
            throw new IllegalStateException(
                    "iLink客户端会话已经存在，sessionId=" + session.sessionId());
        }
        log.info("iLink客户端会话注册成功，sessionId={}", session.sessionId());
    }

    /**
     * 根据会话标识查询iLink客户端会话。
     *
     * @param sessionId 会话唯一标识
     * @return 查询到的客户端会话；不存在或会话标识为空时返回空
     */
    public Optional<ILinkClientSession> find(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionMap.get(sessionId));
    }

    /**
     * 根据会话标识获取必须存在的iLink客户端会话。
     *
     * @param sessionId 会话唯一标识
     * @return 对应的客户端会话
     * @throws IllegalArgumentException 会话标识为空或对应会话不存在时抛出
     */
    public ILinkClientSession getRequired(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        ILinkClientSession session = sessionMap.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException(
                    "iLink客户端会话不存在，sessionId=" + sessionId);
        }
        return session;
    }

    /**
     * 移除并关闭指定iLink客户端会话。
     *
     * <p>先从注册表移除会话，避免关闭过程中继续被新的业务请求获取。</p>
     *
     * @param sessionId 会话唯一标识
     * @return 成功移除会话时返回true，会话不存在时返回false
     */
    public boolean closeAndRemove(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId不能为空");
        }

        ILinkClientSession session = sessionMap.remove(sessionId);
        if (session == null) {
            return false;
        }

        closeSession(session);
        log.info("iLink客户端会话已关闭，sessionId={}", sessionId);
        return true;
    }

    /**
     * 应用停止时关闭并移除当前实例持有的全部iLink客户端会话。
     */
    @PreDestroy
    public void closeAll() {
        for (Map.Entry<String, ILinkClientSession> entry : sessionMap.entrySet()) {
            String sessionId = entry.getKey();
            ILinkClientSession session = entry.getValue();
            if (!sessionMap.remove(sessionId, session)) {
                continue;
            }
            try {
                closeSession(session);
                log.info("iLink客户端会话已关闭，sessionId={}", sessionId);
            } catch (RuntimeException exception) {
                log.error("关闭iLink客户端会话失败，sessionId={}", sessionId, exception);
            }
        }
    }

    /**
     * 清理会话消息并关闭底层iLink客户端。
     *
     * @param session 待关闭的客户端会话
     */
    private void closeSession(ILinkClientSession session) {
        session.messageQueue().clear();
        try {
            session.client().cancelLogin();
        } finally {
            session.client().close();
        }
    }
}
