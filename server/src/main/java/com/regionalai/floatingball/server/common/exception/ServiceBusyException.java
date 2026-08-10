package com.regionalai.floatingball.server.common.exception;

public class ServiceBusyException extends RuntimeException {

    private final String code;
    private final int retryAfterSeconds;

    public ServiceBusyException(String message, int retryAfterSeconds) {
        this("AI-BUSY", message, retryAfterSeconds);
    }

    public ServiceBusyException(String code, String message, int retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public String getCode() {
        return code;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
