package com.westart.ai.westart.config.strategy;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * 飞猪 MCP 请求签名工具。
 */
public final class FlyAiHeaders {

    private static final String SIGN_SECRET = "XSbdYnucPARDc9knhD8+X6hxdD1Nh6ZGI6Hadg25kBw=";
    private static final String TTID = "ai2c(sk.clawhub)";
    private static final String USER_AGENT = "flyai-cli/1.0.6 (Node.js v22.0.0; win32 x64)";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String DEVICE_ID = sha256Hex(UUID.randomUUID().toString());

    private FlyAiHeaders() {
    }

    /**
     * 生成飞猪 MCP 请求所需的认证与签名请求头。
     *
     * @param url MCP 服务地址
     * @param body JSON-RPC 请求体
     * @param apiKey 飞猪 API Key
     * @return 完整的飞猪 MCP 请求头
     */
    public static Map<String, String> of(String url, String body, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("飞猪 MCP apiKey 不能为空");
        }
        try {
            String auth = "Bearer " + apiKey.trim();
            String timestamp = String.valueOf(System.currentTimeMillis());
            String nonce = hex(randomBytes(16));
            String path = URI.create(url).getPath();

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json, text/event-stream");
            headers.put("Authorization", auth);
            headers.put("x-ff-ctx", encryptedFingerprint());
            headers.put("x-ttid", TTID);
            headers.put("User-Agent", USER_AGENT);
            headers.put("x-flyai-ts", timestamp);
            headers.put("x-flyai-sign-ver", "7");
            headers.put("x-flyai-sign-alg", "hmac-sha256");
            headers.put("x-flyai-nonce", nonce);
            headers.put("x-flyai-sign", sign(path, timestamp, nonce, body, auth));
            return headers;
        } catch (Exception e) {
            throw new IllegalStateException("生成飞猪 MCP 请求头失败", e);
        }
    }

    private static String sign(String path, String timestamp, String nonce, String body, String auth)
            throws Exception {
        String data = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n"
                + sha256Hex(body) + "\n" + sha256Hex(auth);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String encryptedFingerprint() throws Exception {
        String json = "{\"machine\":{\"platform\":\"Windows\",\"arch\":\"x64\",\"cpus\":8,"
                + "\"memoryTierGB\":16,\"osType\":\"Windows\",\"nodeVersion\":\"22.0.0\",\"osReleaseMajor\":\"10\"},"
                + "\"fingerprint\":{\"language\":\"zh\",\"platform\":\"Windows\",\"userAgent\":\"" + USER_AGENT + "\","
                + "\"hardwareConcurrency\":8,\"deviceMemory\":16,\"clientSurface\":\"cli\","
                + "\"timezoneOffset\":-480,\"deviceId\":\"" + DEVICE_ID + "\"}}";

        byte[] plain = gzip(json.getBytes(StandardCharsets.UTF_8));
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(SIGN_SECRET.getBytes(StandardCharsets.UTF_8));
        byte[] initializationVector = randomBytes(12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, initializationVector));
        byte[] encrypted = cipher.doFinal(plain);

        byte[] result = new byte[1 + initializationVector.length + encrypted.length];
        result[0] = 0x01;
        System.arraycopy(initializationVector, 0, result, 1, initializationVector.length);
        System.arraycopy(encrypted, 0, result, 1 + initializationVector.length, encrypted.length);
        return Base64.getEncoder().encodeToString(result);
    }

    private static String sha256Hex(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 失败", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            result.append(String.format("%02x", current));
        }
        return result.toString();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] gzip(byte[] source) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutput = new GZIPOutputStream(output)) {
            gzipOutput.write(source);
            gzipOutput.finish();
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("压缩飞猪 MCP 指纹信息失败", e);
        }
    }
}
