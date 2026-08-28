package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class OutpatientEmrTemplateDictionaryItemSnapshot extends StrictOutpatientEmrSnapshotDto {

    private String value;

    private String text;
}
