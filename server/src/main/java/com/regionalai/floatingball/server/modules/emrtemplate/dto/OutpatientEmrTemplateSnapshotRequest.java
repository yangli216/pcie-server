package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"templateHtml", "templateDefinition", "parseResult"})
public class OutpatientEmrTemplateSnapshotRequest extends StrictOutpatientEmrSnapshotDto {

    private String schemaVersion;

    private Boolean supportsFieldVisibility;

    private String templateId;

    private String templateName;

    private String templateHash;

    private String templateHtml;

    private String templateDefinition;

    private OutpatientEmrTemplateParseSnapshot parseResult;
}
