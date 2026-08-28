package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class OutpatientEmrTemplateSnapshotResolveRequest extends StrictOutpatientEmrSnapshotDto {

    private String schemaVersion;

    private String templateId;

    private String templateHash;
}
