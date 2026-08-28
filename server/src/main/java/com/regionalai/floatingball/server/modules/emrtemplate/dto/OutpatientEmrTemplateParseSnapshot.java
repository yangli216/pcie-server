package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class OutpatientEmrTemplateParseSnapshot extends StrictOutpatientEmrSnapshotDto {

    private String schemaVersion;

    private List<OutpatientEmrTemplateFieldSnapshot> fields;
}
