package com.regionalai.floatingball.server.security;

import com.regionalai.floatingball.server.security.nonce.NonceStore;
import com.regionalai.floatingball.server.security.nonce.NonceStoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Component
public class RequestSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(RequestSignatureVerifier.class);

    static final long MAX_SKEW_MS = 300_000L;
    private static final String ECDSA_ALGORITHM = "SHA256withECDSA";
    private static final String KEY_ALGORITHM = "EC";
    private static final int MAX_NONCE_LENGTH = 64;

    private final NonceStore nonceStore;

    public RequestSignatureVerifier(NonceStore nonceStore) {
        this.nonceStore = nonceStore;
    }

    public VerificationResult verify(String deviceId,
                                      String publicKeyBase64,
                                      String method,
                                      String path,
                                      String timestamp,
                                      String nonce,
                                      String bodySha256Hex,
                                      String signatureBase64) {
        if (publicKeyBase64 == null || publicKeyBase64.isEmpty()) {
            return VerificationResult.fail("设备未注册公钥");
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return VerificationResult.fail("时间戳格式无效");
        }
        long now = System.currentTimeMillis();
        if (ts < now - MAX_SKEW_MS || ts > now + MAX_SKEW_MS) {
            return VerificationResult.fail("请求时间戳超出有效窗口");
        }

        if (nonce == null || nonce.isEmpty()) {
            return VerificationResult.fail("缺少随机数");
        }
        if (nonce.length() > MAX_NONCE_LENGTH) {
            return VerificationResult.fail("随机数长度超过限制");
        }
        if (deviceId == null || deviceId.isEmpty()) {
            return VerificationResult.fail("缺少设备标识");
        }

        String stringToSign = method + "\n"
                            + path + "\n"
                            + timestamp + "\n"
                            + nonce + "\n"
                            + (bodySha256Hex != null ? bodySha256Hex : "") + "\n";

        try {
            byte[] pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(pubKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            java.security.PublicKey publicKey = keyFactory.generatePublic(keySpec);

            Signature sig = Signature.getInstance(ECDSA_ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(stringToSign.getBytes(StandardCharsets.UTF_8));

            byte[] sigBytes = Base64.getDecoder().decode(signatureBase64);
            if (sigBytes.length == 64) {
                sigBytes = convertRawEcdsaToDer(sigBytes);
            }

            boolean valid = sig.verify(sigBytes);

            if (!valid) {
                return VerificationResult.fail("签名验证失败");
            }

        } catch (Exception e) {
            log.warn("signature verification error: {}", e.getMessage());
            return VerificationResult.fail("签名验证异常: " + e.getMessage());
        }

        try {
            boolean claimed = nonceStore.claim(deviceId, nonce, ts + MAX_SKEW_MS);
            return claimed
                ? VerificationResult.ok()
                : VerificationResult.fail("随机数已使用，疑似重放攻击");
        } catch (NonceStoreUnavailableException ex) {
            log.error("request nonce store unavailable for deviceId={}: {}", deviceId, ex.getMessage());
            return VerificationResult.storeUnavailable();
        }
    }

    static byte[] convertRawEcdsaToDer(byte[] rawSig) {
        byte[] r = new byte[32];
        byte[] s = new byte[32];
        System.arraycopy(rawSig, 0, r, 0, 32);
        System.arraycopy(rawSig, 32, s, 0, 32);
        return encodeDer(r, s);
    }

    private static byte[] encodeDer(byte[] r, byte[] s) {
        int rStart = 0;
        while (rStart < r.length - 1 && r[rStart] == 0) rStart++;
        int sStart = 0;
        while (sStart < s.length - 1 && s[sStart] == 0) sStart++;

        boolean rPad = (r[rStart] & 0x80) != 0;
        boolean sPad = (s[sStart] & 0x80) != 0;

        int rLen = r.length - rStart + (rPad ? 1 : 0);
        int sLen = s.length - sStart + (sPad ? 1 : 0);
        int totalLen = 2 + rLen + 2 + sLen;

        byte[] der = new byte[2 + totalLen];
        int idx = 0;
        der[idx++] = 0x30;
        der[idx++] = (byte) totalLen;
        der[idx++] = 0x02;
        der[idx++] = (byte) rLen;
        if (rPad) der[idx++] = 0x00;
        System.arraycopy(r, rStart, der, idx, r.length - rStart);
        idx += r.length - rStart;
        der[idx++] = 0x02;
        der[idx++] = (byte) sLen;
        if (sPad) der[idx++] = 0x00;
        System.arraycopy(s, sStart, der, idx, s.length - sStart);

        return der;
    }

    public static class VerificationResult {
        private final boolean valid;
        private final boolean storeUnavailable;
        private final String errorMessage;

        private VerificationResult(boolean valid, boolean storeUnavailable, String errorMessage) {
            this.valid = valid;
            this.storeUnavailable = storeUnavailable;
            this.errorMessage = errorMessage;
        }

        static VerificationResult ok() { return new VerificationResult(true, false, null); }
        static VerificationResult fail(String msg) { return new VerificationResult(false, false, msg); }
        static VerificationResult storeUnavailable() {
            return new VerificationResult(false, true, "请求安全校验服务暂时不可用");
        }

        public boolean isValid() { return valid; }
        public boolean isStoreUnavailable() { return storeUnavailable; }
        public String getErrorMessage() { return errorMessage; }
    }
}
