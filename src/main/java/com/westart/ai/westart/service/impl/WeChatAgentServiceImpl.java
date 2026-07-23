package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.westart.ai.westart.service.UserMessageService;
import com.westart.ai.westart.service.WeChatAgentService;
import com.westart.ai.westart.service.WeChatLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 微信智能体组合服务实现，统一对外提供机器人登录和用户消息处理能力。
 */
@Service
@RequiredArgsConstructor
public class WeChatAgentServiceImpl implements WeChatAgentService {

    private final WeChatLoginService weChatLoginService;
    private final UserMessageService userMessageService;

    /**
     * 为全局唯一的微信机器人发起扫码注册。
     *
     * @return 用于生成登录二维码的内容
     */
    @Override
    public String userLogin() {
        return weChatLoginService.createLogin();
    }

    /**
     * 向指定微信用户发送文本消息。
     *
     * @param userId 微信用户ID
     * @param content 消息内容
     */
    @Override
    public void sendMessage(String userId, String content) {
        userMessageService.sendMessage(userId, content);
    }

    /**
     * 处理指定用户完成防抖收集的消息批次。
     *
     * @param userId 微信用户ID
     * @param batchMessages 原始微信消息批次
     */
    @Override
    public void processMessageBatch(String userId, List<WeixinMessage> batchMessages) {
        userMessageService.processMessageBatch(userId, batchMessages);
    }
}
