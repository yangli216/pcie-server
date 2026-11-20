package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.ALWAYS)
public class OutpatientPolicyResolution extends OutpatientEmrTemplateSnapshotResolution {
    private final OutpatientFieldPolicy fieldPolicy;

    public OutpatientPolicyResolution(OutpatientEmrTemplateSnapshotResolution snapshot, OutpatientFieldPolicy policy) {
        super("outpatient-emr-template-pair-resolution.v2", snapshot.getCacheHit(), snapshot.getId(),
            snapshot.getTemplateId(), snapshot.getTemplateHash(), snapshot.getParseResult(), snapshot.getReceivedAt());
        this.fieldPolicy = policy;
    }
}
