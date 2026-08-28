package com.regionalai.floatingball.server.modules.auth.bbp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class BbpPasswordUtils {

    private BbpPasswordUtils() {
    }

    static String md5Once(String password) {
        String trimmed = password == null ? "" : password.trim();
        if (trimmed.matches("(?i)^[0-9a-f]{32}$")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest((password == null ? "" : password).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte value : bytes) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成 BBP 密码摘要", ex);
        }
    }
}
