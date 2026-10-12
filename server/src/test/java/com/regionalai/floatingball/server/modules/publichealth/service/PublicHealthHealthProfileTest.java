package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PublicHealthHealthProfileTest {
    private PublicHealthRequest parse(ObjectNode input) throws Exception {
        return new PublicHealthValidator(PublicHealthTestFixture.M).parse(PublicHealthTestFixture.M.writeValueAsBytes(input)).request;
    }

    private ObjectNode observation(ObjectNode input, String id, String code, String time, String value) {
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, id, code, "synthetic-unit", 1);
        ObjectNode observation = (ObjectNode) observations.get(observations.size() - 1);
        observation.put("observedAt", time).put("value", value);
        return observation;
    }

    private Set<String> refs(JsonNode profile) {
        Set<String> refs = new HashSet<String>();
        for (JsonNode item : profile.path("evidence")) refs.add(item.path("ref").asText());
        return refs;
    }

    @Test void usesSuppliedFactsSourcesAndNewestObservationWithoutMutatingSnapshot() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ObjectNode facts = (ObjectNode) input.path("facts");
        facts.putArray("conditions").addObject().put("code", "SYNTHETIC-HTN").put("name", "合成病种").put("status", "合成随访中");
        facts.putArray("medicationClasses").addObject().put("code", "SYNTHETIC-CLASS").put("name", "合成类别");
        facts.put("historySummary", "合成就诊与慢病档案摘要").put("nextFollowUpDate", "2026-10-29");
        observation(input, "old", "synthetic-result", "2026-09-20T10:00:00+08:00", "合成历史结果");
        ObjectNode current = observation(input, "latest", "synthetic-result", "2026-09-29T10:00:00+08:00", "合成当前结果");
        ((ObjectNode) current.path("source")).put("system", "SYNTHETIC-EXAM").put("recordId", "SYNTHETIC-REPORT");
        observation(input, "future", "synthetic-result", "2026-09-29T10:00:01+08:00", "不应采用的未来结果");
        PublicHealthRequest request = parse(input);
        JsonNode original = PublicHealthTestFixture.M.valueToTree(request);
        ObjectNode profile = PublicHealthHealthProfile.build(request, PublicHealthTestFixture.M);
        assertEquals("latest", profile.path("latestObservations").get(0).path("id").asText());
        assertEquals(2, profile.path("sources").size());
        assertEquals("old", profile.path("sources").get(0).path("observationIds").get(0).asText());
        assertEquals("latest", profile.path("sources").get(1).path("observationIds").get(0).asText());
        for (JsonNode item : profile.path("evidence")) {
            assertTrue(item.path("value").isNumber() || item.path("value").isTextual());
        }
        assertEquals("future", profile.path("excludedObservationIds").get(0).asText());
        assertEquals(0, profile.path("conflicts").size());
        assertEquals("合成就诊与慢病档案摘要", profile.path("historySummary").asText());
        assertEquals("2026-10-29", profile.path("nextFollowUpDate").asText());
        assertTrue(refs(profile).containsAll(Arrays.asList("patient:ageYears", "patient:sex", "condition:0", "medication:0", "lifestyle:diet", "historySummary", "observation:old", "observation:latest")));
        assertFalse(refs(profile).contains("observation:future"));
        ((ObjectNode) profile.path("patient")).put("name", "投影修改不会改请求");
        assertEquals(original, PublicHealthTestFixture.M.valueToTree(request));
    }

    @Test void conflictingLatestRiskDoesNotFallBackToEarlierRiskGrade() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        observation(input, "old-risk", "risk_level", "2026-09-20T10:00:00+08:00", "high");
        observation(input, "risk-high", "risk_level", "2026-09-28T10:00:00+08:00", "high");
        observation(input, "risk-low", "risk_level", "2026-09-28T02:00:00Z", "low");
        JsonNode profile = PublicHealthHealthProfile.build(parse(input), PublicHealthTestFixture.M);
        assertEquals(0, profile.path("knownRiskAssessments").size());
        assertEquals(0, profile.path("latestObservations").size());
        assertEquals("risk_level", profile.path("conflicts").get(0).path("code").asText());
        assertEquals(2, profile.path("conflicts").get(0).path("observationIds").size());
        assertTrue(refs(profile).contains("observation:old-risk"));
        assertFalse(refs(profile).contains("observation:risk-high"));
        assertFalse(refs(profile).contains("observation:risk-low"));
    }

    @Test void treatsEquivalentNumbersAsDuplicatesButChangedUnitsAsConflicts() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ObjectNode one = observation(input, "one", "synthetic-result", "2026-09-28T10:00:00+08:00", "temporary");
        ObjectNode decimal = observation(input, "decimal", "synthetic-result", "2026-09-28T10:00:00+08:00", "temporary");
        one.put("value", 1); decimal.put("value", 1.0);
        JsonNode profile = PublicHealthHealthProfile.build(parse(input), PublicHealthTestFixture.M);
        assertEquals(1, profile.path("latestObservations").size());
        assertEquals(0, profile.path("conflicts").size());
        assertTrue(refs(profile).containsAll(Arrays.asList("observation:one", "observation:decimal")));
        decimal.put("unit", "different-synthetic-unit");
        profile = PublicHealthHealthProfile.build(parse(input), PublicHealthTestFixture.M);
        assertEquals(0, profile.path("latestObservations").size());
        assertEquals(1, profile.path("conflicts").size());
        assertFalse(refs(profile).contains("observation:one"));
    }

    @Test void preservesAllSupportedKnownRiskCodesAndDoesNotInferOneFromOtherText() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        for (String code : Arrays.asList("risk_level", "hypertension_risk_level", "diabetes_risk_level", "cardiovascular_risk_level", "arbitrary-risk-name")) {
            observation(input, code, code, "2026-09-28T10:00:00+08:00", "合成已有分层");
        }
        JsonNode profile = PublicHealthHealthProfile.build(parse(input), PublicHealthTestFixture.M);
        assertEquals(4, profile.path("knownRiskAssessments").size());
        assertEquals(5, profile.path("latestObservations").size());
        assertEquals("合成已有分层", profile.path("knownRiskAssessments").get(0).path("value").asText());
        assertEquals("SYNTHETIC", profile.path("knownRiskAssessments").get(0).path("source").path("system").asText());
    }

    @Test void missingFactsStayMissingAndRefreshedSnapshotUsesNewFacts() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ((ObjectNode) input.path("patient")).put("sex", "unknown");
        JsonNode before = PublicHealthHealthProfile.build(parse(input), PublicHealthTestFixture.M);
        assertFalse(refs(before).contains("patient:sex"));
        assertFalse(before.has("historySummary"));
        assertEquals(0, before.path("knownRiskAssessments").size());
        assertEquals(0, before.path("latestObservations").size());
        observation(input, "new-risk", "hypertension_risk_level", "2026-09-28T10:00:00+08:00", "合成更新分层");
        ((ObjectNode) input.path("facts").path("lifestyle")).put("diet", "合成已更新饮食");
        JsonNode after = PublicHealthHealthProfile.build(parse(input), PublicHealthTestFixture.M);
        assertEquals(1, after.path("knownRiskAssessments").size());
        assertEquals("合成已更新饮食", after.path("lifestyle").path("diet").asText());
        assertEquals(0, before.path("knownRiskAssessments").size());
    }
}
