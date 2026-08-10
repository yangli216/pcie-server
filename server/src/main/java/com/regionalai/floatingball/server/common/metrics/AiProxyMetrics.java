package com.regionalai.floatingball.server.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AiProxyMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger blockingActive = new AtomicInteger();
    private final AtomicInteger streamingActive = new AtomicInteger();

    @Autowired
    public AiProxyMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("pcie.ai.proxy.active", blockingActive, AtomicInteger::get)
            .description("Active PCIE AI proxy tasks")
            .tag("mode", "blocking")
            .register(registry);
        Gauge.builder("pcie.ai.proxy.active", streamingActive, AtomicInteger::get)
            .description("Active PCIE AI proxy tasks")
            .tag("mode", "stream")
            .register(registry);
    }

    private AiProxyMetrics() {
        this.registry = null;
    }

    public static AiProxyMetrics noop() {
        return new AiProxyMetrics();
    }

    public void started(String mode) {
        activeCounter(mode).incrementAndGet();
        increment("started", mode);
    }

    public void succeeded(String mode) {
        decrementActive(mode);
        increment("succeeded", mode);
    }

    public void failed(String mode) {
        decrementActive(mode);
        increment("failed", mode);
    }

    public void rejected(String mode) {
        increment("rejected", mode);
    }

    public void cancelled(String mode) {
        increment("cancelled", mode);
    }

    public void finishedAfterCancellation(String mode) {
        decrementActive(mode);
    }

    private AtomicInteger activeCounter(String mode) {
        return "stream".equals(mode) ? streamingActive : blockingActive;
    }

    private void decrementActive(String mode) {
        activeCounter(mode).updateAndGet(current -> Math.max(0, current - 1));
    }

    private void increment(String outcome, String mode) {
        if (registry != null) {
            registry.counter("pcie.ai.proxy.requests", "mode", mode, "outcome", outcome).increment();
        }
    }
}
