package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class OutpatientFieldPolicy {
    public static final String RULE_SET = "outpatient-emr-field-prompt.v6";
    private final String schemaVersion = "outpatient-emr-field-policy.v1";
    private final String ruleSetVersion = RULE_SET;
    private String templateState;
    private long revision;
    private List<Field> fields = Collections.emptyList();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Boolean> fieldVisibility = Collections.emptyMap();

    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Field {
        private String fieldId;
        private String meaning;
        private boolean aiSuitable;
        private String generationSource;
        private String promptSource;
        private String resolvedPrompt;
        private String unmentionedDefault;
    }

    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Settings {
        private String generationMode = "inherit";
        private boolean visible = true;
        private String meaningOverride;
        private String additionalConstraint;
        private String customBody;
    }

    @Data
    public static class Detail {
        private OutpatientEmrTemplateFieldSnapshot originalField;
        private Settings settings;
        private Field effective;
        private String defaultMeaning;
        private boolean defaultAiSuitable;
        private String defaultPrompt;
        private String defaultBody;
        private String formatRule;
        private String restriction;
    }
}
