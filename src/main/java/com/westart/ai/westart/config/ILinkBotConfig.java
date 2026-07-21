package com.westart.ai.westart.config;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * iLinkBot 配置类
 */
@Slf4j
@Configuration
public class ILinkBotConfig {

    @Value("${ilink.enabled:true}")
    private boolean enabled;

    @Value("${ilink.connect-timeout:35000}")
    private int connectTimeout;

    @Value("${ilink.read-timeout:35000}")
    private int readTimeout;

    @Value("${ilink.max-retries:3}")
    private int maxRetries;

    @Value("${ilink.heartbeat:true}")
    private boolean heartbeat;

    @Bean
    public ILinkClient ilinkClient() {
        log.info("初始化 iLinkBot 客户端...");
        
        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfig.builder()
                        .connectTimeoutMs(connectTimeout)
                        .readTimeoutMs(readTimeout)
                        .httpMaxRetries(maxRetries)
                        .heartbeatEnabled(heartbeat)
                        .build())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        log.info("登录成功，botId = {}", context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("登录失败", throwable);
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        log.info("收到 {} 条消息", messages.size());
                    }
                })
                .build();

        log.info("iLinkBot 客户端初始化完成");
        return client;
    }
}
