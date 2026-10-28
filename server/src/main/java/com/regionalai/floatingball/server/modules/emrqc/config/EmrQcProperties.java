package com.regionalai.floatingball.server.modules.emrqc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "floating-ball.emr-qc")
public class EmrQcProperties {

    private boolean enabled = true;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 10000;
    private int analysisTimeoutSeconds = 90;
    private int maxTaskRecords = 50;
    private int workerConcurrency = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getAnalysisTimeoutSeconds() {
        return analysisTimeoutSeconds;
    }

    public void setAnalysisTimeoutSeconds(int analysisTimeoutSeconds) {
        this.analysisTimeoutSeconds = analysisTimeoutSeconds;
    }

    public int getMaxTaskRecords() {
        return maxTaskRecords;
    }

    public void setMaxTaskRecords(int maxTaskRecords) {
        this.maxTaskRecords = maxTaskRecords;
    }

    public int getWorkerConcurrency() {
        return workerConcurrency;
    }

    public void setWorkerConcurrency(int workerConcurrency) {
        this.workerConcurrency = Math.max(1, workerConcurrency);
    }
}
