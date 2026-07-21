package com.westart.ai.westart.service;

import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 用户学习服务 - 实现 AI 个性化
 */
@Slf4j
@Service
public class LearningService {

    private final String dataDir = "./learning-data";
    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Path.of(dataDir));
            loadProfiles();
            log.info("学习服务初始化完成");
        } catch (Exception e) {
            log.error("学习服务初始化失败", e);
        }
    }

    /**
     * 用户画像
     */
    public static class UserProfile {
        public String userId;
        public String userName;
        public List<Conversation> conversations = new ArrayList<>();
        public Map<String, Integer> topics = new HashMap<>();
        public Map<String, Integer> styles = new HashMap<>();
        public List<String> preferences = new ArrayList<>();
        public int totalMessages = 0;
        public LocalDateTime lastActive;

        public UserProfile(String userId) {
            this.userId = userId;
            this.lastActive = LocalDateTime.now();
        }
    }

    /**
     * 对话记录
     */
    public static class Conversation {
        public String userMessage;
        public String aiReply;
        public String feedback;
        public LocalDateTime timestamp;

        public Conversation(String userMessage, String aiReply) {
            this.userMessage = userMessage;
            this.aiReply = aiReply;
            this.timestamp = LocalDateTime.now();
        }
    }

    /**
     * 记录对话
     */
    public void recordConversation(String userId, String userMessage, String aiReply) {
        UserProfile profile = getOrCreateProfile(userId);
        
        Conversation conv = new Conversation(userMessage, aiReply);
        profile.conversations.add(conv);
        profile.totalMessages++;
        profile.lastActive = LocalDateTime.now();

        analyzeTopics(profile, userMessage);

        if (profile.conversations.size() > 100) {
            profile.conversations = profile.conversations.stream()
                    .skip(profile.conversations.size() - 100)
                    .collect(Collectors.toList());
        }

        saveProfile(profile);
        log.info("记录对话: {} -> {} 条消息", userId, profile.totalMessages);
    }

    /**
     * 记录用户反馈
     */
    public void recordFeedback(String userId, int conversationIndex, String feedback) {
        UserProfile profile = profiles.get(userId);
        if (profile == null || conversationIndex >= profile.conversations.size()) {
            return;
        }

        Conversation conv = profile.conversations.get(conversationIndex);
        conv.feedback = feedback;

        if ("like".equals(feedback)) {
            learnStyle(profile, conv.aiReply, 1);
        } else if ("dislike".equals(feedback)) {
            learnStyle(profile, conv.aiReply, -1);
        }

        saveProfile(profile);
        log.info("记录反馈: {} -> {}", userId, feedback);
    }

    /**
     * 分析话题
     */
    private void analyzeTopics(UserProfile profile, String message) {
        String[] keywords = {"旅游", "美食", "科技", "电影", "音乐", "游戏", "工作", "学习", "健康", "情感"};
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                profile.topics.merge(keyword, 1, Integer::sum);
            }
        }
    }

    /**
     * 学习回复风格
     */
    private void learnStyle(UserProfile profile, String reply, int score) {
        if (reply.length() < 50) {
            profile.styles.merge("简短", score, Integer::sum);
        } else if (reply.contains("😊") || reply.contains("😄")) {
            profile.styles.merge("活泼", score, Integer::sum);
        } else if (reply.contains("首先") || reply.contains("其次")) {
            profile.styles.merge("结构化", score, Integer::sum);
        }
    }

    /**
     * 生成个性化 System Prompt
     */
    public String generatePersonalizedPrompt(String userId) {
        UserProfile profile = profiles.get(userId);
        if (profile == null || profile.totalMessages < 3) {
            return "你是一个微信助手，请用简短友好的中文回复。";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个微信助手，请根据以下用户画像进行个性化回复：\n\n");

        if (!profile.topics.isEmpty()) {
            String topTopics = profile.topics.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .map(e -> e.getKey())
                    .collect(Collectors.joining("、"));
            prompt.append("用户感兴趣的话题：").append(topTopics).append("\n");
        }

        if (!profile.styles.isEmpty()) {
            String topStyle = profile.styles.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("友好");
            prompt.append("回复风格：").append(topStyle).append("\n");
        }

        if (!profile.preferences.isEmpty()) {
            prompt.append("用户偏好：").append(String.join("、", profile.preferences)).append("\n");
        }

        prompt.append("\n请用中文回复，保持友好和个性化。");
        return prompt.toString();
    }

    /**
     * 获取或创建用户画像
     */
    private UserProfile getOrCreateProfile(String userId) {
        return profiles.computeIfAbsent(userId, UserProfile::new);
    }

    /**
     * 保存用户画像
     */
    private void saveProfile(UserProfile profile) {
        try {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(LocalDateTime.class, new com.google.gson.JsonSerializer<LocalDateTime>() {
                        @Override
                        public com.google.gson.JsonElement serialize(LocalDateTime src, java.lang.reflect.Type typeOfSrc, com.google.gson.JsonSerializationContext context) {
                            return new com.google.gson.JsonPrimitive(src.toString());
                        }
                    })
                    .create();
            String json = gson.toJson(profile);
            Files.writeString(Path.of(dataDir, profile.userId + ".json"), json);
        } catch (Exception e) {
            log.error("保存画像失败", e);
        }
    }

    /**
     * 加载用户画像
     */
    private void loadProfiles() {
        try {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(LocalDateTime.class, new com.google.gson.JsonDeserializer<LocalDateTime>() {
                        @Override
                        public LocalDateTime deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type typeOfT, com.google.gson.JsonDeserializationContext context) {
                            return LocalDateTime.parse(json.getAsString());
                        }
                    })
                    .create();
            
            Files.list(Path.of(dataDir))
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            String json = Files.readString(path);
                            UserProfile profile = gson.fromJson(json, UserProfile.class);
                            profiles.put(profile.userId, profile);
                            log.info("加载画像: {} ({} 条消息)", profile.userId, profile.totalMessages);
                        } catch (Exception e) {
                            log.error("加载画像失败: {}", path, e);
                        }
                    });
        } catch (Exception e) {
            log.warn("无历史画像数据");
        }
    }

    /**
     * 获取用户画像
     */
    public UserProfile getProfile(String userId) {
        return profiles.get(userId);
    }

    /**
     * 添加用户偏好
     */
    public void addPreference(String userId, String preference) {
        UserProfile profile = getOrCreateProfile(userId);
        if (!profile.preferences.contains(preference)) {
            profile.preferences.add(preference);
            saveProfile(profile);
            log.info("添加偏好: {} -> {}", userId, preference);
        }
    }

    /**
     * 清除用户数据
     */
    public void clearProfile(String userId) {
        profiles.remove(userId);
        try {
            Files.deleteIfExists(Path.of(dataDir, userId + ".json"));
            log.info("清除画像: {}", userId);
        } catch (Exception e) {
            log.error("清除画像失败", e);
        }
    }
}
