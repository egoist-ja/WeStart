package com.westart.ai.westart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


/**
 * WeStart应用启动入口。
 */
@EnableScheduling
@SpringBootApplication
public class WestartApplication {

    /**
     * 启动Spring Boot应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(WestartApplication.class, args);
        System.out.println("启动成功");
    }

}
