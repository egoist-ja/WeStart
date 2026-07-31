package com.westart.ai.westart.config;

import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;


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
