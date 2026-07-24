package com.westart.ai.westart.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.westart.ai.westart.DTO.LoginSessionResult;
import com.westart.ai.westart.service.WeChatAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;

@RestController
@RequestMapping("/wechat")
@RequiredArgsConstructor
public class WeChatAgentController {

    private static final int QR_CODE_WIDTH = 320;
    private static final int QR_CODE_HEIGHT = 320;
    private static final String SESSION_ID_HEADER = "X-WeChat-Session-Id";

    private final WeChatAgentService weChatAgentService;

    @GetMapping("/login")
    public ResponseEntity<Void> loginPage() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/wechat/login.html"))
                .build();
    }

    @GetMapping(value = "/login/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> loginQrCode() {
        LoginSessionResult loginResult = weChatAgentService.userLogin();
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    loginResult.qrCodeContent(),
                    BarcodeFormat.QR_CODE,
                    QR_CODE_WIDTH,
                    QR_CODE_HEIGHT);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", outputStream);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(SESSION_ID_HEADER, loginResult.sessionId())
                    .contentType(MediaType.IMAGE_PNG)
                    .body(outputStream.toByteArray());
        } catch (WriterException | IOException exception) {
            throw new IllegalStateException("生成微信登录二维码失败", exception);
        }
    }

    /**
     * 查询指定微信客户端会话的登录状态。
     *
     * @param sessionId 登录会话唯一标识
     * @return 当前登录状态
     */
    @GetMapping("/login/status")
    public ResponseEntity<LoginStatus> loginStatus(@RequestParam String sessionId) {
        return ResponseEntity.ok(weChatAgentService.getLoginStatus(sessionId));
    }

    /**
     * 关闭指定微信客户端会话。
     *
     * @param sessionId 登录会话唯一标识
     * @return 无响应体
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestParam String sessionId) {
        weChatAgentService.logout(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 使用指定微信客户端会话发送文本消息。
     *
     * @param sessionId iLink客户端会话ID
     * @param userId 微信用户ID
     * @param content 消息内容
     * @return 无响应体
     */
    @PostMapping("/send")
    public ResponseEntity<Void> sendMessage(
            @RequestParam String sessionId,
            @RequestParam String userId,
            @RequestParam String content) {
        weChatAgentService.sendMessage(sessionId, userId, content);
        return ResponseEntity.noContent().build();
    }
}
