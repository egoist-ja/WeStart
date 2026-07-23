package com.westart.ai.westart.service;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

/**
 * 用户消息处理服务。
 */
public interface UserMessageService {

    /**
     * 向指定微信用户发送文本消息。
     *
     * @param userId 微信用户ID
     * @param content 消息内容
     */
    void sendMessage(String userId, String content);

    /**
     * 解析并处理指定用户的完整消息批次。
     *
     * @param userId 微信用户ID
     * @param batchMessages 完成防抖收集的原始微信消息
     */
    void processMessageBatch(String userId, List<WeixinMessage> batchMessages);
}
