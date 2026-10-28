package com.regionalai.floatingball.server.modules.emrqc.dto;

import lombok.Data;

@Data
public class EmrQcAnalyzeRequest {

    private Boolean force;

    public boolean forceEnabled() {
        return Boolean.TRUE.equals(force);
    }
}
