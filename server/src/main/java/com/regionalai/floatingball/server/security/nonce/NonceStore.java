package com.regionalai.floatingball.server.security.nonce;

/**
 * Atomically records request nonces for replay protection.
 */
public interface NonceStore {

    /**
     * @return {@code true} when this device/nonce pair was claimed by this call,
     *         {@code false} when it was already claimed.
     * @throws NonceStoreUnavailableException when the store cannot make a safe decision
     */
    boolean claim(String deviceId, String nonce, long expiresAtEpochMs);
}
