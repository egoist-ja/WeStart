package com.westart.ai.westart.util;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;

public class GenerateWeatherJWT {

    /**
     * 生成天气JWT验证
     * @return
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeySpecException
     * @throws InvalidKeyException
     * @throws SignatureException
     */
    public static String generateJWT() throws NoSuchAlgorithmException, InvalidKeySpecException, InvalidKeyException, SignatureException {
        // Private key
        String privateKeyString = System.getenv("PRIVATE_KEY");
        privateKeyString = privateKeyString.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").trim();
        byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyString);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("EdDSA");
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

        // Header
        String headerJson = "{\"alg\": \"EdDSA\", \"kid\": \""+System.getenv("KID")+"\"}";

        // Payload
        long iat = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond() - 30;
        long exp = iat + 86400;
        String payloadJson = "{\"sub\": \""+System.getenv("SUB")+"\", \"iat\": " + iat + ", \"exp\": " + exp + "}";

        // Base64url header+payload
        String headerEncoded = Base64.getUrlEncoder().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadEncoded = Base64.getUrlEncoder().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String data = headerEncoded + "." + payloadEncoded;

        // Sign
        Signature signer = Signature.getInstance("EdDSA");
        signer.initSign(privateKey);
        signer.update(data.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();

        String signatureEncoded = Base64.getUrlEncoder().encodeToString(signature);

        return data + "." + signatureEncoded;
    }
}
