package com.regionalai.floatingball.server.modules.emrqc.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class EmrQcAnalysisVO {

    private String analysisId;
    private LocalDateTime analyzedAt;
    private Integer score;
    private List<Dimension> dimensions = new ArrayList<Dimension>();
    private List<Finding> findings = new ArrayList<Finding>();
    private Integer findingCount;
    private Integer highSeverityCount;
    private String model;
    private String ruleVersion;
    private String resultSource;
    private Integer aiScore;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String reviewReason;
    private boolean stale;
    private String staleReason;
    private Boolean reused;

    @Data
    public static class Dimension {
        private String code;
        private String name;
        private Integer score;
        private Integer weight;
        private String summary;
    }

    @Data
    public static class Finding {
        private String severity;
        private String category;
        private String ruleCode;
        private String sectionName;
        private String excerpt;
        private String issue;
        private String suggestion;
        private Integer deduction;
        private Boolean ignored;
    }
}
