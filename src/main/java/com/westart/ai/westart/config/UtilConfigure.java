package com.westart.ai.westart.config;

import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Slf4j
@Configuration
public class UtilConfigure {

    /**
     * OkHttpClient客户端配置
     * @return
     */
    @Bean
    public OkHttpClient okHttpClient(){
        return new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    @Bean
    public Gson gson(){
        return new Gson();
    }

    /**
     * ILinkConfig客户端配置
     * @return
     */
    @Bean
    public ILinkConfig iLinkConfig() {
        return ILinkConfig.builder()
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
    }


}
