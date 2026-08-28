package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class OutpatientEmrTemplateSnapshotResolution {

    private String schemaVersion;

    private Boolean cacheHit;

    private String id;

    private String templateId;

    private String templateHash;

    private OutpatientEmrTemplateParseSnapshot parseResult;

    private Long receivedAt;
}
