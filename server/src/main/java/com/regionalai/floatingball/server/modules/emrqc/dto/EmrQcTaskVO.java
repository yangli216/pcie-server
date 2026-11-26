package com.regionalai.floatingball.server.modules.emrqc.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class EmrQcTaskVO {

    private String taskId;
    private String status;
    private Integer total;
    private Integer processed;
    private Integer succeeded;
    private Integer failed;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private List<Item> items = new ArrayList<Item>();

    @Data
    public static class Item {
        private String idMedrecdoc;
        private String patientName;
        private String status;
        private Integer score;
        private Integer findingCount;
        private Integer highSeverityCount;
        private String analysisId;
        private LocalDateTime analyzedAt;
        private String resultSource;

        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public boolean isProxyRefill() {
            return "proxy_refill".equals(resultSource);
        }
        private String errorCode;
        private String errorMessage;
    }
}
