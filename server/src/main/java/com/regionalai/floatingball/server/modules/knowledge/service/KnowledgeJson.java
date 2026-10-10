package com.regionalai.floatingball.server.modules.knowledge.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class KnowledgeJson {
    private KnowledgeJson() {}

    static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = input.read(block)) != -1) {
            if (buffer.size() > maxBytes - count) throw new IOException("knowledge response too large");
            buffer.write(block, 0, count);
        }
        return buffer.toByteArray();
    }

    static String sha256(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format("%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
