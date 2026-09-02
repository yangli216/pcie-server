package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class OutpatientEmrTemplateMappingRequest extends StrictOutpatientEmrSnapshotDto {

    private String fieldId;

    private String mappingStatus;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String recordField;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String projectionMode;
}
