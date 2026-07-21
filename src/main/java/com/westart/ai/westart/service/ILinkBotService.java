package com.westart.ai.westart.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * iLinkBot 服务类
 */
@Slf4j
@Service
public class ILinkBotService {

    @Autowired
    private ILinkClient client;

    @Autowired
    private AIService aiService;

    @Autowired
    private MultimodalService multimodalService;

    @Value("${bot.auto-reply:true}")
    private boolean autoReply;

    @Getter
    private String targetUserId;

    @Getter
    private String contextToken;

    private String cursor = "";

    private final AtomicBoolean loggedIn = new AtomicBoolean(false);
    private final CountDownLatch loginLatch = new CountDownLatch(1);

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final ConcurrentHashMap<String, List<String>> messageBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> flushTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> voiceTimestamps = new ConcurrentHashMap<>();
    private long bufferTimeoutMs = 2000;
    private long maxBufferTimeoutMs = 8000;
    private Set<String> batchKeywords = Set.of("。");

    @Value("${bot.batch.buffer-timeout-ms:2000}")
    public void setBufferTimeoutMs(long bufferTimeoutMs) {
        this.bufferTimeoutMs = bufferTimeoutMs;
    }

    @Value("${bot.batch.max-buffer-timeout-ms:8000}")
    public void setMaxBufferTimeoutMs(long maxBufferTimeoutMs) {
        this.maxBufferTimeoutMs = maxBufferTimeoutMs;
    }

    @Value("${bot.batch.keywords:。,！,？,!,?,好吧,谢谢,感谢,嗯嗯,就这样,对吗,是吗,可以吗,行吗,好吗,吧,呢,啊,呀,嘛,吗,哦}")
    public void setBatchKeywords(Set<String> batchKeywords) {
        this.batchKeywords = batchKeywords;
    }

    public boolean isLoggedIn() {
        return loggedIn.get();
    }


    public boolean loginSync() {
        try {
            LoginContext saved = loadLoginContext();
            if (saved != null && tryRestoreLogin(saved)) {
                loggedIn.set(true);
                loginLatch.countDown();
                log.info("登录恢复成功，botId = {}", saved.getBotId());
                return true;
            }

            log.info("开始登录...");
            String qrCodeContent = client.executeLogin();
            System.out.println("\n========================================");
            System.out.println("请使用微信扫码:");
            System.out.println(qrCodeContent);
            System.out.println("========================================\n");
            
            LoginContext context = client.getLoginFuture().get();
            loggedIn.set(true);
            saveLoginContext(context);
            log.info("登录成功，botId = {}", context.getBotId());
            loginLatch.countDown();
            return true;
            
        } catch (Exception e) {
            log.error("登录失败", e);
            loginLatch.countDown();
            return false;
        }
    }

    private static final String LOGIN_FILE = "login-context.json";

