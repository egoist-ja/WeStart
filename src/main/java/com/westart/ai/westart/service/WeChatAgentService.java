package com.westart.ai.westart.service;

import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.westart.ai.westart.DTO.LoginSessionResult;

/**
 * 微信智能体业务服务。
 */
public interface WeChatAgentService {

    /**
     * 创建独立微信客户端会话并发起扫码登录。
     *
     * @return 登录会话标识及二维码内容
     */
    LoginSessionResult userLogin();

    /**
     * 获取指定微信客户端会话的登录状态。
     *
     * @param sessionId 登录会话唯一标识
     * @return 登录状态
     */
    LoginStatus getLoginStatus(String sessionId);

    /**
     * 关闭指定微信客户端会话。
     *
     * @param sessionId 登录会话唯一标识
     */
    void logout(String sessionId);

    /**
     * 向已建立会话上下文的用户发送消息。
     *
     * @param sessionId iLink客户端会话ID
     * @param userId 微信用户ID
     * @param content 消息内容
     */
    void sendMessage(String sessionId, String userId, String content);

}
