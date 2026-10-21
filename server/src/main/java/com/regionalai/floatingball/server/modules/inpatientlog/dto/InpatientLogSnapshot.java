package com.regionalai.floatingball.server.modules.inpatientlog.dto;

import lombok.Data;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.ArrayList;
import java.util.List;

@Data
public class InpatientLogSnapshot extends StrictLogInput {
    @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private String generationId;
    @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private String eventId;
    @NotNull @Min(1) @Max(100000) private Integer sequence;
    @NotBlank @Size(max = 32) private String eventType;
    @NotBlank @Size(max = 32) private String status;
    @NotNull @Min(1) @Max(4102444800000L) private Long generationStartedAt;
    @NotNull @Min(1) @Max(4102444800000L) private Long occurredAt;
    @NotBlank @Size(max = 128) private String admissionId;
    @NotBlank @Size(max = 128) private String templateId;
    @NotBlank @Size(max = 256) private String templateName;
    @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String templateHash;
    @Size(max = 128) private String requestId;
    @Size(max = 64) private String recordTime;
    @Size(max = 128) private String doctorId;
    @Size(max = 128) private String doctorName;
    @Size(max = 128) private String hisOrgId;
    @Size(max = 256) private String orgName;
    @Size(max = 128) private String patientId;
    @Size(max = 128) private String patientName;
    @Size(max = 32) private String patientGender;
    @Size(max = 32) private String patientAge;
    @Size(max = 512) private String message;
    @NotNull @Valid @Size(max = 8) private List<Source> sources = new ArrayList<>();
    @NotNull @Valid @Size(max = 250) private List<Field> fields = new ArrayList<>();

    @Data
    public static class Source extends StrictLogInput {
        @NotBlank @Pattern(regexp = "doctor_supplement|his_summary|outpatient_record") private String code;
        @NotBlank @Pattern(regexp = "used|available|missing|waiting|failed|skipped") private String status;
        @NotNull @Size(max = 100000) private String text;
    }
    @Data
    public static class Field extends StrictLogInput {
        @NotBlank @Size(max = 128) private String id;
        @NotBlank @Size(max = 256) private String name;
        @Size(max = 256) private String section;
        @NotBlank @Pattern(regexp = "ai|his_or_system|fixed|doctor_signature|manual_or_his") private String source;
        @NotNull @Size(max = 20000) private String generatedValue;
        @Size(max = 20000) private String finalValue;
    }
}