    private void saveLoginContext(LoginContext context) {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("botToken", context.getBotToken());
            obj.addProperty("userId", context.getUserId());
            obj.addProperty("botId", context.getBotId());
            obj.addProperty("baseUrl", context.getBaseUrl());
            Files.writeString(Path.of(LOGIN_FILE), new Gson().toJson(obj));
            log.info("登录凭证已保存到 {}", LOGIN_FILE);
        } catch (Exception e) {
            log.warn("保存登录凭证失败", e);
        }
    }

    private LoginContext loadLoginContext() {
        try {
            Path path = Path.of(LOGIN_FILE);
            if (!Files.exists(path)) return null;
            String json = Files.readString(path);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return new LoginContext(
                    obj.get("botToken").getAsString(),
                    obj.get("userId").getAsString(),
                    obj.get("botId").getAsString(),
                    obj.get("baseUrl").getAsString()
            );
        } catch (Exception e) {
            log.warn("加载登录凭证失败", e);
            try { Files.deleteIfExists(Path.of(LOGIN_FILE)); } catch (Exception ignored) {}
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean tryRestoreLogin(LoginContext context) {
        try {
            Field field = client.getClass().getDeclaredField("loginContext");
            field.setAccessible(true);
            AtomicReference<LoginContext> ref = (AtomicReference<LoginContext>) field.get(client);
            ref.set(context);
            log.info("LoginContext 已注入");
            return true;
        } catch (Exception e) {
            log.warn("注入 LoginContext 失败", e);
            try { Files.deleteIfExists(Path.of(LOGIN_FILE)); } catch (Exception ignored) {}
            return false;
        }
    }

    public boolean waitForMessage(int maxRetries) {
        int retryCount = 0;

        while (targetUserId == null && retryCount < maxRetries) {
            try {
                List<WeixinMessage> messages = client.getUpdates();
                if (!messages.isEmpty()) {
                    WeixinMessage msg = messages.get(0);
                    targetUserId = msg.getFrom_user_id();
                    contextToken = msg.getContext_token();
                    log.info("获取用户ID: {}", targetUserId);
                    
                    if (autoReply && msg.getItem_list() != null) {
                        for (var item : msg.getItem_list()) {
                            if (item.getText_item() != null) {
                                String text = extractTextFromItem(item);
                                if (text != null && !text.trim().isEmpty()) {
                                    autoReplyMessage(targetUserId, text);
                                }
                            } else if (item.getImage_item() != null || item.getVoice_item() != null || item.getVideo_item() != null) {
                                handleMultimodalMessage(targetUserId, item);
                            }
                        }
                    }
                    return true;
                }
                
                retryCount++;
                log.info("等待消息中... ({}/{})", retryCount, maxRetries);
                Thread.sleep(3000);
                
            } catch (Exception e) {
                log.error("接收消息异常", e);
                retryCount++;
            }
        }
        
        if (targetUserId == null) {
            log.warn("未获取到用户ID，请先向机器人发送消息");
        }
        return targetUserId != null;
    }

    /**
     * 启动持续监听消息
     */
    public void startMessageLoop() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<WeixinMessage> messages = client.getUpdates();

                if (messages != null && !messages.isEmpty()) {
                    log.info("收到 {} 条消息", messages.size());
                    for (WeixinMessage msg : messages) {
                        String userId = msg.getFrom_user_id();

                        if (msg.getItem_list() != null) {
                            for (var item : msg.getItem_list()) {
                                if (item.getText_item() != null) {
                                    String text = extractTextFromItem(item);
                                    if (text != null && !text.trim().isEmpty()) {
                                        Long voiceTime = voiceTimestamps.get(userId);
                                        if (voiceTime != null && (System.currentTimeMillis() - voiceTime) < 5000) {
                                            log.info("检测到语音转文字: {} -> {}", userId, text);
                                            voiceTimestamps.remove(userId);
                                            if (autoReply) {
                                                autoReplyMessage(userId, text);
                                            }
                                        } else {
                                            log.info("收到文本: {} -> {}", userId, text);
                                            if (autoReply) {
                                                bufferTextMessage(userId, text);
                                            }
                                        }
                                    }
                                } else if (item.getImage_item() != null) {
                                    log.info("收到图片: {}", userId);
                                    if (autoReply) {
                                        flushMessageBuffer(userId);
                                        handleMultimodalMessage(userId, item);
                                    }
                                } else if (item.getVoice_item() != null) {
                                    voiceTimestamps.put(userId, System.currentTimeMillis());
                                    log.info("收到语音: {}", userId);
                                    if (autoReply) {
                                        handleMultimodalMessage(userId, item);
                                    }
                                } else if (item.getVideo_item() != null) {
                                    log.info("收到视频: {}", userId);
                                    if (autoReply) {
                                        flushMessageBuffer(userId);
                                        handleMultimodalMessage(userId, item);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("消息监听异常", e);
            }
        }, 0, 3, TimeUnit.SECONDS);

        log.info("消息监听已启动（每3秒轮询，{}ms 缓冲窗口，关键词: {}）", bufferTimeoutMs, batchKeywords);
    }

    private String findVoiceTranscription(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (var item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                String t = extractTextFromItem(item);
                if (t != null && !t.trim().isEmpty()) {
                    return t;
                }
            }
        }
        return null;
    }

    private boolean isVoiceReplyRequest(String message) {
        if (message == null) return false;
        return message.contains("用语音") || message.contains("语音回复") || message.contains("语音回答")
                || message.contains("发语音") || message.contains("用说的") || message.contains("语音播报");
    }

    private void bufferTextMessage(String userId, String text) {
        messageBuffers.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(text);

        ScheduledFuture<?> existing = flushTasks.get(userId);
        if (existing != null) {
            existing.cancel(false);
        }

        String trimmedText = text.trim();
        if (matchesBatchKeyword(trimmedText)) {
            log.info("消息以关键词结尾，立即刷出缓冲: {}", trimmedText);
            flushMessageBuffer(userId);
            return;
        }

        ScheduledFuture<?> task = scheduler.schedule(
                () -> flushMessageBuffer(userId),
                bufferTimeoutMs, TimeUnit.MILLISECONDS
        );
        flushTasks.put(userId, task);
        log.info("缓冲消息 [{}]，{}ms 后合并回复（已累积 {} 条）",
                userId, bufferTimeoutMs, messageBuffers.get(userId).size());
    }

    /**
     * 检查消息是否匹配批量处理关键词
     */
    private boolean matchesBatchKeyword(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (String keyword : batchKeywords) {
            if (text.endsWith(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void flushMessageBuffer(String userId) {
        List<String> buffer = messageBuffers.remove(userId);
        flushTasks.remove(userId);

        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        if (buffer.size() == 1) {
            autoReplyMessage(userId, buffer.get(0));
        } else {
            processBatchReply(userId, buffer);
        }
    }

    private void processBatchReply(String userId, List<String> messages) {
        executor.submit(() -> {
            try {
                String combined = String.join("\n", messages);
                log.info("合并 {} 条消息回复: {} -> {}", messages.size(), userId, combined);

                client.startTyping(userId);

                String reply = aiService.chat(userId, combined);

                Thread.sleep(500);

                if (isVoiceReplyRequest(combined)) {
                    byte[] audioBytes = multimodalService.textToSpeech(reply);
                    if (audioBytes != null) {
                        replyWithVoice(userId, reply, audioBytes);
                    } else {
                        client.sendText(userId, reply);
                    }
                } else {
                    client.sendText(userId, reply);
                }

                client.stopTyping(userId);
                log.info("合并回复已发送");
            } catch (Exception e) {
                log.error("合并回复失败", e);
            }
        });
    }

    private void autoReplyMessage(String userId, String userMessage) {
        executor.submit(() -> {
            try {
                log.info("AI 自动回复: {}", userMessage);
                client.startTyping(userId);

                if (aiService.isImageGenerationRequest(userMessage)) {
                    log.info("检测到图片生成请求");
                    String prompt = aiService.handleImageRequest(userId, userMessage);
                    String result = multimodalService.generateImage(prompt);

                    Thread.sleep(500);

                    if (result.startsWith("IMAGE:")) {
                        String[] parts = result.substring(6).split("\\|", 2);
                        String imagePath = parts[0];
                        String text = parts.length > 1 ? parts[1] : "图片已生成";
                        replyWithImage(userId, text, imagePath);
                    } else {
                        client.sendText(userId, result.startsWith("TEXT:") ? result.substring(5) : result);
                    }
                } else {
                    String reply = aiService.chat(userId, userMessage);
                    Thread.sleep(500);

                    if (isVoiceReplyRequest(userMessage)) {
                        byte[] audioBytes = multimodalService.textToSpeech(reply);
                        if (audioBytes != null) {
                            replyWithVoice(userId, reply, audioBytes);
                        } else {
                            client.sendText(userId, reply);
                        }
                    } else {
                        client.sendText(userId, reply);
                    }
                }

                client.stopTyping(userId);
                log.info("AI 回复已发送");
            } catch (Exception e) {
                log.error("自动回复失败", e);
            }
        });
    }

    /**
     * 处理多模态消息
     */
    private void handleMultimodalMessage(String userId, MessageItem item) {
        executor.submit(() -> {
            try {
                client.startTyping(userId);
                String result = null;
                boolean isVoice = false;

                if (item.getImage_item() != null) {
                    log.info("处理图片消息");
                    byte[] imageBytes = client.downloadImageFromMessageItem(item);
                    result = multimodalService.analyzeImage(userId, imageBytes, "image.jpg");
                }
                else if (item.getVoice_item() != null) {
                    isVoice = true;
                    var voice = item.getVoice_item();
                    log.info("处理语音消息: encode_type={}, playtime={}ms, sample_rate={}",
                            voice.getEncode_type(), voice.getPlaytime(), voice.getSample_rate());
                    byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
                    result = multimodalService.analyzeAudio(userId, voiceBytes, "voice.silk");
                }
                else if (item.getVideo_item() != null) {
                    log.info("处理视频消息");
                    byte[] videoBytes = client.downloadVideoFromMessageItem(item);
                    result = multimodalService.analyzeVideo(userId, videoBytes, "video.mp4");
                }

                if (result != null) {
                    Thread.sleep(500);

                    boolean textSent = false;
                    String textContent = "";
                    if (result.startsWith("TEXT:")) {
                        textContent = result.substring(5);
                        if (!isVoice) {
                            client.sendText(userId, textContent);
                        }
                        textSent = true;
                    } else if (result.startsWith("IMAGE:")) {
                        String[] parts = result.substring(6).split("\\|", 2);
                        String imagePath = parts[0];
                        String text = parts.length > 1 ? parts[1] : "";
                        replyWithImage(userId, text, imagePath);
                    } else if (result.startsWith("VIDEO:")) {
                        String[] parts = result.substring(6).split("\\|", 2);
                        String videoPath = parts[0];
                        String text = parts.length > 1 ? parts[1] : "";
                        replyWithVideo(userId, text, videoPath);
                    } else {
                        client.sendText(userId, result);
                    }
                    
                    log.info("多模态回复已发送");
                    if (isVoice && textSent && !textContent.isEmpty()) {
                        byte[] audioBytes = multimodalService.textToSpeech(textContent);
                        if (audioBytes != null) {
                            replyWithVoice(userId, textContent, audioBytes);
                        } else {
                            client.sendText(userId, textContent);
                        }
                    }
                }
                client.stopTyping(userId);
            } catch (Exception e) {
                log.error("多模态处理失败", e);
            }
        });
    }

    /**
     * 发送图片回复
     */
    public void replyWithImage(String userId, String text, String imagePath) {
        executor.submit(() -> {
            try {
                Path path = Path.of(imagePath);
                if (!Files.exists(path)) {
                    log.warn("图片文件不存在: {}", imagePath);
                    return;
                }
                byte[] bytes = Files.readAllBytes(path);
                client.sendImage(userId, bytes, imagePath, text);
                log.info("图片回复已发送: {}", imagePath);
            } catch (java.io.IOException e) {
                log.error("发送图片失败: IO异常", e);
            } catch (Exception e) {
                log.error("发送图片失败", e);
            }
        });
    }

    /**
     * 发送语音回复
     */
    public void replyWithVoice(String userId, String text, byte[] audioBytes) {
        executor.submit(() -> {
            try {
                client.sendFile(userId, audioBytes, "语音回复.mp3", text);
                log.info("语音回复已发送: {} bytes", audioBytes.length);
            } catch (java.io.IOException e) {
                log.error("发送语音失败: IO异常", e);
                try { client.sendText(userId, text); } catch (Exception ex) { log.error("发送文本失败", ex); }
            } catch (Exception e) {
                log.error("发送语音失败", e);
                try { client.sendText(userId, text); } catch (Exception ex) { log.error("发送文本失败", ex); }
            }
        });
    }

    /**
     * 发送视频回复
     */
    public void replyWithVideo(String userId, String text, String videoPath) {
        executor.submit(() -> {
            try {
                Path path = Path.of(videoPath);
                if (!Files.exists(path)) {
                    log.warn("视频文件不存在: {}", videoPath);
                    try { client.sendText(userId, text); } catch (Exception ex) { log.error("发送文本失败", ex); }
                    return;
                }
                byte[] bytes = Files.readAllBytes(path);
                client.sendVideo(userId, bytes, videoPath, 5000, text);
                log.info("视频回复已发送: {}", videoPath);
            } catch (java.io.IOException e) {
                log.error("发送视频失败: IO异常", e);
                try { client.sendText(userId, text); } catch (Exception ex) { log.error("发送文本失败", ex); }
            } catch (Exception e) {
                log.error("发送视频失败", e);
                try { client.sendText(userId, text); } catch (Exception ex) { log.error("发送文本失败", ex); }
            }
        });
    }

    public void processAndReply(WeixinMessage msg) {
        String userId = msg.getFrom_user_id();
        String text = extractText(msg);
        
        if (text == null || text.trim().isEmpty()) {
            log.info("忽略空消息");
            return;
        }

        if (autoReply) {
            autoReplyMessage(userId, text);
        } else {
            log.info("收到消息: {} -> {}", userId, text);
        }
    }

    /**
     * 从消息项中提取文本
     */
    private String extractTextFromItem(MessageItem item) {
        try {
            if (item.getText_item() == null) {
                return null;
            }
            
            String json = new Gson().toJson(item.getText_item());
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            
            if (obj.has("text")) {
                return obj.get("text").getAsString();
            }
            return null;
        } catch (Exception e) {
            log.error("提取文本失败", e);
            return null;
        }
    }

    /**
     * 从消息中提取文本
     */
    private String extractText(WeixinMessage msg) {
        try {
            log.info("原始消息: {}", msg);
            
            if (msg.getItem_list() == null || msg.getItem_list().isEmpty()) {
                log.warn("消息无 item_list");
                return null;
            }
            
            log.info("item_list 数量: {}", msg.getItem_list().size());
            
            for (var item : msg.getItem_list()) {
                log.info("item 类型: {}", item.getClass().getSimpleName());
                log.info("item 内容: {}", item);
                
                if (item.getText_item() != null) {
                    String json = new Gson().toJson(item.getText_item());
                    log.info("TextItem JSON: {}", json);
                    
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    // 字段名是 text 不是 content
                    if (obj.has("text")) {
                        String text = obj.get("text").getAsString();
                        log.info("提取到文本: {}", text);
                        return text;
                    }
                }
            }
            
            log.warn("未找到文本内容");
            return null;
        } catch (Exception e) {
            log.error("提取文本失败", e);
            return null;
        }
    }

    public boolean sendTextSync(String userId, String text) {
        if (!checkLogin()) return false;
        if (userId == null) {
            log.error("用户ID为空");
            return false;
        }

        try {
            log.info("发送文本: {} -> {}", userId, text);
            client.startTyping(userId);
            Thread.sleep(1000);
            client.sendText(userId, text);
            client.stopTyping(userId);
            log.info("文本发送成功");
            return true;
        } catch (Exception e) {
            log.error("发送文本失败", e);
            return false;
        }
    }

    public CompletableFuture<Boolean> sendText(String userId, String text) {
        return CompletableFuture.supplyAsync(() -> sendTextSync(userId, text), executor);
    }

    public CompletableFuture<Boolean> sendImage(String userId, String filePath) {
        return CompletableFuture.supplyAsync(() -> {
            if (!checkLogin() || !checkFile(filePath)) return false;
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(filePath));
                client.sendImage(userId, bytes, filePath, "图片");
                log.info("图片发送成功: {}", filePath);
                return true;
            } catch (Exception e) {
                log.error("发送图片失败", e);
                return false;
            }
        }, executor);
    }

    public CompletableFuture<Boolean> sendFile(String userId, String filePath) {
        return CompletableFuture.supplyAsync(() -> {
            if (!checkLogin() || !checkFile(filePath)) return false;
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(filePath));
                client.sendFile(userId, bytes, filePath, "文件");
                log.info("文件发送成功: {}", filePath);
                return true;
            } catch (Exception e) {
                log.error("发送文件失败", e);
                return false;
            }
        }, executor);
    }

    public CompletableFuture<Boolean> sendVideo(String userId, String filePath) {
        return CompletableFuture.supplyAsync(() -> {
            if (!checkLogin() || !checkFile(filePath)) return false;
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(filePath));
                client.sendVideo(userId, bytes, filePath, 5000, "视频");
                log.info("视频发送成功: {}", filePath);
                return true;
            } catch (Exception e) {
                log.error("发送视频失败", e);
                return false;
            }
        }, executor);
    }

    public List<WeixinMessage> receiveMessages(int timeoutSeconds) {
        if (!checkLogin()) return List.of();

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000L) {
            try {
                List<WeixinMessage> messages = client.getUpdates();
                if (!messages.isEmpty()) {
                    return messages;
                }
                Thread.sleep(500);
            } catch (Exception e) {
                log.error("接收消息失败", e);
                return List.of();
            }
        }
        return List.of();
    }

    public byte[] downloadMedia(MessageItem item) {
        try {
            if (item.getImage_item() != null) {
                return client.downloadImageFromMessageItem(item);
            }
        } catch (Exception e) {
            log.error("下载媒体失败", e);
        }
        return null;
    }

    private boolean checkLogin() {
        if (!loggedIn.get()) {
            log.error("未登录，请先调用 loginSync()");
            return false;
        }
        return true;
    }

    private boolean checkFile(String filePath) {
        if (!Files.exists(Paths.get(filePath))) {
            log.warn("文件不存在: {}", filePath);
            return false;
        }
        return true;
    }

    @PreDestroy
    public void destroy() {
        log.info("iLinkBot 服务关闭");
        executor.shutdown();
        scheduler.shutdown();
        try {
            client.close();
            log.info("客户端已关闭");
        } catch (Exception e) {
            log.error("关闭客户端失败", e);
        }
    }
}
