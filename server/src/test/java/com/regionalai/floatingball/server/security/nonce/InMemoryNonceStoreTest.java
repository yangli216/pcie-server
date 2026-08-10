package com.regionalai.floatingball.server.security.nonce;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryNonceStoreTest {

    @Test
    void claim_isUniquePerDeviceAndNonce() {
        InMemoryNonceStore store = new InMemoryNonceStore();
        long expiresAt = System.currentTimeMillis() + 60_000L;

        assertTrue(store.claim("DEV001", "nonce-1", expiresAt));
        assertFalse(store.claim("DEV001", "nonce-1", expiresAt));
        assertTrue(store.claim("DEV002", "nonce-1", expiresAt));
    }

    @Test
    void claim_replacesExpiredEntryAtomically() {
        InMemoryNonceStore store = new InMemoryNonceStore();

        assertTrue(store.claim("DEV001", "nonce-1", System.currentTimeMillis() - 1L));
        assertTrue(store.claim("DEV001", "nonce-1", System.currentTimeMillis() + 60_000L));
        assertFalse(store.claim("DEV001", "nonce-1", System.currentTimeMillis() + 60_000L));
    }
}
