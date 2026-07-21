package com.westart.ai.westart.controller;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.westart.ai.westart.service.AIService;
import com.westart.ai.westart.service.ILinkBotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * iLinkBot REST 控制器 - 优化版
 */
@Slf4j
@RestController
@RequestMapping("/api/bot")
public class ILinkBotController {

    @Autowired
    private ILinkBotService botService;

    @Autowired
    private AIService aiService;

    /**
     * 登录
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login() {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean success = botService.loginSync();
            result.put("success", success);
            result.put("message", success ? "登录成功" : "登录失败");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("登录失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 检查登录状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("loggedIn", botService.isLoggedIn());
        result.put("userId", botService.getTargetUserId());
        return ResponseEntity.ok(result);
    }

    /**
     * 发送文本消息
     */
    @PostMapping("/send/text")
    public ResponseEntity<Map<String, Object>> sendText(
            @RequestParam String userId,
            @RequestParam String text) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean success = botService.sendTextSync(userId, text);
            result.put("success", success);
            result.put("message", success ? "消息已发送" : "发送失败");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("发送消息失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 发送图片
     */
    @PostMapping("/send/image")
    public ResponseEntity<Map<String, Object>> sendImage(
            @RequestParam String userId,
            @RequestParam String filePath) {
        Map<String, Object> result = new HashMap<>();
        try {
            botService.sendImage(userId, filePath).get(10, TimeUnit.SECONDS);
            result.put("success", true);
            result.put("message", "图片已发送");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("发送图片失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 发送文件
     */
    @PostMapping("/send/file")
    public ResponseEntity<Map<String, Object>> sendFile(
            @RequestParam String userId,
            @RequestParam String filePath) {
        Map<String, Object> result = new HashMap<>();
        try {
            botService.sendFile(userId, filePath).get(10, TimeUnit.SECONDS);
            result.put("success", true);
            result.put("message", "文件已发送");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("发送文件失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 发送视频
     */
    @PostMapping("/send/video")
    public ResponseEntity<Map<String, Object>> sendVideo(
            @RequestParam String userId,
            @RequestParam String filePath) {
        Map<String, Object> result = new HashMap<>();
        try {
            botService.sendVideo(userId, filePath).get(10, TimeUnit.SECONDS);
            result.put("success", true);
            result.put("message", "视频已发送");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("发送视频失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 接收消息
     */
    @GetMapping("/receive")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestParam(defaultValue = "5") int timeout) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<WeixinMessage> messages = botService.receiveMessages(timeout);
            result.put("success", true);
            result.put("count", messages.size());
            result.put("messages", messages);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("接收消息失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 获取用户ID
     */
    @GetMapping("/user")
    public ResponseEntity<Map<String, Object>> getUser() {
        Map<String, Object> result = new HashMap<>();
        result.put("userId", botService.getTargetUserId());
        result.put("contextToken", botService.getContextToken());
        return ResponseEntity.ok(result);
    }

    /**
     * 清除会话
     */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear(
            @RequestParam String userId) {
        Map<String, Object> result = new HashMap<>();
        aiService.clearConversation(userId);
        result.put("success", true);
        result.put("message", "会话已清除");
        return ResponseEntity.ok(result);
    }
}
