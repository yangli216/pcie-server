package com.regionalai.floatingball.server.common.deployment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "floating-ball.deployment")
public class DeploymentProperties {

    public static final String STANDALONE = "standalone";
    public static final String AI_SCALE_OUT = "ai-scale-out";

    private String mode = STANDALONE;
    private String nodeId = "";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String normalizedMode() {
        return normalize(mode);
    }

    public boolean isAiScaleOut() {
        return AI_SCALE_OUT.equals(normalizedMode());
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
