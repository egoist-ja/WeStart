package com.westart.ai.westart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 微信消息分发与用户任务执行器配置。
 */
@Configuration
public class ExecutorConfig {

    /**
     * 创建用户消息执行器，为每个用户启动独立虚拟线程。
     *
     * @return 用户消息执行器
     */
    @Bean(name = "wechatUserMessageExecutor", destroyMethod = "shutdownNow")
    public ExecutorService wechatUserMessageExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("wechat-user-message-", 0L).factory());
    }
}
