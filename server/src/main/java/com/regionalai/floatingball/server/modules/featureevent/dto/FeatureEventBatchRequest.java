package com.regionalai.floatingball.server.modules.featureevent.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FeatureEventBatchRequest {

    private List<FeatureEventRequest> events;

    @Data
    public static class FeatureEventRequest {
        private String eventId;
        private String featureCode;
        private String eventAction;
        private String idempotencyKey;
        private String traceId;
        private String consultationId;
        private String sessionId;
        private String sourceModule;
        private String scene;
        private String status;
        private String doctorId;
        private String doctorWorkNo;
        private String doctorName;
        private String deptId;
        private String deptName;
        private String hisOrgId;
        private String hisOrgName;
        private String clientVersion;
        private Map<String, Object> payload;
        private Long timestamp;
    }
}
