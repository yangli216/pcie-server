package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class OutpatientEmrTemplateSnapshotRequest extends StrictOutpatientEmrSnapshotDto {

    private String schemaVersion;

    private String templateId;

    private String templateName;

    private String templateHash;

    private String templateHtml;

    private String templateDefinition;

    private OutpatientEmrTemplateParseSnapshot parseResult;
}
