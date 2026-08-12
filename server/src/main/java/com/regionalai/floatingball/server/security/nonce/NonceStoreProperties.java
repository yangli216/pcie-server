package com.regionalai.floatingball.server.security.nonce;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.Min;

@Validated
@ConfigurationProperties(prefix = "floating-ball.security.nonce")
public class NonceStoreProperties {

    public enum Store {
        MEMORY,
        DATABASE
    }

    private Store store = Store.MEMORY;

    @Min(1_000L)
    private long cleanupIntervalMs = 60_000L;

    @Min(300_000L)
    private long cleanupGraceMs = 300_000L;

    @Min(60_000L)
    private long deepProbeIntervalMs = 300_000L;

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    public long getCleanupGraceMs() {
        return cleanupGraceMs;
    }

    public void setCleanupGraceMs(long cleanupGraceMs) {
        this.cleanupGraceMs = cleanupGraceMs;
    }

    public boolean isDatabase() {
        return store == Store.DATABASE;
    }

    public long getDeepProbeIntervalMs() {
        return deepProbeIntervalMs;
    }

    public void setDeepProbeIntervalMs(long deepProbeIntervalMs) {
        this.deepProbeIntervalMs = deepProbeIntervalMs;
    }
}
