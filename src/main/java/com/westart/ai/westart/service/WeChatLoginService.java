package com.westart.ai.westart.service;

import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.westart.ai.westart.DTO.LoginSessionResult;

/**
 * 微信客户端会话注册与登录服务。
 */
public interface WeChatLoginService {

    /**
     * 创建独立的微信客户端会话并发起扫码登录。
     *
     * @return 登录会话标识及二维码内容
     */
    LoginSessionResult createLogin();

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
}
