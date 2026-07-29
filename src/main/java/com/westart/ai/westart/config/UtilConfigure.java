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

    /** Transport 内部用 Jackson 2.x，签名必须同版本，否则字段顺序不同 → SHA256 不同 → 401 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JACKSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * OkHttpClient客户端配置
     * @return
     */
    @Bean
    public OkHttpClient okHttpClient(){
        return new OkHttpClient();
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

    /**
     * 配置McpToolProvider
     * @return
     */
    @Bean
    public McpToolProvider mcpToolProvider(){
        McpTransport luckinTransport = StreamableHttpMcpTransport.builder()
                .customHeaders(Map.of("Authorization","Bearer "+System.getenv("LUCKIN_API_KEY")))
                .url("https://gwmcp.lkcoffee.com/order/user/mcp")
                .logRequests(true)
                .logResponses(true)
                .build();
        McpHeadersSupplier flyHeadersSupplier = context -> {
            try {
                String apiKey = System.getenv("FLYAI_API_KEY");
                String bodyJson = JACKSON.writeValueAsString(context.message());
                return FlyAiHeaders.of("https://flyai.open.fliggy.com/mcp", bodyJson, apiKey);
            } catch (Exception e) {
                log.error("生成飞猪 MCP 请求头失败", e);
                return Map.of();
            }
        };

        McpTransport flyTransport = StreamableHttpMcpTransport.builder()
                .customHeaders(flyHeadersSupplier)
                .url("https://flyai.open.fliggy.com/mcp")
                .logRequests(true)
                .logResponses(true)
                .build();
        McpClient flyClient = null;
        try {
            flyClient = DefaultMcpClient.builder()
                    .transport(flyTransport)
                    .key("flyClient")
                    .addListener(new McpClientListener() {
                        @Override
                        public void beforeInitialize(McpCallContext context) {
                            log.info("初始化飞猪MCP");
                        }

                        @Override
                        public void afterInitialize(McpCallContext context) {
                            log.info("飞猪MCP初始化已完成");
                        }

                        @Override
                        public void beforeExecuteTool(McpCallContext context) {
                            InvocationContext invocationContext = context.invocationContext();
                            log.info("准备执行工具:{}", invocationContext.methodName());
                        }

                        @Override
                        public void afterExecuteTool(McpCallContext context, ToolExecutionResult result, Map<String, Object> rawResult) {
                            InvocationContext invocationContext = context.invocationContext();
                            log.info("准备执行工具:{},工具执行结果:{}", invocationContext.methodName(), result.result());
                        }
                    })
                    .build();
        } catch (Exception e) {
            log.error("飞猪 MCP 客户端初始化失败，将跳过", e);
        }

        McpClient luckinClient = null;
        try {
            luckinClient = DefaultMcpClient.builder()
                    .transport(luckinTransport)
                    .key("luckinClient")
                    .addListener(new McpClientListener() {
                        @Override
                        public void beforeInitialize(McpCallContext context) {
                            log.info("初始化瑞幸MCP");
                        }

                        @Override
                        public void afterInitialize(McpCallContext context) {
                            log.info("瑞幸MCP初始化已完成");
                        }

                        @Override
                        public void beforeExecuteTool(McpCallContext context) {
                            InvocationContext invocationContext = context.invocationContext();
                            log.info("准备执行工具:{}", invocationContext.methodName());
                        }

                        @Override
                        public void afterExecuteTool(McpCallContext context, ToolExecutionResult result, Map<String, Object> rawResult) {
                            InvocationContext invocationContext = context.invocationContext();
                            log.info("准备执行工具:{},工具执行结果:{}", invocationContext.methodName(), result.result());
                        }
                    })
                    .build();
        } catch (Exception e) {
            log.error("瑞幸 MCP 客户端初始化失败，将跳过", e);
        }

        List<McpClient> clients = new ArrayList<>();
        if (flyClient != null) clients.add(flyClient);
        if (luckinClient != null) clients.add(luckinClient);

        return McpToolProvider.builder()
                .mcpClients(clients.toArray(new McpClient[0]))
                .failIfOneServerFails(false)
                .build();
    }
}


