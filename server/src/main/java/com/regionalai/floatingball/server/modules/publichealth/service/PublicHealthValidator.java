package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.*;

@Component
public class PublicHealthValidator {
    public static final int MAX_BODY_BYTES = 524288;
    public static final List<String> HEALTH = Collections.unmodifiableList(Arrays.asList("check_plan","medication_guidance","lifestyle_guidance"));
    public static final List<String> WEIGHT = Collections.unmodifiableList(Arrays.asList("exercise_plan","meal_plan"));
    private final ObjectMapper mapper;
    public PublicHealthValidator(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalid("请求不能为空");
        }
        if (bytes.length>MAX_BODY_BYTES) {
            throw new PublicHealthException(413,"INVALID_REQUEST","请求超过512KiB");
        }
        try {
            JsonNode tree = mapper.readTree(bytes);
            rejectNull(tree);
            JsonNode canonical = PublicHealthJson.sorted(tree, mapper);
            PublicHealthRequest request = mapper.treeToValue(canonical, PublicHealthRequest.class);
            validate(request);
            return new Parsed(request, PublicHealthJson.sha256(mapper.writeValueAsBytes(canonical)));
        } catch (PublicHealthException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("请求JSON或字段类型无效");
        }
    }

    private void rejectNull(JsonNode node) {
        if (node == null || node.isNull()) {
            throw invalid("字段不能为null，请省略未提供的可选字段");
        }
        if (node.isContainerNode()) {
            for (JsonNode child:node) {
                rejectNull(child);
            }
        }
    }

    public void validate(PublicHealthRequest r) {
        if (r == null) {
            throw invalid("请求不能为空");
        }
        equal(r.getSchemaVersion(),"public-health.v1","schemaVersion");
        uuid(r.getOriginRequestId(),"originRequestId");
        uuid(r.getRequestId(),"requestId");
        one(r.getCapability(), Arrays.asList("health_prescription","weight_management"),"capability");
        one(r.getScene(), Arrays.asList("hypertension_followup","diabetes_followup","elderly_exam"),"scene");
        text(r.getPatientId(), 128,"patientId");
        text(r.getBusinessRecordId(), 128,"businessRecordId");
        text(r.getRecordVersion(), 128,"recordVersion");
        dateTime(r.getSnapshotAt(),"snapshotAt");
        Patient p = r.getPatient();
        if (p == null) {
            throw missing("patient");
        }
        text(p.getName(), 100,"patient.name");
        one(p.getSex(), Arrays.asList("male","female","unknown"),"patient.sex");
        if (p.getSexSource() != null) {
            source(p.getSexSource());
        }
        if (p.getAgeYears() == null) {
            throw missing("patient.ageYears");
        }
        if (p.getAgeYears()<0 || p.getAgeYears()>150) {
            throw invalid("年龄超过工程输入范围");
        }
        Facts f = r.getFacts();
        if (f == null) {
            throw missing("facts");
        }
        size(f.getObservations(), 0, 256,"observations");
        size(f.getConditions(), 0, 64,"conditions");
        size(f.getMedicationClasses(), 0, 64,"medicationClasses");
        if (f.getLifestyle() == null) {
            throw missing("facts.lifestyle");
        }
        Set<String> obsIds = new HashSet<String>();
        for (Observation o:f.getObservations()) {
            if (o == null) {
                throw invalid("observation不能为空");
            }
            text(o.getId(), 128,"observation.id");
            if (!obsIds.add(o.getId())) {
                throw invalid("observation.id重复");
            }
            text(o.getCode(), 128,"observation.code");
            optional(o.getUnit(), 64,"unit");
            dateTime(o.getObservedAt(),"observedAt");
            source(o.getSource());
            if (o.getValue() == null || !(o.getValue().isNumber() || o.getValue().isTextual())) {
                throw invalid("observation.value须为数值或文本");
            }
            if (o.getValue().isTextual()) {
                text(o.getValue().asText(), 2000,"value");
            }
        }
        for (Condition c:f.getConditions()) {
            if (c == null) {
                throw invalid("condition不能为空");
            }
            text(c.getCode(), 128,"condition.code");
            text(c.getName(), 200,"condition.name");
            text(c.getStatus(), 64,"condition.status");
        }
        for (MedicationClass c:f.getMedicationClasses()) {
            if (c == null) {
                throw invalid("medicationClass不能为空");
            }
            text(c.getCode(), 128,"medicationClass.code");
            text(c.getName(), 200,"medicationClass.name");
        }
        Lifestyle l = f.getLifestyle();
        for (String v:Arrays.asList(l.getSmoking(), l.getAlcohol(), l.getExercise(), l.getDiet(), l.getExerciseAbility(), l.getOccupation())) {
            optional(v, 2000,"lifestyle");
        }
        optional(f.getHistorySummary(), 12000,"historySummary");
        if (f.getNextFollowUpDate() != null) {
            try {
                LocalDate.parse(f.getNextFollowUpDate());
            } catch (Exception ex) {
                throw invalid("nextFollowUpDate无效");
            }
        }
        List<String> expected = sections(r.getCapability());
        size(r.getWritebackTargets(), 1, expected.size(),"writebackTargets");
        Set<String> targetIds = new HashSet<String>();
        Set<String> mapped = new HashSet<String>();
        for (WritebackTarget t:r.getWritebackTargets()) {
            if (t == null) {
                throw invalid("target不能为空");
            }
            text(t.getTargetFieldId(), 128,"targetFieldId");
            text(t.getLabel(), 200,"label");
            if (!targetIds.add(t.getTargetFieldId())) {
                throw invalid("targetFieldId重复");
            }
            equal(t.getValueType(),"string","valueType");
            equal(t.getMode(),"replace","mode");
            size(t.getSourceSections(), 1, expected.size(),"sourceSections");
            int last = -1;
            for (String id:t.getSourceSections()) {
                int index = expected.indexOf(id);
                if (index<0 || index <= last || !mapped.add(id)) {
                    throw invalid("章节映射重复、越界或顺序错误");
                }
                last = index;
            }
        }
        if (!mapped.equals(new HashSet<String>(expected))) {
            throw invalid("章节映射必须覆盖全部章节");
        }
        if ("weight_management".equals(r.getCapability())) {
            validateWeight(r);
        } else if (f.getWeightProfile() != null) {
            validateProfile(f.getWeightProfile());
        }
    }

    private void validateWeight(PublicHealthRequest r) {
        if ("unknown".equals(r.getPatient().getSex())) {
            throw missing("patient.sex");
        }
        WeightProfile p = r.getFacts().getWeightProfile();
        if (p == null) {
            throw missing("facts.weightProfile");
        }
        validateProfile(p);
        measured(r, p.getHeightObservationId(),"height","cm", p.getHeightCm(),"heightObservationId");
        measured(r, p.getWeightObservationId(),"weight","kg", p.getWeightKg(),"weightObservationId");
    }

    private void validateProfile(WeightProfile p) {
        positive(p.getHeightCm(), 300,"heightCm");
        positive(p.getWeightKg(), 1000,"weightKg");
        one(p.getRegion(), Arrays.asList("东北","华北","华东","华中","华南","西南","西北"),"region");
        one(p.getSeason(), Arrays.asList("spring","summer","autumn","winter"),"season");
        optional(p.getActivityDescription(), 4000,"activityDescription");
        optional(p.getDietPreferences(), 4000,"dietPreferences");
        optional(p.getLimitations(), 4000,"limitations");
        optional(p.getHeightObservationId(), 128,"heightObservationId");
        optional(p.getWeightObservationId(), 128,"weightObservationId");
        if (p.getContextSource() != null) {
            source(p.getContextSource());
        }
    }

    private void measured(PublicHealthRequest r, String id, String code, String unit, BigDecimal value, String field) {
        if (id == null || id.trim().isEmpty()) {
            throw missing("facts.weightProfile."+field);
        }
        Observation found = null;
        for (Observation o:r.getFacts().getObservations()) {
            if (id.equals(o.getId())) {
                found = o;
            }
        }
        if (found == null) {
            throw missing("facts.observations["+id+"]");
        }
        if (!code.equals(found.getCode()) || !unit.equals(found.getUnit()) || !found.getValue().isNumber() || value.compareTo(found.getValue().decimalValue()) != 0) {
            throw invalid("身高/体重与引用测量事实不一致");
        }
    }

    private void source(FactSource s) {
        if (s == null) {
            throw missing("source");
        }
        text(s.getSystem(), 64,"source.system");
        text(s.getRecordId(), 128,"source.recordId");
        if ("PCIE_MANUAL".equals(s.getSystem())) {
            uuid(s.getRecordId(),"source.recordId");
        }
    }

    public static List<String> sections(String capability) {
        return "health_prescription".equals(capability)?HEALTH:WEIGHT;
    }

    static void text(String v, int max, String field) {
        if (v == null || v.trim().isEmpty() || v.codePointCount(0, v.length())>max) {
            throw invalid(field+"不能为空或超过长度限制");
        }
    }

    static void optional(String v, int max, String field) {
        if (v != null) {
            text(v, max, field);
        }
    }

    static void equal(String v, String expected, String field) {
        if (!expected.equals(v)) {
            throw invalid(field+"无效");
        }
    }

    static void one(String v, List<String> allowed, String field) {
        if (v == null || !allowed.contains(v)) {
            throw invalid(field+"无效");
        }
    }

    static void uuid(String v, String field) {
        if (v == null || !v.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid(field+"须为UUID");
        }
    }

    static void dateTime(String v, String field) {
        try {
            OffsetDateTime.parse(v);
        } catch (Exception e) {
            throw invalid(field+"须为带时区时间");
        }
    }

    static void size(List<?> list, int min, int max, String field) {
        if (list == null || list.size()<min || list.size()>max) {
            throw invalid(field+"数量无效");
        }
    }

    static void positive(BigDecimal value, int max, String field) {
        if (value == null) {
            throw missing(field);
        }
        if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(max))>0) {
            throw invalid(field+"超过工程范围");
        }
    }

    public static PublicHealthException invalid(String message) {
        return new PublicHealthException(400,"INVALID_REQUEST", message);
    }

    public static PublicHealthException missing(String field) {
        return new PublicHealthException(400,"MISSING_FACTS","请补充生成所需资料", Collections.singletonList(field));
    }

    public static final class Parsed {
        public final PublicHealthRequest request;
        public final String inputHash;
        public Parsed(PublicHealthRequest request, String inputHash) {
            this.request = request;
            this.inputHash = inputHash;
        }
    }
}
