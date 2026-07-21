package com.westart.ai.westart.config;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.westart.ai.westart.service.impl.WeChatAgentServiceImpl;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UtilConfiguration {

    /**
     * OkHttpClient客户端配置
     * @return
     */
    @Bean
    public OkHttpClient okHttpClient(){
        return new OkHttpClient();
    }

    /**
     * ILinkClient客户端配置
     * @return
     */
    @Bean
    public ILinkClient iLinkClient(
            ObjectProvider<WeChatAgentServiceImpl> serviceProvider) {
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(15_000)
                .readTimeoutMs(35_000)
                .writeTimeoutMs(15_000)
                .httpMaxRetries(3)
                .retryBaseDelayMs(1_000)
                .retryMaxDelayMs(10_000)
                .retryJitterEnabled(true)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(100L)
                .build();
        return ILinkClient.builder()
                .config(config)
                .onMessage(messages -> serviceProvider.getObject().handleMessages(messages))
                .build();
    }
}
