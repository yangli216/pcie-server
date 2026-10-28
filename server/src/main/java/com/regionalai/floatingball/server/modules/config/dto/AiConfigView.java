package com.regionalai.floatingball.server.modules.config.dto;

import lombok.Data;

@Data
public class AiConfigView {

    private String idConfig;
    private String cdConfig;
    private String naConfig;
    private String provider;
    private String apiBaseUrl;
    private String apiKeyMasked;
    private String modelName;
    private String fastModelName;
    private Boolean enableThinking;
    private String audioApiKeyMasked;
    private String audioBaseUrl;
    private String audioModel;
    private String speechProvider;
    private String speechRealtimeUrl;
    private String speechModel;
    private String speechTranscriptionMode;
    private Integer speechSegmentDurationMs;
    private String knowledgeBaseEnabled;
    private String knowledgeBaseBaseUrl;
    private String pmphaiEnabled;
    private String pmphaiBaseUrl;
    private String pmphaiAppKeyMasked;
    private String pmphaiAppSecretMasked;
    private String reviewerEnabled;
    private String reviewerBaseUrl;
    private String reviewerApiKeyMasked;
    private String reviewerModel;
    private Boolean reviewerCheckExaminationEnabled;
    private String featuresJson;
    private String idOrg;
    private String idRegion;
    private String sdStatus;
}
