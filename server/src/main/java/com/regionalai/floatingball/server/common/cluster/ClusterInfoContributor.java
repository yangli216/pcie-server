package com.regionalai.floatingball.server.common.cluster;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ClusterInfoContributor implements InfoContributor {

    private final ClusterProperties properties;

    public ClusterInfoContributor(ClusterProperties properties) {
        this.properties = properties;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("enabled", properties.isEnabled());
        details.put("clusterId", properties.getClusterId());
        details.put("nodeId", properties.getNodeId());
        details.put("releaseWriterNodeId", properties.getReleaseWriterNodeId());
        details.put("releaseWriter", properties.isReleaseWriterNode());
        builder.withDetail("cluster", details);
    }
}
