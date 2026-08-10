package com.regionalai.floatingball.server.security.nonce;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "floating-ball.cluster.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryNonceStore implements NonceStore {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<NonceKey, Long> nonces = new ConcurrentHashMap<NonceKey, Long>();

    @Override
    public boolean claim(String deviceId, String nonce, long expiresAtEpochMs) {
        long now = System.currentTimeMillis();
        evictExpiredNonces(now);

        NonceKey key = new NonceKey(deviceId, nonce);
        while (true) {
            Long existingExpiry = nonces.putIfAbsent(key, expiresAtEpochMs);
            if (existingExpiry == null) {
                return true;
            }
            if (existingExpiry > now) {
                return false;
            }
            if (nonces.replace(key, existingExpiry, expiresAtEpochMs)) {
                return true;
            }
        }
    }

    private void evictExpiredNonces(long now) {
        if (nonces.size() < CLEANUP_THRESHOLD) {
            return;
        }
        Iterator<Map.Entry<NonceKey, Long>> iterator = nonces.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<NonceKey, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                nonces.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private static final class NonceKey {
        private final String deviceId;
        private final String nonce;

        private NonceKey(String deviceId, String nonce) {
            this.deviceId = deviceId;
            this.nonce = nonce;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof NonceKey)) {
                return false;
            }
            NonceKey that = (NonceKey) other;
            return Objects.equals(deviceId, that.deviceId) && Objects.equals(nonce, that.nonce);
        }

        @Override
        public int hashCode() {
            return Objects.hash(deviceId, nonce);
        }
    }
}
