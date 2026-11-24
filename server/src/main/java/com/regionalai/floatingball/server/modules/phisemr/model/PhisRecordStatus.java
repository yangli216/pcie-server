package com.regionalai.floatingball.server.modules.phisemr.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import org.springframework.util.StringUtils;

import java.util.Locale;

/** Only the two approved outpatient states; never coerce signing/unknown states to draft. */
public enum PhisRecordStatus {
    DRAFT("draft", "0"), COMPLETED("completed", "2");

    private final String value;
    private final String upstream;

    PhisRecordStatus(String value, String upstream) {
        this.value = value;
        this.upstream = upstream;
    }

    public String getValue() { return value; }
    public String getUpstream() { return upstream; }

    /** Null denotes the vendor's unified all-state query. */
    public static PhisRecordStatus filter(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "all";
        if ("all".equals(normalized)) return null;
        for (PhisRecordStatus status : values()) if (status.value.equals(normalized)) return status;
        throw new BusinessException("EMR-QC-400", "病历状态仅支持全部、草稿或已完成");
    }

    public static PhisRecordStatus fromUpstream(JsonNode node) {
        if (node != null && (node.isTextual() || node.isIntegralNumber())) {
            for (PhisRecordStatus status : values()) {
                if (status.upstream.equals(node.asText())) return status;
            }
        }
        throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 返回缺失或不支持的病历状态，请联系管理员核对");
    }
}
