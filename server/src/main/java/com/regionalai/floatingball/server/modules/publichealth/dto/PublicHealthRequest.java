package com.regionalai.floatingball.server.modules.publichealth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data @JsonInclude(JsonInclude.Include.NON_NULL)
public class PublicHealthRequest {
    private String schemaVersion, originRequestId, requestId, capability, scene;
    private String patientId, businessRecordId, recordVersion, snapshotAt;
    private Patient patient;
    private Facts facts;
    private List<WritebackTarget> writebackTargets;
    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Patient {
        private String name, sex;
        private Integer ageYears;
        private FactSource sexSource;
    }
    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Facts {
        private List<Observation> observations;
        private List<Condition> conditions;
        private List<MedicationClass> medicationClasses;
        private Lifestyle lifestyle;
        private String historySummary, nextFollowUpDate;
        private WeightProfile weightProfile;
    }
    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Observation {
        private String id, code, unit, observedAt;
        private JsonNode value;
        private FactSource source;
    }
    @Data
    public static class FactSource {
        private String system, recordId;
    }
    @Data
    public static class Condition {
        private String code, name, status;
    }
    @Data
    public static class MedicationClass {
        private String code, name;
    }
    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Lifestyle {
        private String smoking, alcohol, exercise, diet, exerciseAbility, occupation;
    }
    @Data @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WeightProfile {
        private BigDecimal heightCm, weightKg;
        private String region, season, activityDescription, dietPreferences, limitations;
        private String heightObservationId, weightObservationId;
        private FactSource contextSource;
    }
    @Data
    public static class WritebackTarget {
        private String targetFieldId, label, valueType, mode;
        private List<String> sourceSections;
    }
}
