package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.westart.ai.westart.DTO.LoginSessionResult;
import com.westart.ai.westart.service.UserMessageService;
import com.westart.ai.westart.service.WeChatAgentService;
import com.westart.ai.westart.service.WeChatLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 微信智能体组合服务实现，统一对外提供机器人登录和用户消息处理能力。
 */
@Service
@RequiredArgsConstructor
public class WeChatAgentServiceImpl implements WeChatAgentService {

    private final WeChatLoginService weChatLoginService;
    private final UserMessageService userMessageService;

    /**
     * 创建独立微信客户端会话并发起扫码登录。
     *
     * @return 登录会话标识及二维码内容
     */
    @Override
    public LoginSessionResult userLogin() {
        return weChatLoginService.createLogin();
    }

    /**
     * 获取指定微信客户端会话的登录状态。
     *
     * @param sessionId 登录会话唯一标识
     * @return 登录状态
     */
    @Override
    public LoginStatus getLoginStatus(String sessionId) {
        return weChatLoginService.getLoginStatus(sessionId);
    }

    /**
     * 关闭指定微信客户端会话。
     *
     * @param sessionId 登录会话唯一标识
     */
    @Override
    public void logout(String sessionId) {
        weChatLoginService.logout(sessionId);
    }

    /**
     * 向指定微信用户发送文本消息。
     *
     * @param sessionId iLink客户端会话ID
     * @param userId 微信用户ID
     * @param content 消息内容
     */
    @Override
    public void sendMessage(String sessionId, String userId, String content) {
        userMessageService.sendMessage(sessionId, userId, content);
    }

}
