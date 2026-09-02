package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
public class OutpatientEmrTemplateMappingOverrideVO {

    private String fieldId;

    private String mappingStatus;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String recordField;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String projectionMode;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String automaticRecordField;

    private String automaticMappingSource;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String automaticProjectionMode;

    private Long updatedAt;
}
