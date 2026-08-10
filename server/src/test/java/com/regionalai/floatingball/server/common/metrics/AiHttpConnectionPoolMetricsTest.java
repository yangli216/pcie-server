package com.regionalai.floatingball.server.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiHttpConnectionPoolMetricsTest {

    @Test
    void shouldExposeLowCardinalityPoolStateGauges() {
        PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
        manager.setMaxTotal(17);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new AiHttpConnectionPoolMetrics(manager).bindTo(registry);

        assertThat(registry.get("pcie.ai.http.pool.connections").tag("state", "max").gauge().value())
            .isEqualTo(17.0d);
        assertThat(registry.get("pcie.ai.http.pool.connections").tag("state", "leased").gauge().value())
            .isZero();
        assertThat(registry.get("pcie.ai.http.pool.connections").tag("state", "pending").gauge().value())
            .isZero();
        manager.close();
    }
}
