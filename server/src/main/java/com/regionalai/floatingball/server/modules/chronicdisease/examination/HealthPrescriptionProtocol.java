package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Wire compatibility with rbmh-cdw; never fall back to plaintext. */
final class HealthPrescriptionProtocol {
    private static final Provider PROVIDER = new BouncyCastleProvider();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'+0800'")
        .withZone(ZoneOffset.ofHours(8));

    static String date(Instant instant) { return DATE.format(instant); }

    static String sign(String ak, String sk, String date) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        // Login uses SK TEXT; SM4 below uses decoded hexadecimal bytes.
        mac.init(new SecretKeySpec(sk.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Hex.toHexString(mac.doFinal((ak + "\n" + date).getBytes(StandardCharsets.UTF_8)));
    }

    static String encrypt(String plain, String sk) throws Exception {
        return Hex.toHexString(cipher(Cipher.ENCRYPT_MODE, sk).doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    static String decrypt(String encrypted, String sk) throws Exception {
        if (encrypted == null || encrypted.isEmpty() || encrypted.length() > 4 * 1024 * 1024
            || encrypted.length() % 32 != 0 || !encrypted.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("Invalid ciphertext");
        }
        byte[] plain = cipher(Cipher.DECRYPT_MODE, sk).doFinal(Hex.decode(encrypted));
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString();
    }

    private static Cipher cipher(int mode, String sk) throws Exception {
        if (sk == null || !sk.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("Invalid key configuration");
        Cipher cipher = Cipher.getInstance("SM4/ECB/PKCS7Padding", PROVIDER);
        cipher.init(mode, new SecretKeySpec(Hex.decode(sk), "SM4"));
        return cipher;
    }
}
