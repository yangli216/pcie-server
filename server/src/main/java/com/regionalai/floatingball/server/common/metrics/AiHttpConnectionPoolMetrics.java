package com.regionalai.floatingball.server.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.pool.PoolStats;
import org.springframework.stereotype.Component;

@Component
public class AiHttpConnectionPoolMetrics implements MeterBinder {

    private final PoolingHttpClientConnectionManager connectionManager;

    public AiHttpConnectionPoolMetrics(PoolingHttpClientConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("pcie.ai.http.pool.connections", connectionManager,
                manager -> stats(manager).getLeased())
            .description("AI HTTP connection pool state")
            .tag("state", "leased")
            .register(registry);
        Gauge.builder("pcie.ai.http.pool.connections", connectionManager,
                manager -> stats(manager).getAvailable())
            .description("AI HTTP connection pool state")
            .tag("state", "available")
            .register(registry);
        Gauge.builder("pcie.ai.http.pool.connections", connectionManager,
                manager -> stats(manager).getPending())
            .description("AI HTTP connection pool state")
            .tag("state", "pending")
            .register(registry);
        Gauge.builder("pcie.ai.http.pool.connections", connectionManager,
                manager -> stats(manager).getMax())
            .description("AI HTTP connection pool state")
            .tag("state", "max")
            .register(registry);
    }

    private static PoolStats stats(PoolingHttpClientConnectionManager manager) {
        return manager.getTotalStats();
    }
}
