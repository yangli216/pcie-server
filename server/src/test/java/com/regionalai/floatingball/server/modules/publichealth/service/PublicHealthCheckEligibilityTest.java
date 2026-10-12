package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PublicHealthCheckEligibilityTest {
    private ObjectNode input() {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ((ArrayNode) input.path("facts").path("conditions")).addObject().put("code", "SYNTHETIC-HTN").put("name", "合成病种").put("status", "合成随访");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "risk", "hypertension_risk_level", "synthetic-unit", 1);
        ((ObjectNode) observations.get(0)).put("value", "high");
        return input;
    }

    private PublicHealthRequest parse(ObjectNode input) throws Exception {
        return new PublicHealthValidator(PublicHealthTestFixture.M).parse(PublicHealthTestFixture.M.writeValueAsBytes(input)).request;
    }

    private ObjectNode eligible() {
        ObjectNode value = PublicHealthTestFixture.M.createObjectNode().put("minAgeYears", 40).put("maxAgeYears", 40);
        value.putArray("sexes").add("male");
        value.putArray("conditionCodes").add("another-code").add("SYNTHETIC-HTN");
        value.putArray("riskLevels").add("low").add("high");
        return value;
    }

    @Test void requiresEveryDimensionAndAnyMatchWithinConditionRiskListsWithInclusiveAge() throws Exception {
        PublicHealthRequest request = parse(input());
        JsonNode profile = PublicHealthHealthProfile.build(request, PublicHealthTestFixture.M);
        ObjectNode eligibility = eligible();
        assertTrue(PublicHealthCheckEligibility.matches(eligibility, request, profile));
        List<Consumer<ObjectNode>> changes = Arrays.asList(
                value -> value.put("minAgeYears", 41).put("maxAgeYears", 80),
                value -> value.put("minAgeYears", 20).put("maxAgeYears", 39),
                value -> value.putArray("sexes").add("female"),
                value -> value.putArray("conditionCodes").add("missing-code"),
                value -> value.putArray("riskLevels").add("missing-grade")
        );
        for (Consumer<ObjectNode> change : changes) {
            ObjectNode altered = eligible(); change.accept(altered);
            assertFalse(PublicHealthCheckEligibility.matches(altered, request, profile));
        }
        assertTrue(PublicHealthCheckEligibility.matches(PublicHealthTestFixture.M.createObjectNode(), request, profile));
    }

    @Test void absentUnknownFutureAndConflictingGradesCannotSatisfyRiskRestrictions() throws Exception {
        for (String scenario : Arrays.asList("absent", "future", "conflict")) {
            ObjectNode input = input();
            ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
            if ("absent".equals(scenario)) observations.removeAll();
            if ("future".equals(scenario)) ((ObjectNode) observations.get(0)).put("observedAt", "2026-09-30T00:00:00+08:00");
            if ("conflict".equals(scenario)) {
                PublicHealthTestFixture.observation(observations, "conflict-risk", "hypertension_risk_level", "synthetic-unit", 1);
                ((ObjectNode) observations.get(1)).put("value", "low");
            }
            PublicHealthRequest request = parse(input);
            assertFalse(PublicHealthCheckEligibility.matches(eligible(), request,
                    PublicHealthHealthProfile.build(request, PublicHealthTestFixture.M)), scenario);
        }
        ObjectNode input = input(); ((ObjectNode) input.path("patient")).put("sex", "unknown");
        PublicHealthRequest unknown = parse(input);
        assertFalse(PublicHealthCheckEligibility.matches(eligible(), unknown, PublicHealthHealthProfile.build(unknown, PublicHealthTestFixture.M)));
    }

    @Test void filtersWithoutMutatingApprovedPolicyAndRetainsChecksWithNoExtraRestrictions() throws Exception {
        PublicHealthRequest request = parse(input());
        ObjectNode policy = PublicHealthTestFixture.policy();
        ArrayNode checks = policy.withArray("checkWhitelist");
        ObjectNode restricted = checks.addObject().put("code", "restricted");
        restricted.putObject("eligibility").putArray("sexes").add("female");
        JsonNode before = policy.deepCopy();
        ObjectNode filtered = PublicHealthCheckEligibility.filter(policy, request, PublicHealthHealthProfile.build(request, PublicHealthTestFixture.M));
        assertEquals(1, filtered.path("checkWhitelist").size());
        assertEquals("SYNTHETIC-CHECK", filtered.path("checkWhitelist").get(0).path("code").asText());
        assertEquals(before, policy);
    }

    @Test void rejectsInvalidApprovedRestrictionFieldsAndTypes() {
        List<Consumer<ObjectNode>> changes = Arrays.asList(
                value -> value.put("unknown", true),
                value -> value.putNull("minAgeYears"),
                value -> value.put("minAgeYears", -1),
                value -> value.put("maxAgeYears", 131),
                value -> value.put("minAgeYears", 40.5),
                value -> value.put("minAgeYears", "40"),
                value -> value.put("minAgeYears", 41),
                value -> value.putArray("sexes").add("unknown"),
                value -> value.putArray("sexes").add("male").add("male"),
                value -> value.putArray("conditionCodes"),
                value -> value.putArray("conditionCodes").add(1),
                value -> value.putArray("riskLevels").add(" "),
                value -> value.putArray("riskLevels").add("high").add("high"),
                value -> value.putArray("riskLevels").add(String.join("", Collections.nCopies(129, "x"))),
                value -> value.putNull("riskLevels")
        );
        for (Consumer<ObjectNode> change : changes) {
            ObjectNode value = eligible(); change.accept(value);
            assertEquals("POLICY_NOT_READY", assertThrows(PublicHealthException.class,
                    () -> PublicHealthCheckEligibility.validate(value)).getCode());
        }
        assertThrows(PublicHealthException.class, () -> PublicHealthCheckEligibility.validate(PublicHealthTestFixture.M.createArrayNode()));
        assertThrows(PublicHealthException.class, () -> PublicHealthCheckEligibility.validate(PublicHealthTestFixture.M.nullNode()));
    }

    @Test void scheduleUsesSnapshotForAbsentOrOverdueChecksAndFiltersWithoutMutation() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        observation(observations, "old", "SYNTHETIC-CHECK", 10, "2026-06-01T09:00:00+08:00");
        ObjectNode policy = PublicHealthTestFixture.policy();
        addRule(policy, "no-baseline", 365, 7);
        addRule(policy, "ineligible", 30, 7).putObject("eligibility").putArray("sexes").add("female");
        JsonNode beforeInput = input.deepCopy(), beforePolicy = policy.deepCopy();
        JsonNode constraints = schedule(input, policy);
        assertEquals(2, constraints.size());
        assertEquals("SYNTHETIC-CHECK", constraints.get(0).path("code").asText());
        assertEquals("no-baseline", constraints.get(1).path("code").asText());
        for (JsonNode check : constraints) {
            assertEquals(4, check.size());
            assertEquals("2026-09-29", check.path("routineEarliestDate").asText());
            assertTrue(check.path("routineAvailable").asBoolean());
            assertFalse(check.path("abnormalRetestAvailable").asBoolean());
            assertFalse(check.has("abnormalRetestEarliestDate"));
        }
        assertEquals(beforeInput, input);
        assertEquals(beforePolicy, policy);
    }

    @Test void scheduleCountsCurrentAndConflictDatesFromAnySourceButExcludesFutureInstants() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        ObjectNode current = observation(observations, "current", "SYNTHETIC-CHECK", 10, "2026-09-29T09:00:00+08:00");
        current.putObject("source").put("system", "MOCK_PHIS").put("recordId", "SYNTHETIC-CURRENT-FOLLOWUP");
        ObjectNode conflicting = observation(observations, "conflict", "SYNTHETIC-CHECK", 11, "2026-09-29T09:00:00+08:00");
        conflicting.putObject("source").put("system", "人工补充").put("recordId", "SYNTHETIC-MANUAL");
        observation(observations, "future", "SYNTHETIC-CHECK", 12, "2026-09-30T09:00:00+08:00");
        observation(observations, "future-only", "future-only", 13, "2026-09-29T10:00:00.000000001+08:00");
        ObjectNode policy = PublicHealthTestFixture.policy();
        addRule(policy, "future-only", 365, 7);
        JsonNode constraints = schedule(input, policy);
        assertEquals("2026-10-29", constraints.get(0).path("routineEarliestDate").asText());
        assertTrue(constraints.get(0).path("routineAvailable").asBoolean());
        assertFalse(constraints.get(0).path("abnormalRetestAvailable").asBoolean());
        assertEquals("2026-09-29", constraints.get(1).path("routineEarliestDate").asText());
        assertTrue(constraints.get(1).path("routineAvailable").asBoolean());
    }

    @Test void scheduleUsesLatestImplementationLocalDateAcrossAllNonFutureOffsets() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        observation(observations, "latest-instant", "SYNTHETIC-CHECK", 10, "2026-09-28T23:30:00-01:00");
        observation(observations, "latest-local-date", "SYNTHETIC-CHECK", 11, "2026-09-29T00:30:00+01:00");
        assertEquals("2026-10-29", schedule(input, PublicHealthTestFixture.policy()).get(0).path("routineEarliestDate").asText());
    }

    @Test void scheduleKeepsEarliestDatesAndUsesInclusiveNinetyDayWindowForBothModes() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        observation((ArrayNode) input.path("facts").path("observations"), "current", "SYNTHETIC-CHECK", 10, input.path("snapshotAt").asText());
        for (int days : Arrays.asList(0, 90, 91, 365)) {
            ObjectNode policy = PublicHealthTestFixture.policy();
            ObjectNode rule = (ObjectNode) policy.path("checkWhitelist").get(0);
            rule.put("minIntervalDays", days).put("abnormalRetestIntervalDays", days);
            criterion(rule.putArray("abnormalCriteria"), "SYNTHETIC-CHECK", "gte", 10, 0);
            JsonNode check = schedule(input, policy).get(0);
            String earliest = LocalDate.of(2026, 9, 29).plusDays(days).toString();
            assertEquals(earliest, check.path("routineEarliestDate").asText());
            assertEquals(earliest, check.path("abnormalRetestEarliestDate").asText());
            assertEquals(days <= 90, check.path("routineAvailable").asBoolean());
            assertEquals(days <= 90, check.path("abnormalRetestAvailable").asBoolean());
        }
    }

    @Test void scheduleRequiresFreshUsableNumericEvidenceForEveryApprovedAbnormalCriterion() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        observation(observations, "result", "SYNTHETIC-CHECK", 10, "2026-09-28T09:00:00+08:00");
        observation(observations, "other-result", "other-code", 20, "2026-09-28T09:00:00+08:00");
        ObjectNode policy = PublicHealthTestFixture.policy();
        ObjectNode rule = (ObjectNode) policy.path("checkWhitelist").get(0);
        rule.put("minIntervalDays", 365);
        ArrayNode criteria = rule.putArray("abnormalCriteria");
        criterion(criteria, "SYNTHETIC-CHECK", "gte", 10, 1);
        criterion(criteria, "other-code", "lt", 21, 1);
        JsonNode supported = schedule(input, policy).get(0);
        assertFalse(supported.path("routineAvailable").asBoolean());
        assertTrue(supported.path("abnormalRetestAvailable").asBoolean());
        assertEquals("2026-10-05", supported.path("abnormalRetestEarliestDate").asText());
        List<Consumer<ObjectNode>> unsupported = Arrays.asList(
                value -> ((ObjectNode) value.path("facts").path("observations").get(0)).put("value", "10"),
                value -> ((ObjectNode) value.path("facts").path("observations").get(0)).put("value", 9),
                value -> ((ObjectNode) value.path("facts").path("observations").get(0)).put("unit", "wrong-unit"),
                value -> ((ObjectNode) value.path("facts").path("observations").get(0)).put("observedAt", "2026-09-27T09:00:00+08:00"),
                value -> ((ObjectNode) value.path("facts").path("observations").get(0)).put("observedAt", "2026-09-30T09:00:00+08:00"),
                value -> observation((ArrayNode) value.path("facts").path("observations"), "conflict", "SYNTHETIC-CHECK", 11, "2026-09-28T09:00:00+08:00"),
                value -> ((ArrayNode) value.path("facts").path("observations")).remove(1),
                value -> ((ArrayNode) value.path("facts").path("observations")).remove(0)
        );
        for (Consumer<ObjectNode> change : unsupported) {
            ObjectNode altered = input.deepCopy(); change.accept(altered);
            JsonNode check = schedule(altered, policy).get(0);
            assertFalse(check.path("abnormalRetestAvailable").asBoolean());
            assertFalse(check.has("abnormalRetestEarliestDate"));
        }
        ObjectNode noCriteria = policy.deepCopy();
        ((ObjectNode) noCriteria.path("checkWhitelist").get(0)).remove("abnormalCriteria");
        assertFalse(schedule(input, noCriteria).get(0).has("abnormalRetestEarliestDate"));
    }

    @Test void scheduleHonorsNumericComparisonBoundariesAndKeepsConflictDatesForUsableOlderEvidence() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        observation(observations, "usable-older", "SYNTHETIC-CHECK", 10, "2026-09-28T09:00:00+08:00");
        observation(observations, "conflict-a", "SYNTHETIC-CHECK", 10, "2026-09-29T09:00:00+08:00");
        observation(observations, "conflict-b", "SYNTHETIC-CHECK", 11, "2026-09-29T09:00:00+08:00");
        ObjectNode policy = PublicHealthTestFixture.policy();
        ObjectNode rule = (ObjectNode) policy.path("checkWhitelist").get(0);
        Map<String, Integer> passing = new LinkedHashMap<String, Integer>();
        passing.put("gt", 9); passing.put("gte", 10); passing.put("lt", 11); passing.put("lte", 10); passing.put("eq", 10);
        Map<String, Integer> failing = new LinkedHashMap<String, Integer>();
        failing.put("gt", 10); failing.put("gte", 11); failing.put("lt", 10); failing.put("lte", 9); failing.put("eq", 11);
        for (Map.Entry<String, Integer> example : passing.entrySet()) {
            criterion(rule.putArray("abnormalCriteria"), "SYNTHETIC-CHECK", example.getKey(), example.getValue(), 1);
            JsonNode check = schedule(input, policy).get(0);
            assertTrue(check.path("abnormalRetestAvailable").asBoolean(), example.getKey());
            assertEquals("2026-10-06", check.path("abnormalRetestEarliestDate").asText());
            assertEquals("2026-10-29", check.path("routineEarliestDate").asText());
        }
        for (Map.Entry<String, Integer> example : failing.entrySet()) {
            criterion(rule.putArray("abnormalCriteria"), "SYNTHETIC-CHECK", example.getKey(), example.getValue(), 1);
            JsonNode check = schedule(input, policy).get(0);
            assertFalse(check.path("abnormalRetestAvailable").asBoolean(), example.getKey());
            assertFalse(check.has("abnormalRetestEarliestDate"));
        }
    }

    @Test void scheduleClampsAbnormalCandidateToSnapshotWhenNoSameCodeResultOrIntervalAlreadyPassed() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        observation(observations, "trigger", "other-code", 10, "2026-09-28T09:00:00+08:00");
        ObjectNode policy = PublicHealthTestFixture.policy();
        ObjectNode rule = (ObjectNode) policy.path("checkWhitelist").get(0);
        criterion(rule.putArray("abnormalCriteria"), "other-code", "eq", 10, 1);
        JsonNode check = schedule(input, policy).get(0);
        assertTrue(check.path("abnormalRetestAvailable").asBoolean());
        assertEquals("2026-09-29", check.path("abnormalRetestEarliestDate").asText());
        observation(observations, "old-check", "SYNTHETIC-CHECK", 1, "2026-06-01T09:00:00+08:00");
        assertEquals("2026-09-29", schedule(input, policy).get(0).path("abnormalRetestEarliestDate").asText());
    }

    private JsonNode schedule(ObjectNode input, ObjectNode policy) throws Exception {
        PublicHealthRequest request = parse(input);
        return PublicHealthCheckEligibility.schedule(request, policy, PublicHealthHealthProfile.build(request, PublicHealthTestFixture.M), PublicHealthTestFixture.M);
    }

    private ObjectNode observation(ArrayNode observations, String id, String code, int value, String observedAt) {
        PublicHealthTestFixture.observation(observations, id, code, "synthetic-unit", value);
        return ((ObjectNode) observations.get(observations.size() - 1)).put("observedAt", observedAt);
    }

    private ObjectNode addRule(ObjectNode policy, String code, int routine, int abnormal) {
        return policy.withArray("checkWhitelist").addObject().put("code", code).put("name", PublicHealthTestFixture.NOTICE)
                .put("prep", PublicHealthTestFixture.NOTICE).put("minIntervalDays", routine).put("abnormalRetestIntervalDays", abnormal);
    }

    private void criterion(ArrayNode criteria, String code, String operator, int threshold, int maxAge) {
        criteria.addObject().put("observationCode", code).put("unit", "synthetic-unit")
                .put("operator", operator).put("threshold", threshold).put("maxEvidenceAgeDays", maxAge);
    }
}
