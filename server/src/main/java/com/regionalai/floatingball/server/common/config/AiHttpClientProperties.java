package com.regionalai.floatingball.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "floating-ball.ai.http")
public class AiHttpClientProperties {

    private int maxTotal = 128;
    private int maxPerRoute = 112;
    private int connectionRequestTimeoutMs = 500;
    private int validateAfterInactivityMs = 5000;
    private int idleEvictSeconds = 30;

    public int getMaxTotal() {
        return maxTotal;
    }

    public void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    public int getMaxPerRoute() {
        return maxPerRoute;
    }

    public void setMaxPerRoute(int maxPerRoute) {
        this.maxPerRoute = maxPerRoute;
    }

    public int getConnectionRequestTimeoutMs() {
        return connectionRequestTimeoutMs;
    }

    public void setConnectionRequestTimeoutMs(int connectionRequestTimeoutMs) {
        this.connectionRequestTimeoutMs = connectionRequestTimeoutMs;
    }

    public int getValidateAfterInactivityMs() {
        return validateAfterInactivityMs;
    }

    public void setValidateAfterInactivityMs(int validateAfterInactivityMs) {
        this.validateAfterInactivityMs = validateAfterInactivityMs;
    }

    public int getIdleEvictSeconds() {
        return idleEvictSeconds;
    }

    public void setIdleEvictSeconds(int idleEvictSeconds) {
        this.idleEvictSeconds = idleEvictSeconds;
    }
}
