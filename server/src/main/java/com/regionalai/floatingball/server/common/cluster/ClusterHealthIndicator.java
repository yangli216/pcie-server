package com.regionalai.floatingball.server.common.cluster;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("clusterStorage")
public class ClusterHealthIndicator implements HealthIndicator {

    private final ClusterProperties properties;
    private final ClusterStorageValidator validator;

    public ClusterHealthIndicator(ClusterProperties properties, ClusterStorageValidator validator) {
        this.properties = properties;
        this.validator = validator;
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.up().withDetail("mode", "standalone").build();
        }
        try {
            validator.validate();
            return Health.up()
                .withDetail("mode", "cluster")
                .withDetail("clusterId", properties.getClusterId())
                .withDetail("nodeId", properties.getNodeId())
                .withDetail("releaseWriterNodeId", properties.getReleaseWriterNodeId())
                .withDetail("releaseWriter", properties.isReleaseWriterNode())
                .build();
        } catch (RuntimeException ex) {
            return Health.down(ex)
                .withDetail("clusterId", properties.getClusterId())
                .withDetail("nodeId", properties.getNodeId())
                .build();
        }
    }
}
