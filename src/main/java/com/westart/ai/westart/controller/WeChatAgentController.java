package com.westart.ai.westart.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
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

    private final WeChatAgentService weChatAgentService;

    @GetMapping("/login")
    public ResponseEntity<Void> loginPage() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/wechat/login.html"))
                .build();
    }

    @GetMapping(value = "/login/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> loginQrCode() {
        String qrCodeContent = weChatAgentService.userLogin();
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    qrCodeContent, BarcodeFormat.QR_CODE, QR_CODE_WIDTH, QR_CODE_HEIGHT);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", outputStream);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(MediaType.IMAGE_PNG)
                    .body(outputStream.toByteArray());
        } catch (WriterException | IOException exception) {
            throw new IllegalStateException("生成微信登录二维码失败", exception);
        }
    }

    @PostMapping("/send")
    public ResponseEntity<Void> sendMessage(
            @RequestParam String userId,
            @RequestParam String content) {
        weChatAgentService.sendMessage(userId, content);
        return ResponseEntity.noContent().build();
    }
}