/**
 * 飞猪 MCP 请求头生成器。
 * 用法：Map<String,String> h = FlyAiHeaders.of(url, body, apiKey);
 */
class FlyAiHeaders {

    // ===== 内置公共常量（npm 包公开值，直接用） =====
    private static final String SIGN_SECRET = "XSbdYnucPARDc9knhD8+X6hxdD1Nh6ZGI6Hadg25kBw=";
    private static final String FALLBACK_KEY = "sk-faRn8Kp2QzXvLm9YtA4EjHcWbS7oUdG5iF3xNqV6rZ";
    private static final String TTID = "ai2c(sk.clawhub)";
    private static final String UA = "flyai-cli/1.0.6 (Node.js v22.0.0; win32 x64)";
    private static final SecureRandom RNG = new SecureRandom();
    private static final String DEVICE_ID = sha256hex(UUID.randomUUID().toString()); // JVM 生命周期内固定

    private FlyAiHeaders() {}


    /**
     * @param url    完整 MCP 地址，如 https://flyai.open.fliggy.com/mcp
     * @param body   即将发送的 JSON-RPC body 字符串（签名依赖它，必须和实际发送的完全一致）
     * @param apiKey 你的 key；传 null/空 则用内置试用 key
     */
    public static Map<String, String> of(String url, String body, String apiKey) {
        try {
            String key  = (apiKey == null || apiKey.isBlank()) ? FALLBACK_KEY : apiKey.trim();
            String auth = "Bearer " + key;
            String ts   = String.valueOf(System.currentTimeMillis());
            String nonce = hex(randomBytes(16));
            String path  = URI.create(url).getPath();

            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "application/json");
            h.put("Accept", "application/json, text/event-stream");
            h.put("Authorization", auth);
            h.put("x-ff-ctx", ffCtx());
            h.put("x-ttid", TTID);
            h.put("User-Agent", UA);
            h.put("x-flyai-ts", ts);
            h.put("x-flyai-sign-ver", "7");
            h.put("x-flyai-sign-alg", "hmac-sha256");
            h.put("x-flyai-nonce", nonce);
            h.put("x-flyai-sign", sign(path, ts, nonce, body, auth));
            return h;
        } catch (Exception e) {
            throw new RuntimeException("FlyAiHeaders 生成失败", e);
        }
    }

    //签名

    private static String sign(String path, String ts, String nonce, String body, String auth) throws Exception {
        String data = "POST\n" + path + "\n" + ts + "\n" + nonce + "\n"
                + sha256hex(body) + "\n" + sha256hex(auth);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    // x-ff-ctx（加密指纹）

    private static String ffCtx() throws Exception {
        String json = "{\"machine\":{\"platform\":\"Windows\",\"arch\":\"x64\",\"cpus\":8,"
                + "\"memoryTierGB\":16,\"osType\":\"Windows\",\"nodeVersion\":\"22.0.0\",\"osReleaseMajor\":\"10\"},"
                + "\"fingerprint\":{\"language\":\"zh\",\"platform\":\"Windows\",\"userAgent\":\"" + UA + "\","
                + "\"hardwareConcurrency\":8,\"deviceMemory\":16,\"clientSurface\":\"cli\","
                + "\"timezoneOffset\":-480,\"deviceId\":\"" + DEVICE_ID + "\"}}";

        byte[] plain = gzip(json.getBytes(StandardCharsets.UTF_8));
        byte[] aesKey = MessageDigest.getInstance("SHA-256")
                .digest(SIGN_SECRET.getBytes(StandardCharsets.UTF_8));
        byte[] iv = randomBytes(12);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] enc = c.doFinal(plain);

        byte[] out = new byte[1 + iv.length + enc.length];
        out[0] = 0x01;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(enc, 0, out, 1 + iv.length, enc.length);
        return Base64.getEncoder().encodeToString(out);
    }

    //工具方法

    private static String sha256hex(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static byte[] gzip(byte[] in) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(bo)) { g.write(in); }
            return bo.toByteArray();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
