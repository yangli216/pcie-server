package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class OutpatientEmrTemplateFieldSnapshot extends StrictOutpatientEmrSnapshotDto {

    private String id;

    private String name;

    private String type;

    private String articleTemplateId;

    private String articleId;

    private String articleName;

    private String articleDefinitionName;

    private Boolean readonly;

    private Boolean aiSuitable;

    private String baselineValue;

    private String baselineDictionaryValue;

    private List<OutpatientEmrTemplateDictionaryItemSnapshot> dictionaryItems;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String recordField;

    private String mappingSource;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String projectionMode;
}
