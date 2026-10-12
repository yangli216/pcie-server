package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest.Observation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Optional approved check restrictions. AND between dimensions, OR inside a list. */
final class PublicHealthCheckEligibility {
    private static final Set<String> FIELDS = new HashSet<String>(Arrays.asList(
            "minAgeYears", "maxAgeYears", "sexes", "conditionCodes", "riskLevels"));

    private PublicHealthCheckEligibility() { }

    static void validate(JsonNode eligibility) {
        if (eligibility.isMissingNode()) return;
        if (!eligibility.isObject()) fail();
        Iterator<String> fields = eligibility.fieldNames();
        while (fields.hasNext()) if (!FIELDS.contains(fields.next())) fail();
        for (String field : Arrays.asList("minAgeYears", "maxAgeYears")) {
            if (!eligibility.has(field)) continue;
            JsonNode age = eligibility.path(field);
            if (!age.isIntegralNumber() || !age.canConvertToInt() || age.asInt() < 0 || age.asInt() > 130) fail();
        }
        if (eligibility.has("minAgeYears") && eligibility.has("maxAgeYears")
                && eligibility.path("minAgeYears").asInt() > eligibility.path("maxAgeYears").asInt()) fail();
        for (String field : Arrays.asList("sexes", "conditionCodes", "riskLevels")) {
            if (!eligibility.has(field)) continue;
            JsonNode list = eligibility.path(field);
            if (!list.isArray() || list.size() == 0 || list.size() > ("sexes".equals(field) ? 2 : 64)) fail();
            Set<String> seen = new HashSet<String>();
            for (JsonNode value : list) {
                if (!value.isTextual() || value.asText().trim().isEmpty()
                        || value.asText().codePointCount(0, value.asText().length()) > 128 || !seen.add(value.asText())) fail();
                if ("sexes".equals(field) && !Arrays.asList("male", "female").contains(value.asText())) fail();
            }
        }
    }

    static ObjectNode filter(JsonNode policy, PublicHealthRequest request, JsonNode profile) {
        ObjectNode filtered = policy.deepCopy();
        ArrayNode checks = filtered.putArray("checkWhitelist");
        for (JsonNode check : policy.path("checkWhitelist")) {
            if (matches(check.path("eligibility"), request, profile)) checks.add(check.deepCopy());
        }
        return filtered;
    }

    /** Model hints only: the output validator independently checks the cited observations and dates. */
    static ArrayNode schedule(PublicHealthRequest request, JsonNode policy, JsonNode profile, ObjectMapper mapper) {
        OffsetDateTime snapshot = OffsetDateTime.parse(request.getSnapshotAt());
        LocalDate base = snapshot.toLocalDate(), windowEnd = base.plusDays(90);
        Set<String> usableIds = PublicHealthHealthProfile.observationEvidenceIds(profile);
        Map<String, LocalDate> latestDates = new HashMap<String, LocalDate>();
        List<Observation> evidence = new ArrayList<Observation>();
        for (Observation observation : request.getFacts().getObservations()) {
            OffsetDateTime observedAt = OffsetDateTime.parse(observation.getObservedAt());
            if (observedAt.toInstant().isAfter(snapshot.toInstant())) continue;
            LocalDate date = observedAt.toLocalDate(), latest = latestDates.get(observation.getCode());
            if (latest == null || date.isAfter(latest)) latestDates.put(observation.getCode(), date);
            if (usableIds.contains(observation.getId())) evidence.add(observation);
        }
        ArrayNode constraints = mapper.createArrayNode();
        for (JsonNode rule : policy.path("checkWhitelist")) {
            if (!matches(rule.path("eligibility"), request, profile)) continue;
            String code = rule.path("code").asText();
            LocalDate latest = latestDates.get(code);
            LocalDate routine = earliest(base, latest, rule.path("minIntervalDays").asInt());
            ObjectNode item = constraints.addObject().put("code", code)
                    .put("routineEarliestDate", routine.toString())
                    .put("routineAvailable", !routine.isAfter(windowEnd))
                    .put("abnormalRetestAvailable", false);
            if (abnormalSupported(rule.path("abnormalCriteria"), evidence, base)) {
                LocalDate abnormal = earliest(base, latest, rule.path("abnormalRetestIntervalDays").asInt());
                item.put("abnormalRetestEarliestDate", abnormal.toString())
                        .put("abnormalRetestAvailable", !abnormal.isAfter(windowEnd));
            }
        }
        return constraints;
    }

    private static LocalDate earliest(LocalDate base, LocalDate latest, int intervalDays) {
        if (latest == null) return base;
        LocalDate next = latest.plusDays(intervalDays);
        return next.isBefore(base) ? base : next;
    }

    static boolean abnormalSupported(JsonNode criteria, List<Observation> evidence, LocalDate snapshotDate) {
        if (!criteria.isArray() || criteria.size() == 0) return false;
        for (JsonNode condition : criteria) {
            boolean matched = false;
            for (Observation observation : evidence) {
                long age = ChronoUnit.DAYS.between(OffsetDateTime.parse(observation.getObservedAt()).toLocalDate(), snapshotDate);
                if (!condition.path("observationCode").asText().equals(observation.getCode())
                        || !condition.path("unit").asText().equals(observation.getUnit())
                        || !observation.getValue().isNumber()
                        || age < 0 || age > condition.path("maxEvidenceAgeDays").asInt()) continue;
                int order = observation.getValue().decimalValue().compareTo(condition.path("threshold").decimalValue());
                String operator = condition.path("operator").asText();
                if (("gt".equals(operator) && order > 0) || ("gte".equals(operator) && order >= 0)
                        || ("lt".equals(operator) && order < 0) || ("lte".equals(operator) && order <= 0)
                        || ("eq".equals(operator) && order == 0)) matched = true;
            }
            if (!matched) return false;
        }
        return true;
    }

    static boolean matches(JsonNode eligibility, PublicHealthRequest request, JsonNode profile) {
        validate(eligibility);
        if (eligibility.isMissingNode()) return true;
        int age = request.getPatient().getAgeYears();
        if (eligibility.has("minAgeYears") && age < eligibility.path("minAgeYears").asInt()) return false;
        if (eligibility.has("maxAgeYears") && age > eligibility.path("maxAgeYears").asInt()) return false;
        if (eligibility.has("sexes") && !contains(eligibility.path("sexes"), request.getPatient().getSex())) return false;
        if (eligibility.has("conditionCodes")) {
            boolean matched = false;
            for (PublicHealthRequest.Condition condition : request.getFacts().getConditions()) {
                if (contains(eligibility.path("conditionCodes"), condition.getCode())) matched = true;
            }
            if (!matched) return false;
        }
        if (eligibility.has("riskLevels")) {
            boolean matched = false;
            for (JsonNode risk : profile.path("knownRiskAssessments")) {
                if (contains(eligibility.path("riskLevels"), risk.path("value").asText())) matched = true;
            }
            if (!matched) return false;
        }
        return true;
    }

    private static boolean contains(JsonNode list, String value) {
        for (JsonNode entry : list) if (entry.asText().equals(value)) return true;
        return false;
    }

    private static void fail() { throw PublicHealthAssetService.unavailable("POLICY_NOT_READY"); }
}
