package com.regionalai.floatingball.server.modules.publichealth.service;

import java.util.Collections;
import java.util.List;

public class PublicHealthException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<String> missingFields;
    public PublicHealthException(int status, String code, String message) {
        this(status, code, message, Collections.<String>emptyList());
    }

    public PublicHealthException(int status, String code, String message, List<String> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.missingFields = fields;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<String> getMissingFields() {
        return missingFields;
    }
}
