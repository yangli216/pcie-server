package com.regionalai.floatingball.server.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProxyMetricsTest {

    @Test
    void recordsActiveRequestsAndBoundedOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiProxyMetrics metrics = new AiProxyMetrics(registry);

        metrics.started("chat");
        assertThat(registry.get("pcie.ai.proxy.active").tag("mode", "blocking").gauge().value()).isEqualTo(1D);

        metrics.succeeded("chat");
        metrics.rejected("speech");
        assertThat(registry.get("pcie.ai.proxy.active").tag("mode", "blocking").gauge().value()).isZero();
        assertThat(registry.get("pcie.ai.proxy.requests").tags("mode", "chat", "outcome", "succeeded").counter().count())
            .isEqualTo(1D);
        assertThat(registry.get("pcie.ai.proxy.requests").tags("mode", "speech", "outcome", "rejected").counter().count())
            .isEqualTo(1D);
    }
}
