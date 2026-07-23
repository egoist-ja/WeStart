package com.westart.ai.westart.service;

import com.github.wechat.ilink.sdk.core.login.LoginStatus;

/**
 * 全局微信机器人账号注册与登录服务。
 */
public interface WeChatLoginService {

    /**
     * 为全局唯一的微信机器人发起扫码注册（登录授权）。
     *
     * <p>该方法只注册机器人账号，普通聊天用户无需扫码登录。</p>
     *
     * @return 用于生成登录二维码的内容
     */
    String createLogin();

    /**
     * 获取全局微信机器人的注册登录状态。
     *
     * @return 登录状态
     */
    LoginStatus getLoginStatus();

    /**
     * 关闭当前微信客户端会话。
     *
     * <p>底层SDK不支持关闭后复用同一个客户端重新登录。</p>
     */
    void logout();
}
