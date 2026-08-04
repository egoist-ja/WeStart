package com.westart.ai.westart.config;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnHeartbeatListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.westart.ai.westart.service.UserThreadService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Consumer;

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
     * @param heartbeatFailureHandler 心跳失败处理器
     * @return 独立的iLink客户端
     */
    public ILinkClient createClient(
            String sessionId,
            Consumer<Throwable> heartbeatFailureHandler) {
        return buildClient(sessionId, null, heartbeatFailureHandler);
    }

    /**
     * 使用已持久化的登录上下文创建iLink客户端。
     *
     * @param sessionId 登录会话唯一标识
     * @param loginContext SDK登录上下文
     * @param heartbeatFailureHandler 心跳失败处理器
     * @return 已恢复登录状态的独立iLink客户端
     */
    public ILinkClient createClient(
            String sessionId,
            LoginContext loginContext,
            Consumer<Throwable> heartbeatFailureHandler) {
        Objects.requireNonNull(loginContext, "loginContext不能为空");
        return buildClient(sessionId, loginContext, heartbeatFailureHandler);
    }

    /**
     * 创建并绑定指定会话消息回调的iLink客户端。
     *
     * @param sessionId 登录会话唯一标识
     * @param loginContext SDK登录上下文，扫码登录客户端传入null
     * @param heartbeatFailureHandler 心跳失败处理器
     * @return 独立的iLink客户端
     */
    private ILinkClient buildClient(
            String sessionId,
            LoginContext loginContext,
            Consumer<Throwable> heartbeatFailureHandler) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        Objects.requireNonNull(heartbeatFailureHandler, "heartbeatFailureHandler不能为空");
        ILinkClientBuilder clientBuilder = ILinkClient.builder()
                .config(iLinkConfig)
                .onHeartbeat(new OnHeartbeatListener() {
                    @Override
                    public void onHeartbeatSuccess() {
                        // 心跳恢复后无需额外处理。
                    }

                    @Override
                    public void onHeartbeatFailure(Throwable cause) {
                        heartbeatFailureHandler.accept(cause);
                    }
                })
                .onMessage(messages ->
                        userThreadService.handleMessages(sessionId, messages));
        if (loginContext != null) {
            clientBuilder.loginContext(loginContext);
        }
        return clientBuilder.build();
    }
}
