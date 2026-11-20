package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import lombok.Getter;

@Getter
public class OutpatientPolicyReceipt extends OutpatientEmrTemplateSnapshotReceipt {
    private final String schemaVersion = "outpatient-emr-template-pair-receipt.v2";
    private final String templateId;
    private final OutpatientEmrTemplateParseSnapshot parseResult;
    private final OutpatientFieldPolicy fieldPolicy;

    public OutpatientPolicyReceipt(OutpatientEmrTemplateSnapshotReceipt receipt, String templateId,
                                    OutpatientEmrTemplateParseSnapshot parseResult, OutpatientFieldPolicy policy) {
        super(receipt.getId(), receipt.getTemplateHash(), receipt.getDeduplicated(), receipt.getReceivedAt());
        this.templateId = templateId;
        this.parseResult = parseResult;
        this.fieldPolicy = policy;
    }
}
