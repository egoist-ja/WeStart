package com.westart.ai.westart.infra;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WeChatLoginTokenCipherTest {

    private static final String ENCODED_KEY = Base64.getEncoder()
            .encodeToString(new byte[32]);

    @Test
    void shouldDecryptEncryptedToken() {
        WeChatLoginTokenCipher tokenCipher = new WeChatLoginTokenCipher(ENCODED_KEY);

        String ciphertext = tokenCipher.encrypt("test-token");

        assertEquals("test-token", tokenCipher.decrypt(ciphertext));
    }

    @Test
    void shouldUseRandomInitializationVector() {
        WeChatLoginTokenCipher tokenCipher = new WeChatLoginTokenCipher(ENCODED_KEY);

        String firstCiphertext = tokenCipher.encrypt("test-token");
        String secondCiphertext = tokenCipher.encrypt("test-token");

        assertNotEquals(firstCiphertext, secondCiphertext);
    }
}
