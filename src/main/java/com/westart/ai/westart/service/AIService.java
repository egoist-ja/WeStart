package com.westart.ai.westart.service;

import com.google.gson.*;
import com.westart.ai.westart.service.LearningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通义千问 AI 服务（HTTP 直接调用）
 */
@Slf4j
@Service
public class AIService {

    @Value("${dashscope.api-key:sk-ws-H.EDXDDPE.S3zq.MEQCIFgbKSviIWu3UzE9GTtndZ20V0NcZOQw4100h3ft2GsvAiBDI4J6ADieWbgkOYqo4qjh2ShmmE-fOS1OtMHEu0j3bA}")
    private String apiKey;

    @Autowired
    private LearningService learningService;

    @Value("${ai.model:qwen3.7-max}")
    private String model;

    @Value("${ai.system-prompt:你是一个微信助手，请用简短友好的中文回复。}")
    private String systemPrompt;

    private final HttpClient httpClient;
    private final Map<String, List<Map<String, String>>> conversations = new ConcurrentHashMap<>();

    public AIService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String chat(String userId, String userMessage) {
        try {
            log.info("AI 对话: {} -> {}", userId, userMessage);

            String systemPrompt = learningService.generatePersonalizedPrompt(userId);
            log.info("个性化 prompt: {}", systemPrompt);

            List<Map<String, String>> messages = getOrCreateConversation(userId);
            
            if (!messages.isEmpty() && "system".equals(messages.get(0).get("role"))) {
                messages.set(0, Map.of("role", "system", "content", systemPrompt));
            } else {
                messages.add(0, Map.of("role", "system", "content", systemPrompt));
            }
            
            messages.add(Map.of("role", "user", "content", userMessage));

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);
            
            JsonArray messagesArray = new JsonArray();
            for (Map<String, String> msg : messages) {
                JsonObject msgObj = new JsonObject();
                msgObj.addProperty("role", msg.get("role"));
                msgObj.addProperty("content", msg.get("content"));
                messagesArray.add(msgObj);
            }
            requestBody.add("messages", messagesArray);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
                String reply = result.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();

                messages.add(Map.of("role", "assistant", "content", reply));
                
                if (messages.size() > 20) {
                    messages.subList(0, messages.size() - 10).clear();
                }

                // 记录对话到学习服务
                learningService.recordConversation(userId, userMessage, reply);

                log.info("AI 回复: {}", reply);
                return reply;
            } else {
                log.error("AI 调用失败: HTTP {}", response.statusCode());
                return "抱歉，我现在有点忙，请稍后再试。";
            }

        } catch (Exception e) {
            log.error("AI 调用异常", e);
            return "抱歉，我现在有点忙，请稍后再试。";
        }
    }

    public void clearConversation(String userId) {
        conversations.remove(userId);
        log.info("清除会话: {}", userId);
    }

    public boolean isImageGenerationRequest(String message) {
        if (message == null) return false;
        boolean hasAction = message.contains("生成") || message.contains("画一") || message.contains("帮我画")
                || message.contains("做一") || message.contains("制作一") || message.contains("创建一");
        boolean hasImage = message.contains("图") || message.contains("画") || message.contains("照片")
                || message.contains("壁纸") || message.contains("头像") || message.contains("海报")
                || message.contains("插画");
        return hasAction && hasImage;
    }

    public String handleImageRequest(String userId, String message) {
        String prompt = extractImagePrompt(message);
        log.info("图片生成请求: {} -> prompt: {}", message, prompt);
        learningService.recordConversation(userId, message, "[图片生成] " + prompt);
        return prompt;
    }

    private String extractImagePrompt(String message) {
        String prompt = message
                .replaceAll("(帮我|请|给我|麻烦)?(生成|画|做|制作|创建)(一张|一个|一幅|张|个|幅)?", "")
                .replaceAll("(的)?(图片|图|画|照片|壁纸|头像|插画|海报)", "")
                .replaceAll("(可以|能|吗|呢|吧|呀|啊|嘛)", "")
                .replaceAll("[？?。.！!，,]", "")
                .trim();
        if (prompt.isEmpty()) {
            prompt = message.replaceAll("[？?。.！!]", "").trim();
        }
        return prompt;
    }

    private List<Map<String, String>> getOrCreateConversation(String userId) {
        return conversations.computeIfAbsent(userId, id -> {
            List<Map<String, String>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", systemPrompt));
            return msgs;
        });
    }
}
