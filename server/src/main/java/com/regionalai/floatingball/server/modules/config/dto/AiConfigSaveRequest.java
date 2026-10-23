package com.regionalai.floatingball.server.modules.config.dto;

import lombok.Data;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

@Data
public class AiConfigSaveRequest {

    private String idConfig;
    private String cdConfig;
    private String naConfig;
    private String provider;
    private String apiBaseUrl;
    private String apiKey;
    private String modelName;
    private String fastModelName;
    private Boolean enableThinking;
    private String audioApiKey;
    private String audioBaseUrl;
    private String audioModel;
    private String speechProvider;
    private String speechRealtimeUrl;
    private String speechModel;
    private String speechTranscriptionMode;
    @JsonDeserialize(using = WholeMillisecondsDeserializer.class)
    private Integer speechSegmentDurationMs;
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
    private String featuresJson;
    private String idOrg;
    private String idRegion;
    private String sdStatus;

    public static class WholeMillisecondsDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }
}
