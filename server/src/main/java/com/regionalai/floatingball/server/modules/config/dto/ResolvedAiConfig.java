package com.regionalai.floatingball.server.modules.config.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ResolvedAiConfig {

    private String provider;
    private String baseUrl;
    private String apiKey;
    private String model;
    private String fastModel;
    private Boolean enableThinking;
    private String audioApiKey;
    private String audioBaseUrl;
    private String audioModel;
    private String speechProvider;
    private String speechRealtimeUrl;
    private String speechModel;
    private Boolean knowledgeBaseEnabled;
    private String knowledgeBaseBaseUrl;
    private Boolean pmphaiEnabled;
    private String pmphaiBaseUrl;
    private String pmphaiAppKey;
    private String pmphaiAppSecret;
    private Boolean reviewerEnabled;
    private String reviewerBaseUrl;
    private String reviewerApiKey;
    private String reviewerModel;
    private Boolean reviewerCheckExaminationEnabled;
    private Map<String, Boolean> features;
}
