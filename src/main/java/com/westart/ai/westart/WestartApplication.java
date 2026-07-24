package com.westart.ai.westart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan
public class WestartApplication {

    public static void main(String[] args) {

        SpringApplication.run(WestartApplication.class, args);
        System.out.println("Westart Application Started");
    }

}
