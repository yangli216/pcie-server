package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.Map;

public abstract class StrictOutpatientEmrSnapshotDto {

    private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    @JsonIgnore
    public Map<String, Object> getUnknownFields() {
        return unknownFields;
    }
}
