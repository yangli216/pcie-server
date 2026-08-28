package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OutpatientEmrTemplateSnapshotReceipt {

    private String id;

    private String templateHash;

    private Boolean deduplicated;

    private Long receivedAt;
}
