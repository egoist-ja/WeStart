package com.westart.ai.westart.service;

public interface WeChatAgentService {

    /**
     * 发起微信扫码登录。
     *
     * @return 可用于渲染二维码的内容
     */
    String userLogin();

    /**
     * 向已建立会话上下文的用户发送消息。
     *
     * @param userId 微信用户ID
     * @param content 消息内容
     */
    void sendMessage(String userId, String content);

}
