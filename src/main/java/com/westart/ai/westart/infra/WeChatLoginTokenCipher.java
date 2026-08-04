package com.westart.ai.westart.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 微信登录令牌加密器，使用AES-256-GCM保护持久化的敏感凭证。
 */
@Component
public class WeChatLoginTokenCipher {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_LENGTH = 32;
    private static final int INITIALIZATION_VECTOR_LENGTH = 12;
    private static final int AUTHENTICATION_TAG_LENGTH = 128;
    private static final byte CIPHERTEXT_VERSION = 1;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec encryptionKey;

    /**
     * 创建微信登录令牌加密器。
     *
     * @param encodedEncryptionKey Base64编码的32字节加密密钥
     */
    public WeChatLoginTokenCipher(
            @Value("${westart.wechat.login-state-encryption-key:}")
            String encodedEncryptionKey) {
        this.encryptionKey = parseEncryptionKey(encodedEncryptionKey);
    }

    /**
     * 加密微信登录令牌。
     *
     * @param token 登录令牌明文
     * @return 携带版本和随机向量的Base64密文
     */
    public String encrypt(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("微信登录令牌不能为空");
        }

        byte[] initializationVector = new byte[INITIALIZATION_VECTOR_LENGTH];
        SECURE_RANDOM.nextBytes(initializationVector);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(AUTHENTICATION_TAG_LENGTH, initializationVector));
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(
                            1 + INITIALIZATION_VECTOR_LENGTH + ciphertext.length)
                    .put(CIPHERTEXT_VERSION)
                    .put(initializationVector)
                    .put(ciphertext)
                    .array();
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("微信登录令牌加密失败", exception);
        }
    }

    /**
     * 解密微信登录令牌。
     *
     * @param encodedCiphertext Base64编码的令牌密文
     * @return 登录令牌明文
     */
    public String decrypt(String encodedCiphertext) {
        if (encodedCiphertext == null || encodedCiphertext.isBlank()) {
            throw new IllegalArgumentException("微信登录令牌密文不能为空");
        }

        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(encodedCiphertext);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("微信登录令牌密文格式错误", exception);
        }
        if (payload.length <= 1 + INITIALIZATION_VECTOR_LENGTH
                || payload[0] != CIPHERTEXT_VERSION) {
            throw new IllegalStateException("微信登录令牌密文版本或长度无效");
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.get();
        byte[] initializationVector = new byte[INITIALIZATION_VECTOR_LENGTH];
        buffer.get(initializationVector);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(AUTHENTICATION_TAG_LENGTH, initializationVector));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("微信登录令牌解密失败", exception);
        }
    }

    /**
     * 解析并校验登录令牌加密密钥。
     *
     * @param encodedEncryptionKey Base64编码的32字节加密密钥
     * @return AES-256密钥
     */
    private static SecretKeySpec parseEncryptionKey(String encodedEncryptionKey) {
        if (encodedEncryptionKey == null || encodedEncryptionKey.isBlank()) {
            throw new IllegalStateException(
                    "未配置westart.wechat.login-state-encryption-key");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedEncryptionKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("微信登录状态加密密钥不是有效的Base64", exception);
        }
        if (keyBytes.length != KEY_LENGTH) {
            throw new IllegalStateException("微信登录状态加密密钥必须为32字节");
        }
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }
}
