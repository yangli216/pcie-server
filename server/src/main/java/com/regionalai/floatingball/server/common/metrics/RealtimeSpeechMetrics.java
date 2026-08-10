package com.regionalai.floatingball.server.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RealtimeSpeechMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeSessions = new AtomicInteger();
    private final Counter capacityRejected;
    private final Counter bufferRejected;

    @Autowired
    public RealtimeSpeechMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("pcie.ai.realtime.sessions.active", activeSessions, AtomicInteger::get)
            .description("Active realtime speech proxy sessions on this node")
            .register(registry);
        this.capacityRejected = Counter.builder("pcie.ai.realtime.sessions.rejected")
            .description("Rejected realtime speech proxy sessions")
            .tag("reason", "capacity")
            .register(registry);
        this.bufferRejected = Counter.builder("pcie.ai.realtime.sessions.rejected")
            .description("Rejected realtime speech proxy sessions")
            .tag("reason", "buffer")
            .register(registry);
    }

    private RealtimeSpeechMetrics() {
        this.registry = null;
        this.capacityRejected = null;
        this.bufferRejected = null;
    }

    public static RealtimeSpeechMetrics noop() {
        return new RealtimeSpeechMetrics();
    }

    public boolean tryAcquire(int maximum) {
        int limit = Math.max(1, maximum);
        while (true) {
            int current = activeSessions.get();
            if (current >= limit) {
                return false;
            }
            if (activeSessions.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release() {
        activeSessions.updateAndGet(current -> Math.max(0, current - 1));
    }

    public void rejected(String reason) {
        if (registry == null) {
            return;
        }
        if ("buffer".equals(reason)) {
            bufferRejected.increment();
        } else {
            capacityRejected.increment();
        }
    }

    public int activeSessions() {
        return activeSessions.get();
    }
}
