package com.regionalai.floatingball.server.security.nonce;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void claim_sameDeviceAndNonceConcurrently_succeedsOnlyOnce() throws Exception {
        InMemoryNonceStore store = new InMemoryNonceStore();
        int workerCount = 32;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<Future<Boolean>>();

        try {
            for (int i = 0; i < workerCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.claim(
                        "DEV001", "nonce-concurrent", System.currentTimeMillis() + 60_000L);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int successfulClaims = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    successfulClaims++;
                }
            }
            assertEquals(1, successfulClaims);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
