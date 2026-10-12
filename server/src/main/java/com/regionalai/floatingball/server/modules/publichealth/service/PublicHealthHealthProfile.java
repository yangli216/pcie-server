package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest.Observation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/** Projects only supplied facts. No external lookup, diagnosis or inferred risk grade. */
public final class PublicHealthHealthProfile {
    private static final Set<String> RISK_CODES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "risk_level", "hypertension_risk_level", "diabetes_risk_level", "cardiovascular_risk_level")));

    private PublicHealthHealthProfile() { }

    public static ObjectNode build(PublicHealthRequest request, ObjectMapper mapper) {
        ObjectNode profile = mapper.createObjectNode();
        profile.set("patient", mapper.valueToTree(request.getPatient()));
        profile.put("snapshotAt", request.getSnapshotAt());
        profile.set("conditions", mapper.valueToTree(request.getFacts().getConditions()));
        profile.set("medicationClasses", mapper.valueToTree(request.getFacts().getMedicationClasses()));
        ObjectNode lifestyle = mapper.valueToTree(request.getFacts().getLifestyle());
        profile.set("lifestyle", lifestyle);
        if (request.getFacts().getHistorySummary() != null) profile.put("historySummary", request.getFacts().getHistorySummary());
        if (request.getFacts().getNextFollowUpDate() != null) profile.put("nextFollowUpDate", request.getFacts().getNextFollowUpDate());
        ArrayNode latest = profile.putArray("latestObservations");
        ArrayNode risks = profile.putArray("knownRiskAssessments");
        ArrayNode sources = profile.putArray("sources");
        ArrayNode evidence = profile.putArray("evidence");
        ArrayNode excluded = profile.putArray("excludedObservationIds");
        ArrayNode conflicts = profile.putArray("conflicts");

        addEvidence(evidence, "patient:ageYears", "年龄", mapper.valueToTree(request.getPatient().getAgeYears()));
        if (!"unknown".equals(request.getPatient().getSex())) {
            ObjectNode item = addEvidence(evidence, "patient:sex", "性别", mapper.valueToTree(request.getPatient().getSex()));
            if (request.getPatient().getSexSource() != null) item.set("source", mapper.valueToTree(request.getPatient().getSexSource()));
        }

        Instant snapshot = OffsetDateTime.parse(request.getSnapshotAt()).toInstant();
        Map<String, List<Observation>> byCode = new LinkedHashMap<String, List<Observation>>();
        Map<String, ObjectNode> sourceRecords = new LinkedHashMap<String, ObjectNode>();
        List<Observation> observations = new ArrayList<Observation>();
        for (Observation observation : request.getFacts().getObservations()) {
            if (OffsetDateTime.parse(observation.getObservedAt()).toInstant().isAfter(snapshot)) {
                excluded.add(observation.getId());
                continue;
            }
            if (!byCode.containsKey(observation.getCode())) byCode.put(observation.getCode(), new ArrayList<Observation>());
            byCode.get(observation.getCode()).add(observation);
            observations.add(observation);
            // Delimit with an unambiguous encoding, not a source label or guessed record association.
            JsonNode source = mapper.valueToTree(observation.getSource());
            String key = source.toString();
            if (!sourceRecords.containsKey(key)) {
                ObjectNode record = source.deepCopy();
                record.putArray("observationIds");
                sourceRecords.put(key, record);
                sources.add(record);
            }
            sourceRecords.get(key).withArray("observationIds").add(observation.getId());
        }
        Set<String> conflictedIds = new HashSet<String>();
        for (Map.Entry<String, List<Observation>> group : byCode.entrySet()) {
            Instant newest = null;
            List<Observation> newestObservations = new ArrayList<Observation>();
            for (Observation observation : group.getValue()) {
                Instant time = OffsetDateTime.parse(observation.getObservedAt()).toInstant();
                if (newest == null || time.isAfter(newest)) {
                    newest = time;
                    newestObservations.clear();
                }
                if (time.equals(newest)) newestObservations.add(observation);
            }
            Observation first = newestObservations.get(0);
            boolean conflict = false;
            for (Observation observation : newestObservations) {
                if (!sameResult(first, observation)) conflict = true;
            }
            if (conflict) {
                ObjectNode item = conflicts.addObject().put("code", group.getKey()).put("observedAt", first.getObservedAt());
                ArrayNode ids = item.putArray("observationIds");
                for (Observation observation : newestObservations) {
                    ids.add(observation.getId());
                    conflictedIds.add(observation.getId());
                }
            } else {
                JsonNode projected = mapper.valueToTree(first);
                latest.add(projected);
                if (RISK_CODES.contains(first.getCode())) risks.add(projected.deepCopy());
            }
        }
        for (Observation observation : observations) {
            if (conflictedIds.contains(observation.getId())) continue;
            ObjectNode item = addEvidence(evidence, "observation:" + observation.getId(), observationLabel(observation.getCode()), observation.getValue());
            item.put("observedAt", observation.getObservedAt());
            if (observation.getUnit() != null) item.put("unit", observation.getUnit());
            item.set("source", mapper.valueToTree(observation.getSource()));
        }
        for (int i = 0; i < request.getFacts().getConditions().size(); i++) {
            PublicHealthRequest.Condition condition = request.getFacts().getConditions().get(i);
            addEvidence(evidence, "condition:" + i, "健康状况", mapper.valueToTree(condition.getName() + "（" + condition.getStatus() + "）"));
        }
        for (int i = 0; i < request.getFacts().getMedicationClasses().size(); i++) {
            addEvidence(evidence, "medication:" + i, "已提供用药类别", mapper.valueToTree(request.getFacts().getMedicationClasses().get(i).getName()));
        }
        String[] lifestyleKeys = {"smoking", "alcohol", "exercise", "diet", "exerciseAbility", "occupation"};
        String[] lifestyleLabels = {"吸烟", "饮酒", "运动", "饮食", "运动能力", "职业"};
        for (int i = 0; i < lifestyleKeys.length; i++) {
            if (lifestyle.has(lifestyleKeys[i])) addEvidence(evidence, "lifestyle:" + lifestyleKeys[i], lifestyleLabels[i], lifestyle.path(lifestyleKeys[i]));
        }
        if (request.getFacts().getHistorySummary() != null) {
            addEvidence(evidence, "historySummary", "病史与补充摘要", mapper.valueToTree(request.getFacts().getHistorySummary()));
        }
        return profile;
    }

    private static String observationLabel(String code) {
        String[] codes = {"systolic_bp", "diastolic_bp", "fasting_glucose", "height", "weight", "waist", "bmi",
                "risk_level", "hypertension_risk_level", "diabetes_risk_level", "cardiovascular_risk_level"};
        String[] labels = {"收缩压", "舒张压", "空腹血糖", "身高", "体重", "腰围", "BMI",
                "已有风险等级", "高血压风险等级", "糖尿病风险等级", "心血管风险等级"};
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(code)) return labels[i];
        return code;
    }

    private static boolean sameResult(Observation left, Observation right) {
        boolean sameValue = left.getValue().isNumber() && right.getValue().isNumber()
                ? left.getValue().decimalValue().compareTo(right.getValue().decimalValue()) == 0
                : left.getValue().equals(right.getValue());
        return sameValue && Objects.equals(left.getUnit(), right.getUnit());
    }

    private static ObjectNode addEvidence(ArrayNode evidence, String ref, String label, JsonNode value) {
        ObjectNode item = evidence.addObject().put("ref", ref).put("label", label);
        item.set("value", value.deepCopy());
        return item;
    }

    static Set<String> observationEvidenceIds(JsonNode profile) {
        Set<String> ids = new HashSet<String>();
        for (JsonNode item : profile.path("evidence")) {
            String ref = item.path("ref").asText();
            if (ref.startsWith("observation:")) ids.add(ref.substring("observation:".length()));
        }
        return ids;
    }
}
