package com.westart.ai.westart;

import com.westart.ai.westart.service.ILinkBotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * 启动类
 */
@Slf4j
@SpringBootApplication
public class WestartApplication {

    @Autowired
    private ILinkBotService botService;

    public static void main(String[] args) {
        SpringApplication.run(WestartApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("=====================================");
        log.info("iLinkBot 服务启动成功");
        log.info("API 地址: http://localhost:8080/api/bot");
        log.info("=====================================");
        
        new Thread(() -> {
            try {
                log.info("开始登录...");
                boolean loginSuccess = botService.loginSync();
                
                if (loginSuccess) {
                    log.info("请向机器人发送一条消息...");
                    botService.waitForMessage(10);
                    
                    // 启动持续监听
                    botService.startMessageLoop();
                } else {
                    log.error("登录失败");
                }
                
                log.info("服务就绪，等待 API 调用...");
            } catch (Exception e) {
                log.error("初始化失败", e);
            }
        }, "ilinkbot-init").start();
    }
}
