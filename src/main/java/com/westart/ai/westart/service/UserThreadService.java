package com.westart.ai.westart.service;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

/**
 * 用户消息接收与并发调度服务。
 */
public interface UserThreadService {

    /**
     * 接收iLink SDK拉取到的消息，并按用户ID异步调度。
     *
     * @param messages iLink拉取到的消息集合
     */
    void handleMessages(List<WeixinMessage> messages);
}
