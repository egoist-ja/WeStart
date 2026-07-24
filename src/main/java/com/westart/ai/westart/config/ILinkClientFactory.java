package com.westart.ai.westart.config;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.westart.ai.westart.service.UserThreadService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * iLink客户端工厂，为每个登录会话创建相互独立的客户端实例。
 */
@Component
@RequiredArgsConstructor
public class ILinkClientFactory {

    private final ILinkConfig iLinkConfig;
    private final UserThreadService userThreadService;

    /**
     * 创建并绑定指定会话消息回调的iLink客户端。
     *
     * @param sessionId 登录会话唯一标识
     * @return 独立的iLink客户端
     */
    public ILinkClient createClient(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        return ILinkClient.builder()
                .config(iLinkConfig)
                .onMessage(messages ->
                        userThreadService.handleMessages(sessionId, messages))
                .build();
    }
}
