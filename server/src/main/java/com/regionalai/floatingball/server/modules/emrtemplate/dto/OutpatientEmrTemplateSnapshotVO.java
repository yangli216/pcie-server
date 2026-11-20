package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import lombok.Data;

import java.util.List;

@Data
public class OutpatientEmrTemplateSnapshotVO {

    private String id;

    private String idOrg;

    private String idRegion;

    private String idDevice;

    private String cdDevice;

    private String templateId;

    private String templateName;

    private String templateHash;

    private String templateHtml;

    private String templateDefinition;

    private OutpatientEmrTemplateParseSnapshot parseResult;

    private List<OutpatientEmrTemplateMappingOverrideVO> mappingOverrides;

    private String templateState;
    private Long revision;
    private Integer aiFieldCount;
    private Integer customPromptCount;
    private String ruleSetVersion;
    private List<OutpatientFieldPolicy.Detail> fields;

    private Integer fieldCount;

    private Integer writableFieldCount;

    private Integer dictionaryFieldCount;

    private Integer mappedFieldCount;

    private Long receivedAt;

    private Long createdAt;

    private Long updatedAt;
}
