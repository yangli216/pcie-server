package com.regionalai.floatingball.server.modules.emrqc.dto;

import lombok.Data;

import java.util.List;

@Data
public class EmrQcTaskCreateRequest {

    private List<String> recordIds;
    private Boolean force;

    public boolean forceEnabled() {
        return Boolean.TRUE.equals(force);
    }
}
