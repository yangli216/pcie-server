package com.regionalai.floatingball.server.common.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "floating-ball.cluster")
public class ClusterProperties {

    private boolean enabled;
    private String nodeId = "standalone";
    private String clusterId = "standalone";
    private boolean sharedStorageRequired = true;
    // Retained only for configuration compatibility. Writer selection is derived from node IDs.
    private boolean releaseWriterEnabled = true;
    private String releaseWriterNodeId;
    private String markerFileName = ".pcie-cluster-id";
    private int storageChallengeTtlSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public boolean isSharedStorageRequired() {
        return sharedStorageRequired;
    }

    public void setSharedStorageRequired(boolean sharedStorageRequired) {
        this.sharedStorageRequired = sharedStorageRequired;
    }

    public boolean isReleaseWriterEnabled() {
        return releaseWriterEnabled;
    }

    public void setReleaseWriterEnabled(boolean releaseWriterEnabled) {
        this.releaseWriterEnabled = releaseWriterEnabled;
    }

    public String getReleaseWriterNodeId() {
        return releaseWriterNodeId;
    }

    public void setReleaseWriterNodeId(String releaseWriterNodeId) {
        this.releaseWriterNodeId = releaseWriterNodeId;
    }

    public boolean isReleaseWriterNode() {
        if (!enabled) {
            return true;
        }
        return nodeId != null && nodeId.equals(releaseWriterNodeId);
    }

    public String getMarkerFileName() {
        return markerFileName;
    }

    public void setMarkerFileName(String markerFileName) {
        this.markerFileName = markerFileName;
    }

    public int getStorageChallengeTtlSeconds() {
        return storageChallengeTtlSeconds;
    }

    public void setStorageChallengeTtlSeconds(int storageChallengeTtlSeconds) {
        this.storageChallengeTtlSeconds = storageChallengeTtlSeconds;
    }
}
