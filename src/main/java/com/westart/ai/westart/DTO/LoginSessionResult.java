package com.westart.ai.westart.DTO;

import io.micrometer.common.util.StringUtils;

/**
 * 微信扫码登录会话创建结果。
 *
 * @param sessionId 登录会话唯一标识，用于查询状态、发送消息和退出登录
 * @param qrCodeContent 用于渲染微信登录二维码的原始内容
 */
public record LoginSessionResult(
        String sessionId,
        String qrCodeContent) {

    /**
     * 校验登录会话创建结果。
     */
    public LoginSessionResult {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        if (StringUtils.isBlank(qrCodeContent)) {
            throw new IllegalArgumentException("qrCodeContent不能为空");
        }
    }
}
