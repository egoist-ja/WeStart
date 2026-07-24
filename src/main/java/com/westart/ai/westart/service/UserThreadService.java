package com.westart.ai.westart.service;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

/**
 * 用户消息接收与并发调度服务。
 */
public interface UserThreadService {

    /**
     * 接收指定iLink客户端拉取到的消息并放入对应会话队列。
     *
     * @param sessionId 会话ID
     * @param messages iLink拉取到的消息集合
     */
    void handleMessages(String sessionId, List<WeixinMessage> messages);

    /**
     * 为指定会话启动独立的消息处理虚拟线程。
     *
     * @param sessionId 会话ID
     */
    void startSession(String sessionId);

    /**
     * 停止指定会话的消息处理任务。
     *
     * @param sessionId 会话ID
     */
    void stopSession(String sessionId);
}
