package com.regionalai.floatingball.server.security.nonce;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Latches whether the database nonce store has passed its latest integrity probe.
 */
@Component
public class DatabaseNonceStoreAvailability {

    private final AtomicBoolean trusted = new AtomicBoolean(false);

    public boolean isTrusted() {
        return trusted.get();
    }

    void markTrusted() {
        trusted.set(true);
    }

    void markUntrusted() {
        trusted.set(false);
    }

    void requireTrusted() {
        if (!trusted.get()) {
            throw new NonceStoreUnavailableException(
                "request nonce store has not passed its latest database integrity probe",
                new IllegalStateException("database nonce store is untrusted")
            );
        }
    }
}
