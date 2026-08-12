package com.regionalai.floatingball.server.security;

import com.regionalai.floatingball.server.security.nonce.InMemoryNonceStore;
import com.regionalai.floatingball.server.security.nonce.NonceStoreUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSignatureVerifierTest {

    private RequestSignatureVerifier verifier;
    private KeyPair testKeyPair;
    private String publicKeyBase64;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new RequestSignatureVerifier(new InMemoryNonceStore());
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(256);
        testKeyPair = keyGen.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(testKeyPair.getPublic().getEncoded());
    }

    @Test
    void verify_validSignature_returnsOk() throws Exception {
        String method = "POST";
        String path = "/v1/client/bootstrap";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = java.util.UUID.randomUUID().toString();
        String bodySha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        String stringToSign = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodySha256Hex + "\n";

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(testKeyPair.getPrivate());
        sig.update(stringToSign.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = sig.sign();
        String signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes);

        RequestSignatureVerifier.VerificationResult result = verifier.verify(
            "DEV001", publicKeyBase64, method, path, timestamp, nonce, bodySha256Hex, signatureBase64);

        assertTrue(result.isValid());
    }

    @Test
    void verify_expiredTimestamp_returnsFail() throws Exception {
        String method = "GET";
        String path = "/v1/client/bootstrap";
        String timestamp = String.valueOf(System.currentTimeMillis() - 600_000L);
        String nonce = java.util.UUID.randomUUID().toString();
        String bodySha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        String stringToSign = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodySha256Hex + "\n";

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(testKeyPair.getPrivate());
        sig.update(stringToSign.getBytes(StandardCharsets.UTF_8));
        String signatureBase64 = Base64.getEncoder().encodeToString(sig.sign());

        RequestSignatureVerifier.VerificationResult result = verifier.verify(
            "DEV001", publicKeyBase64, method, path, timestamp, nonce, bodySha256Hex, signatureBase64);

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("时间戳"));
    }

    @Test
    void verify_replayedNonce_returnsFail() throws Exception {
        String method = "GET";
        String path = "/v1/client/bootstrap";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = java.util.UUID.randomUUID().toString();
        String bodySha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        String stringToSign = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodySha256Hex + "\n";

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(testKeyPair.getPrivate());
        sig.update(stringToSign.getBytes(StandardCharsets.UTF_8));
        String signatureBase64 = Base64.getEncoder().encodeToString(sig.sign());

        RequestSignatureVerifier.VerificationResult first = verifier.verify(
            "DEV001", publicKeyBase64, method, path, timestamp, nonce, bodySha256Hex, signatureBase64);
        assertTrue(first.isValid());

        RequestSignatureVerifier.VerificationResult second = verifier.verify(
            "DEV001", publicKeyBase64, method, path, timestamp, nonce, bodySha256Hex, signatureBase64);
        assertFalse(second.isValid());
        assertTrue(second.getErrorMessage().contains("随机数已使用"));
    }

    @Test
    void verify_tamperedBody_returnsFail() throws Exception {
        String method = "POST";
        String path = "/v1/client/bootstrap";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = java.util.UUID.randomUUID().toString();
        String originalBodyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String tamperedBodyHash = "a3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        String stringToSign = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + originalBodyHash + "\n";

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(testKeyPair.getPrivate());
        sig.update(stringToSign.getBytes(StandardCharsets.UTF_8));
        String signatureBase64 = Base64.getEncoder().encodeToString(sig.sign());

        RequestSignatureVerifier.VerificationResult result = verifier.verify(
            "DEV001", publicKeyBase64, method, path, timestamp, nonce, tamperedBodyHash, signatureBase64);

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("签名验证失败"));
    }

    @Test
    void verify_missingPublicKey_returnsFail() {
        RequestSignatureVerifier.VerificationResult result = verifier.verify(
            "DEV001", null, "GET", "/v1/client/bootstrap", String.valueOf(System.currentTimeMillis()),
            "nonce", "hash", "sig");

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("未注册公钥"));
    }

    @Test
    void verify_invalidSignatureBase64_returnsFail() throws Exception {
        String method = "GET";
        String path = "/v1/client/bootstrap";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = java.util.UUID.randomUUID().toString();
        String bodySha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        RequestSignatureVerifier.VerificationResult result = verifier.verify(
            "DEV001", publicKeyBase64, method, path, timestamp, nonce, bodySha256Hex, "not-valid-base64!!!");

        assertFalse(result.isValid());
    }

    @Test
    void verify_rawEcdsaSignature_returnsOk() throws Exception {
        String method = "POST";
        String path = "/v1/ai/chat";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = java.util.UUID.randomUUID().toString();
        String bodySha256Hex = "abc123";

        String stringToSign = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodySha256Hex + "\n";

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(testKeyPair.getPrivate());
        sig.update(stringToSign.getBytes(StandardCharsets.UTF_8));
        byte[] derSig = sig.sign();

        byte[] rawSig = convertDerToRaw(derSig);
        String signatureBase64 = Base64.getEncoder().encodeToString(rawSig);

        RequestSignatureVerifier.VerificationResult result = verifier.verify(
            "DEV001", publicKeyBase64, method, path, timestamp, nonce, bodySha256Hex, signatureBase64);

        assertTrue(result.isValid());
    }

    @Test
    void verify_sameNonceForDifferentDevices_returnsOk() throws Exception {
        String method = "GET";
        String path = "/v1/client/bootstrap";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = java.util.UUID.randomUUID().toString();
        String bodySha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String signatureBase64 = sign(method, path, timestamp, nonce, bodySha256Hex);

        RequestSignatureVerifier.VerificationResult first = verifier.verify(
            "DEV001", publicKeyBase64, method, path, timestamp, nonce, bodySha256Hex, signatureBase64);
        RequestSignatureVerifier.VerificationResult second = verifier.verify(
            "DEV002", publicKeyBase64, method, path, timestamp, nonce, bodySha256Hex, signatureBase64);

        assertTrue(first.isValid());
        assertTrue(second.isValid());
    }

    @Test
    void verify_sameDeviceNonceIsSharedAcrossHttpAndWebSocketPaths() throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = java.util.UUID.randomUUID().toString();
        String bodySha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        RequestSignatureVerifier.VerificationResult http = verifier.verify(
            "DEV001", publicKeyBase64, "GET", "/v1/client/bootstrap", timestamp, nonce,
            bodySha256Hex, sign("GET", "/v1/client/bootstrap", timestamp, nonce, bodySha256Hex));
        RequestSignatureVerifier.VerificationResult websocket = verifier.verify(
            "DEV001", publicKeyBase64, "GET", "/v1/ai/speech/realtime/ws", timestamp, nonce,
            bodySha256Hex, sign("GET", "/v1/ai/speech/realtime/ws", timestamp, nonce, bodySha256Hex));

        assertTrue(http.isValid());
        assertFalse(websocket.isValid());
        assertTrue(websocket.getErrorMessage().contains("随机数已使用"));
    }

    @Test
    void verify_invalidSignature_doesNotClaimNonce() throws Exception {
        final int[] claims = new int[1];
        verifier = new RequestSignatureVerifier((deviceId, nonce, expiresAtEpochMs) -> {
            claims[0]++;
            return true;
        });

        RequestSignatureVerifier.VerificationResult result = verifier.verify(
            "DEV001", publicKeyBase64, "GET", "/v1/client/bootstrap",
            String.valueOf(System.currentTimeMillis()), "nonce-1",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "not-valid-base64!!!");

        assertFalse(result.isValid());
        assertEquals(0, claims[0]);
    }

    @Test
    void verify_nonceStoreUnavailable_returnsDedicatedResult() throws Exception {
        verifier = new RequestSignatureVerifier((deviceId, nonce, expiresAtEpochMs) -> {
            throw new NonceStoreUnavailableException("down", new IllegalStateException("db down"));
        });
        String method = "GET";
        String path = "/v1/client/bootstrap";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = java.util.UUID.randomUUID().toString();
        String bodyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        RequestSignatureVerifier.VerificationResult result = verifier.verify(
            "DEV001",
            publicKeyBase64,
            method,
            path,
            timestamp,
            nonce,
            bodyHash,
            sign(method, path, timestamp, nonce, bodyHash)
        );

        assertFalse(result.isValid());
        assertTrue(result.isStoreUnavailable());
        assertTrue(result.getErrorMessage().contains("暂时不可用"));
    }

    @Test
    void verify_nonceLongerThan64Characters_isRejectedBeforeClaim() {
        final int[] claims = new int[1];
        verifier = new RequestSignatureVerifier((deviceId, nonce, expiresAtEpochMs) -> {
            claims[0]++;
            return true;
        });

        RequestSignatureVerifier.VerificationResult result = verifier.verify(
            "DEV001", publicKeyBase64, "GET", "/v1/client/bootstrap",
            String.valueOf(System.currentTimeMillis()),
            "12345678901234567890123456789012345678901234567890123456789012345",
            "hash", "signature");

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("长度"));
        assertEquals(0, claims[0]);
    }

    private String sign(String method,
                        String path,
                        String timestamp,
                        String nonce,
                        String bodySha256Hex) throws Exception {
        String stringToSign = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodySha256Hex + "\n";
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(testKeyPair.getPrivate());
        sig.update(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private static byte[] convertDerToRaw(byte[] derSig) {
        int idx = 2;
        int rLen = derSig[idx + 1];
        byte[] r = new byte[32];
        int rOffset = idx + 2 + (rLen > 32 ? rLen - 32 : 0);
        System.arraycopy(derSig, rOffset, r, 32 - (rLen > 32 ? 32 : rLen), rLen > 32 ? 32 : rLen);

        idx = idx + 2 + rLen;
        int sLen = derSig[idx + 1];
        byte[] s = new byte[32];
        int sOffset = idx + 2 + (sLen > 32 ? sLen - 32 : 0);
        System.arraycopy(derSig, sOffset, s, 32 - (sLen > 32 ? 32 : sLen), sLen > 32 ? 32 : sLen);

        byte[] raw = new byte[64];
        System.arraycopy(r, 0, raw, 0, 32);
        System.arraycopy(s, 0, raw, 32, 32);
        return raw;
    }
}
