package com.regionalai.floatingball.server.modules.config.dto;

import lombok.Data;

import java.util.Map;

@Data
public class BootstrapVO {

    private LlmConfig llm;
    private SpeechConfig speech;
    private KnowledgeBaseConfig knowledgeBase;
    private PmphaiConfig pmphai;
    private ReviewerConfig reviewer;
    private Map<String, Boolean> features;
    private String templateVersion;
    private String dataPackageVersion;
    private String promptVersion;

    @Data
    public static class LlmConfig {
        private String model;
        private String fastModel;
        private Boolean enableThinking;
        private String audioModel;
    }

    @Data
    public static class SpeechConfig {
        private String provider;
        private String model;
        private String transcriptionMode;
        private Integer segmentDurationMs;
    }

    @Data
    public static class KnowledgeBaseConfig {
        private Boolean enabled;
    }

    @Data
    public static class PmphaiConfig {
        private Boolean enabled;
    }

    @Data
    public static class ReviewerConfig {
        private Boolean enabled;
        private String model;
        private Boolean checkExaminationEnabled;
    }
}
