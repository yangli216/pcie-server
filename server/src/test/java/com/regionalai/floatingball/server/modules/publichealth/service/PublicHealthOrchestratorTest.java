package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.regionalai.floatingball.server.modules.prompt.dto.PromptView;
import com.regionalai.floatingball.server.modules.prompt.service.PromptService;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicHealthOrchestratorTest {
    PublicHealthAssetService assets;
    PublicHealthRecipeRetriever retrieval;
    PublicHealthModelService model;
    PromptService prompts;
    PublicHealthOrchestrator service;
    PublicHealthProperties properties;

    @BeforeEach void setup() {
        assets = mock(PublicHealthAssetService.class);
        retrieval = mock(PublicHealthRecipeRetriever.class);
        model = mock(PublicHealthModelService.class);
        prompts = mock(PromptService.class);
        properties = new PublicHealthProperties();
        properties.setRetrievalEnabled(true);
        service = new PublicHealthOrchestrator(assets, retrieval, new WeightMetricsCalculator(PublicHealthTestFixture.M), prompts, model, properties,
        PublicHealthTestFixture.M);
        when(prompts.resolveEffectivePrompt(anyString(), anyString(), any())).thenAnswer(i -> {
            PromptView p = new PromptView(); p.setCdPrompt(i.getArgument(0)); p.setVersionNum("synthetic-v1"); p.setSysPrompt(PublicHealthTestFixture.NOTICE); return p;
        }
        );
        when(model.generate(any(), any(), any(), anyString(), anyString(), anyString())).thenAnswer(i -> {
            String section = i.getArgument(4); ObjectNode r = PublicHealthTestFixture.M.createObjectNode();
            if ("lifestyle_guidance".equals(section)) return PublicHealthTestFixture.healthEducation();
            if ("meal_plan".equals(section) && ((JsonNode)i.getArgument(2)).path("recipeReferences").size() >= 3) return mealRaw(((JsonNode)i.getArgument(2)).path("recipeReferences"));
            if ("check_plan".equals(section))r.putArray("checks"); else if ("medication_guidance".equals(section))r.putArray("medicationGuidance"); else r.put("content",
            PublicHealthTestFixture.NOTICE); return r;
        }
        );
    }

    private ObjectNode mealRaw(JsonNode references) {
        ObjectNode raw = PublicHealthTestFixture.M.createObjectNode();
        ArrayNode options = raw.putArray("options");
        for (int n = 0; n < 3; n++) options.addObject().put("recipeId", references.get(n).path("id").asText()).put("content", PublicHealthTestFixture.NOTICE + " " + n);
        return raw;
    }

    @Test void healthProducesThreeValidatedSectionsAndActualVersion() throws Exception {
        PublicHealthRequest r = PublicHealthTestFixture.parsed("health_prescription").request;
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(PublicHealthTestFixture.policy(), null, PublicHealthTestFixture.versions()));
        PublicHealthOrchestrator.Listener listener = mock(PublicHealthOrchestrator.Listener.class);
        ObjectNode result = service.generate(PublicHealthTestFixture.device(), r,"generation", service.prepare(PublicHealthTestFixture.device(),
        r), listener);
        assertEquals(3, result.path("sections").size());
        assertEquals(1, result.path("promptVersions").size());
        assertEquals(r.getOriginRequestId(), result.path("originRequestId").asText());
        verify(listener, times(3)).section(any());
        verify(model, times(3)).generate(any(), any(), any(), anyString(), anyString(), anyString());
        verifyNoInteractions(retrieval);
    }

    @Test void healthNodesReceiveCurrentProfileAndOnlyUsableObservationsWithStructuredEducation() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "current", "current-code", "synthetic-unit", 10);
        PublicHealthTestFixture.observation(observations, "future", "future-code", "synthetic-unit", 11);
        ((ObjectNode) observations.get(1)).put("observedAt", "2026-09-30T00:00:00+08:00");
        PublicHealthTestFixture.observation(observations, "conflict-a", "conflict-code", "synthetic-unit", 10);
        PublicHealthTestFixture.observation(observations, "conflict-b", "conflict-code", "synthetic-unit", 11);
        PublicHealthRequest request = parse(input);
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(PublicHealthTestFixture.policy(), null, PublicHealthTestFixture.versions()));
        List<JsonNode> contexts = new ArrayList<JsonNode>();
        when(model.generate(any(), any(), any(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            JsonNode context = call.getArgument(2); contexts.add(context.deepCopy());
            String section = call.getArgument(4);
            ObjectNode raw = PublicHealthTestFixture.M.createObjectNode();
            if ("check_plan".equals(section)) raw.putArray("checks");
            else if ("medication_guidance".equals(section)) raw.putArray("medicationGuidance");
            else {
                raw = PublicHealthTestFixture.healthEducation();
                ObjectNode risk = raw.withArray("risks").addObject().put("type", "progression").put("name", "合成进展关注").put("concern", "needs_assessment").put("intervention", PublicHealthTestFixture.NOTICE);
                risk.putArray("evidenceRefs").add("observation:current");
                assertEquals(PublicHealthHealthEducation.OUTPUT_SHAPE, call.getArgument(5));
            }
            return raw;
        });
        ObjectNode result = service.generate(PublicHealthTestFixture.device(), request, "generation", service.prepare(PublicHealthTestFixture.device(), request), mock(PublicHealthOrchestrator.Listener.class));
        assertEquals(3, contexts.size());
        for (JsonNode context : contexts) {
            assertEquals(1, context.path("healthProfile").path("latestObservations").size());
            assertEquals("future", context.path("healthProfile").path("excludedObservationIds").get(0).asText());
            assertEquals(1, context.path("healthProfile").path("conflicts").size());
            assertEquals(1, context.path("facts").path("observations").size());
            assertEquals("current", context.path("facts").path("observations").get(0).path("id").asText());
            assertEquals(1, context.path("checkScheduleConstraints").size());
            assertEquals("2026-09-29", context.path("checkScheduleConstraints").get(0).path("routineEarliestDate").asText());
            assertFalse(context.has("writebackTargets"));
        }
        assertEquals(4, request.getFacts().getObservations().size());
        assertFalse(result.has("healthProfile"));
        assertFalse(result.has("riskPrediction"));
        assertFalse(result.has("checkScheduleConstraints"));
        assertTrue(result.path("sections").get(2).path("content").asText().contains("合成进展关注"));
        assertTrue(result.path("sections").get(2).path("content").asText().contains("SYNTHETIC-FACT"));
    }

    @Test void currentFollowUpMeasurementLimitsAllHealthNodeSchedulesAndStillRejectsEarlierOutput() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "current-check", "SYNTHETIC-CHECK", "synthetic-unit", 10);
        ((ObjectNode) observations.get(0)).put("observedAt", input.path("snapshotAt").asText())
                .putObject("source").put("system", "MOCK_PHIS").put("recordId", "SYNTHETIC-CURRENT-FOLLOWUP");
        PublicHealthRequest request = parse(input);
        ObjectNode policy = PublicHealthTestFixture.policy();
        ((ObjectNode) policy.path("checkWhitelist").get(0)).put("minIntervalDays", 365);
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(policy, null, PublicHealthTestFixture.versions()));
        List<JsonNode> contexts = new ArrayList<JsonNode>();
        when(model.generate(any(), any(), any(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            contexts.add(((JsonNode) call.getArgument(2)).deepCopy());
            String section = call.getArgument(4);
            ObjectNode raw = PublicHealthTestFixture.M.createObjectNode();
            if ("check_plan".equals(section)) raw.putArray("checks");
            else if ("medication_guidance".equals(section)) raw.putArray("medicationGuidance");
            else return PublicHealthTestFixture.healthEducation();
            return raw;
        });
        ObjectNode result = generateHealth(request);
        assertEquals(3, contexts.size());
        for (JsonNode context : contexts) {
            JsonNode check = context.path("checkScheduleConstraints").get(0);
            assertEquals("SYNTHETIC-CHECK", check.path("code").asText());
            assertEquals("2027-09-29", check.path("routineEarliestDate").asText());
            assertFalse(check.path("routineAvailable").asBoolean());
            assertFalse(check.path("abnormalRetestAvailable").asBoolean());
            assertFalse(check.has("abnormalRetestEarliestDate"));
            assertEquals(1, context.path("policy").path("checkWhitelist").size());
        }
        assertEquals(3, result.path("sections").size());
        assertFalse(result.has("checkScheduleConstraints"));
        ObjectNode early = checkRaw("current-check");
        ((ObjectNode) early.path("checks").get(0)).putArray("evidenceObservationIds");
        ((ObjectNode) early.path("checks").get(0)).putArray("evidenceRefs").add("patient:ageYears");
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(early);
        PublicHealthOrchestrator.Listener listener = mock(PublicHealthOrchestrator.Listener.class);
        assertEquals("INVALID_MODEL_OUTPUT", assertThrows(PublicHealthException.class,
                () -> service.generate(PublicHealthTestFixture.device(), request, "generation", service.prepare(PublicHealthTestFixture.device(), request), listener)).getCode());
        verify(listener, never()).section(any());
    }

    @Test void filtersIneligibleChecksBeforeModelAndRejectsTheirOutputEvenWithValidEvidence() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        PublicHealthTestFixture.observation((ArrayNode) input.path("facts").path("observations"), "evidence", "another-result", "synthetic-unit", 10);
        PublicHealthRequest request = parse(input);
        ObjectNode policy = PublicHealthTestFixture.policy();
        ((ObjectNode) policy.path("checkWhitelist").get(0)).putObject("eligibility").putArray("sexes").add("female");
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(policy, null, PublicHealthTestFixture.versions()));
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenAnswer(call -> {
            JsonNode context = call.getArgument(2);
            assertEquals(0, context.path("policy").path("checkWhitelist").size());
            assertEquals(0, context.path("checkScheduleConstraints").size());
            return checkRaw("evidence");
        });
        assertEquals("INVALID_MODEL_OUTPUT", assertThrows(PublicHealthException.class,
                () -> service.generate(PublicHealthTestFixture.device(), request, "generation", service.prepare(PublicHealthTestFixture.device(), request), mock(PublicHealthOrchestrator.Listener.class))).getCode());
        verify(model, times(1)).generate(any(), any(), any(), anyString(), anyString(), anyString());
        assertEquals(1, policy.path("checkWhitelist").size());
        ObjectNode empty = PublicHealthTestFixture.M.createObjectNode(); empty.putArray("checks");
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(empty);
        assertTrue(service.generate(PublicHealthTestFixture.device(), request, "generation", service.prepare(PublicHealthTestFixture.device(), request), mock(PublicHealthOrchestrator.Listener.class))
                .path("sections").get(0).path("content").asText().contains("未生成检查计划"));
    }

    @Test void eligibleRiskUsesLatestSuppliedGradeAndRetainsCheckTimingAndEvidenceRules() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "evidence", "another-result", "synthetic-unit", 10);
        PublicHealthTestFixture.observation(observations, "risk", "hypertension_risk_level", "synthetic-unit", 1);
        ((ObjectNode) observations.get(1)).put("value", "high");
        PublicHealthRequest request = parse(input);
        ObjectNode policy = PublicHealthTestFixture.policy();
        ObjectNode rule = (ObjectNode) policy.path("checkWhitelist").get(0);
        ObjectNode eligibility = rule.putObject("eligibility").put("minAgeYears", 40).put("maxAgeYears", 40);
        eligibility.putArray("sexes").add("male"); eligibility.putArray("riskLevels").add("high");
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(policy, null, PublicHealthTestFixture.versions()));
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenAnswer(call -> {
            assertEquals(1, ((JsonNode) call.getArgument(2)).path("policy").path("checkWhitelist").size());
            return checkRaw("evidence");
        });
        ObjectNode result = service.generate(PublicHealthTestFixture.device(), request, "generation", service.prepare(PublicHealthTestFixture.device(), request), mock(PublicHealthOrchestrator.Listener.class));
        assertTrue(result.path("sections").get(0).path("content").asText().contains("2026-10-29"));
        assertTrue(result.path("sections").get(0).path("content").asText().contains("SYNTHETIC-FACT"));
        PublicHealthTestFixture.observation(observations, "conflict-risk", "hypertension_risk_level", "synthetic-unit", 2);
        ((ObjectNode) observations.get(2)).put("value", "low");
        PublicHealthRequest conflict = parse(input);
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(checkRaw("evidence"));
        assertThrows(PublicHealthException.class, () -> service.generate(PublicHealthTestFixture.device(), conflict, "generation", service.prepare(PublicHealthTestFixture.device(), conflict), mock(PublicHealthOrchestrator.Listener.class)));
    }

    @Test void invalidEducationRefIsNeverEmittedAndDoesNotTriggerRetry() throws Exception {
        PublicHealthRequest request = PublicHealthTestFixture.parsed("health_prescription").request;
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(PublicHealthTestFixture.policy(), null, PublicHealthTestFixture.versions()));
        ObjectNode raw = PublicHealthTestFixture.healthEducation();
        raw.withArray("risks").addObject().put("type", "complication").put("name", "合成关注").put("concern", "attention").put("intervention", PublicHealthTestFixture.NOTICE).putArray("evidenceRefs").add("observation:invented");
        when(model.generate(any(), any(), any(), anyString(), eq("lifestyle_guidance"), anyString())).thenReturn(raw);
        PublicHealthOrchestrator.Listener listener = mock(PublicHealthOrchestrator.Listener.class);
        assertThrows(PublicHealthException.class, () -> service.generate(PublicHealthTestFixture.device(), request, "generation", service.prepare(PublicHealthTestFixture.device(), request), listener));
        verify(listener, times(2)).section(any());
        verify(model, times(3)).generate(any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test void completedCheckWithConflictingResultsStillEnforcesMinimumInterval() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "evidence", "another-result", "synthetic-unit", 10);
        PublicHealthTestFixture.observation(observations, "result-a", "SYNTHETIC-CHECK", "synthetic-unit", 10);
        PublicHealthTestFixture.observation(observations, "result-b", "SYNTHETIC-CHECK", "synthetic-unit", 11);
        PublicHealthRequest request = parse(input);
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(PublicHealthTestFixture.policy(), null, PublicHealthTestFixture.versions()));
        ObjectNode tooSoon = checkRaw("evidence");
        ((ObjectNode) tooSoon.path("checks").get(0)).put("plannedDate", "2026-10-05");
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(tooSoon);
        assertThrows(PublicHealthException.class, () -> service.generate(PublicHealthTestFixture.device(), request, "generation", service.prepare(PublicHealthTestFixture.device(), request), mock(PublicHealthOrchestrator.Listener.class)));
        verify(model, times(1)).generate(any(), any(), any(), anyString(), anyString(), anyString());
    }

    private PublicHealthRequest parse(ObjectNode input) throws Exception {
        return new PublicHealthValidator(PublicHealthTestFixture.M).parse(PublicHealthTestFixture.M.writeValueAsBytes(input)).request;
    }

    private ObjectNode checkRaw(String evidenceId) {
        ObjectNode raw = PublicHealthTestFixture.M.createObjectNode();
        raw.putArray("checks").addObject().put("code", "SYNTHETIC-CHECK").put("plannedDate", "2026-10-29").put("priority", "medium")
                .put("reason", PublicHealthTestFixture.NOTICE).put("intervalKind", "routine").putArray("evidenceObservationIds").add(evidenceId);
        return raw;
    }

    @Test void missingBaselineRoutineCheckCanUseRealAgeAndConditionRefsWithoutBlockingOtherSections() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ((ObjectNode) input.path("patient")).put("ageYears", 65);
        ((ArrayNode) input.path("facts").path("conditions")).addObject().put("code", "SYNTHETIC-HTN").put("name", "合成病种").put("status", "合成随访");
        PublicHealthRequest request = parse(input);
        ObjectNode policy = PublicHealthTestFixture.policy();
        ((ObjectNode) policy.path("checkWhitelist").get(0)).putObject("eligibility").put("minAgeYears", 65).putArray("conditionCodes").add("SYNTHETIC-HTN");
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(policy, null, PublicHealthTestFixture.versions()));
        ObjectNode raw = checkRaw("not-used");
        ObjectNode check = (ObjectNode) raw.path("checks").get(0);
        check.withArray("evidenceObservationIds").removeAll();
        check.putArray("evidenceRefs").add("patient:ageYears").add("condition:0");
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenAnswer(call -> {
            String shape = call.getArgument(5);
            assertEquals(PublicHealthOrchestrator.CHECK_OUTPUT_SHAPE, shape);
            assertTrue(shape.contains("\"evidenceRefs\":[\"patient:ageYears\"]"));
            assertTrue(shape.contains("evidenceRefs为可选字段"));
            assertTrue(shape.contains("异常复查的数值证据"));
            return raw;
        });
        ObjectNode result = generateHealth(request);
        assertEquals(3, result.path("sections").size());
        String text = result.path("sections").get(0).path("content").asText();
        assertTrue(text.contains("年龄：65"));
        assertTrue(text.contains("健康状况：合成病种（合成随访）"));
        assertTrue(text.contains("资料快照：2026-09-29T10:00:00+08:00"));
        assertTrue(text.contains("既往结果：尚未提供本项目既往结果。"));
        assertFalse(result.path("sections").get(0).has("evidenceRefs"));
        verify(model, times(3)).generate(any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test void mixedObservationAndProfileRefsRenderInOrderAndRejectCrossClassDuplicate() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        PublicHealthTestFixture.observation((ArrayNode) input.path("facts").path("observations"), "evidence", "another-result", "synthetic-unit", 10);
        PublicHealthRequest request = parse(input);
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(PublicHealthTestFixture.policy(), null, PublicHealthTestFixture.versions()));
        ObjectNode raw = checkRaw("evidence");
        ObjectNode check = (ObjectNode) raw.path("checks").get(0);
        check.putArray("evidenceRefs").add("patient:ageYears");
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(raw);
        String text = generateHealth(request).path("sections").get(0).path("content").asText();
        assertTrue(text.indexOf("another-result：10 synthetic-unit") < text.indexOf("年龄：40"));
        assertTrue(text.contains("来源：SYNTHETIC / 记录：SYNTHETIC-FACT"));
        check.withArray("evidenceRefs").add("observation:evidence");
        clearInvocations(model);
        assertEquals("INVALID_MODEL_OUTPUT", assertThrows(PublicHealthException.class, () -> generateHealth(request)).getCode());
        verify(model, times(1)).generate(any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test void profileObservationRefsSupportRoutineButCannotReplaceOriginalAbnormalRetestEvidence() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        PublicHealthTestFixture.observation((ArrayNode) input.path("facts").path("observations"), "result", "SYNTHETIC-CHECK", "synthetic-unit", 10);
        PublicHealthRequest request = parse(input);
        ObjectNode policy = PublicHealthTestFixture.policy();
        ((ObjectNode) policy.path("checkWhitelist").get(0)).putArray("abnormalCriteria").addObject().put("observationCode", "SYNTHETIC-CHECK")
                .put("operator", "gt").put("threshold", 5).put("maxEvidenceAgeDays", 7).put("unit", "synthetic-unit");
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(policy, null, PublicHealthTestFixture.versions()));
        ObjectNode raw = checkRaw("not-used"); ObjectNode check = (ObjectNode) raw.path("checks").get(0);
        check.withArray("evidenceObservationIds").removeAll();
        check.putArray("evidenceRefs").add("observation:result");
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(raw);
        String text = generateHealth(request).path("sections").get(0).path("content").asText();
        assertTrue(text.contains("SYNTHETIC-CHECK：10 synthetic-unit"));
        assertFalse(text.contains("尚未提供本项目既往结果"));
        check.put("intervalKind", "abnormal_retest").put("plannedDate", "2026-10-05");
        assertEquals("INVALID_MODEL_OUTPUT", assertThrows(PublicHealthException.class, () -> generateHealth(request)).getCode());
        check.putArray("evidenceRefs").add("patient:ageYears");
        assertEquals("INVALID_MODEL_OUTPUT", assertThrows(PublicHealthException.class, () -> generateHealth(request)).getCode());
        check.withArray("evidenceObservationIds").add("result");
        assertTrue(generateHealth(request).path("sections").get(0).path("content").asText().contains("2026-10-05"));
    }

    @Test void profileRefsCannotSupplyMissingAbnormalCriterionAndConflictingResultsAreExplicit() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "other", "another-result", "synthetic-unit", 10);
        PublicHealthTestFixture.observation(observations, "result", "SYNTHETIC-CHECK", "synthetic-unit", 10);
        PublicHealthRequest request = parse(input);
        ObjectNode policy = PublicHealthTestFixture.policy();
        ((ObjectNode) policy.path("checkWhitelist").get(0)).putArray("abnormalCriteria").addObject().put("observationCode", "SYNTHETIC-CHECK")
                .put("operator", "gt").put("threshold", 5).put("maxEvidenceAgeDays", 7).put("unit", "synthetic-unit");
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(policy, null, PublicHealthTestFixture.versions()));
        ObjectNode raw = checkRaw("other"); ObjectNode check = (ObjectNode) raw.path("checks").get(0);
        check.put("intervalKind", "abnormal_retest").put("plannedDate", "2026-10-05");
        check.putArray("evidenceRefs").add("observation:result");
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(raw);
        assertEquals("POLICY_NOT_READY", assertThrows(PublicHealthException.class, () -> generateHealth(request)).getCode());
        PublicHealthTestFixture.observation(observations, "conflict", "SYNTHETIC-CHECK", "synthetic-unit", 11);
        PublicHealthRequest conflictRequest = parse(input);
        check.put("intervalKind", "routine").put("plannedDate", "2026-10-29");
        check.putArray("evidenceRefs").add("patient:ageYears");
        String text = generateHealth(conflictRequest).path("sections").get(0).path("content").asText();
        assertTrue(text.contains("既往结果：本项目最新结果存在冲突，请医生核对。"));
        assertFalse(text.contains("尚未提供本项目既往结果"));
    }

    @Test void rejectsInvalidOptionalRefsAndStrictFieldTypesBeforeEmittingAnyCheck() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "evidence", "another-result", "synthetic-unit", 10);
        PublicHealthTestFixture.observation(observations, "future", "future-code", "synthetic-unit", 1);
        ((ObjectNode) observations.get(1)).put("observedAt", "2026-09-30T00:00:00+08:00");
        PublicHealthTestFixture.observation(observations, "conflict-a", "conflicting-code", "synthetic-unit", 1);
        PublicHealthTestFixture.observation(observations, "conflict-b", "conflicting-code", "synthetic-unit", 2);
        PublicHealthRequest request = parse(input);
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(PublicHealthTestFixture.policy(), null, PublicHealthTestFixture.versions()));
        List<Consumer<ObjectNode>> mutations = Arrays.asList(
                check -> check.putNull("evidenceRefs"),
                check -> check.put("evidenceRefs", "patient:ageYears"),
                check -> check.putArray("evidenceRefs"),
                check -> check.putArray("evidenceRefs").add(1),
                check -> check.putArray("evidenceRefs").add(" "),
                check -> check.putArray("evidenceRefs").add("patient:ageYears").add("patient:ageYears"),
                check -> check.putArray("evidenceRefs").add("condition:0"),
                check -> check.putArray("evidenceRefs").add("observation:future"),
                check -> check.putArray("evidenceRefs").add("observation:conflict-a"),
                check -> check.putArray("evidenceRefs").add("observation:evidence"),
                check -> check.withArray("evidenceObservationIds").removeAll(),
                check -> check.put("extra", true),
                check -> check.remove("evidenceObservationIds"),
                check -> check.put("reason", 1),
                check -> check.put("reason", " "),
                check -> check.put("priority", 1),
                check -> check.put("intervalKind", true),
                check -> check.put("plannedDate", "2026-2-1"),
                check -> check.put("plannedDate", "2026-02-30"),
                check -> check.put("code", 1),
                check -> check.putArray("evidenceObservationIds").add(1),
                check -> check.putArray("evidenceObservationIds").add("evidence").add("evidence"),
                check -> check.putArray("evidenceRefs").add(String.join("", java.util.Collections.nCopies(257, "x")))
        );
        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode raw = checkRaw("evidence"); mutation.accept((ObjectNode) raw.path("checks").get(0));
            when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(raw);
            clearInvocations(model);
            PublicHealthOrchestrator.Listener listener = mock(PublicHealthOrchestrator.Listener.class);
            assertEquals("INVALID_MODEL_OUTPUT", assertThrows(PublicHealthException.class,
                    () -> service.generate(PublicHealthTestFixture.device(), request, "generation", service.prepare(PublicHealthTestFixture.device(), request), listener)).getCode());
            verify(model, times(1)).generate(any(), any(), any(), anyString(), anyString(), anyString());
            verify(listener, never()).section(any());
        }
    }

    @Test void enforcesCheckReferenceCountsAndUnicodeReasonLengthFromSchema() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        for (int i = 0; i < 65; i++) PublicHealthTestFixture.observation(observations, "obs-" + i, "code-" + i, "synthetic-unit", i);
        ArrayNode conditions = (ArrayNode) input.path("facts").path("conditions");
        for (int i = 0; i < 16; i++) conditions.addObject().put("code", "condition-" + i).put("name", "合成病种" + i).put("status", "合成随访");
        PublicHealthRequest request = parse(input);
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(PublicHealthTestFixture.policy(), null, PublicHealthTestFixture.versions()));
        ObjectNode raw = checkRaw("obs-0"); ObjectNode check = (ObjectNode) raw.path("checks").get(0);
        String reason = String.join("", java.util.Collections.nCopies(2000, "𠮷")); check.put("reason", reason);
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(raw);
        assertTrue(generateHealth(request).path("sections").get(0).path("content").asText().contains(reason));
        check.put("reason", reason + "𠮷");
        assertThrows(PublicHealthException.class, () -> generateHealth(request));
        check.put("reason", PublicHealthTestFixture.NOTICE);
        ArrayNode refs = check.putArray("evidenceRefs");
        for (int i = 0; i < 16; i++) refs.add("condition:" + i);
        generateHealth(request);
        refs.add("patient:ageYears");
        assertThrows(PublicHealthException.class, () -> generateHealth(request));
        check.remove("evidenceRefs");
        ArrayNode ids = check.withArray("evidenceObservationIds").removeAll();
        for (int i = 0; i < 64; i++) ids.add("obs-" + i);
        generateHealth(request);
        ids.add("obs-64");
        assertThrows(PublicHealthException.class, () -> generateHealth(request));
    }

    private ObjectNode generateHealth(PublicHealthRequest request) {
        return service.generate(PublicHealthTestFixture.device(), request, "generation", service.prepare(PublicHealthTestFixture.device(), request), mock(PublicHealthOrchestrator.Listener.class));
    }

    @Test void weightUsesMetricsRecipesAndTwoPromptsWithoutMets() throws Exception {
        PublicHealthRequest r = PublicHealthTestFixture.parsed("weight_management").request;
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(null, PublicHealthTestFixture.recipes(), PublicHealthTestFixture.versions()));
        when(retrieval.retrieve(any(AiDevice.class), any(), anyString(), any())).thenReturn(PublicHealthTestFixture.recipes().withArray("documents"));
        ObjectNode out = service.generate(PublicHealthTestFixture.device(), r,"generation", service.prepare(PublicHealthTestFixture.device(), r),
        mock(PublicHealthOrchestrator.Listener.class));
        assertEquals(2, out.path("sections").size());
        assertEquals(2, out.path("promptVersions").size());
        assertEquals(1603.0, out.path("metrics").path("bmrKcalPerDay").asDouble());
        JsonNode meal = out.path("sections").get(1);
        assertEquals(3, meal.path("options").size());
        assertEquals(meal.path("options").get(0).path("content"), meal.path("content"));
        verify(retrieval).retrieve(any(AiDevice.class), any(), contains("1603"), any());
        verify(model, times(2)).generate(any(), any(), argThat(context -> !context.has("healthProfile") && !context.has("checkScheduleConstraints")), anyString(), anyString(), anyString());
    }

    @Test void sourceBoundMealChoicesPreserveModelOrderAndUseSourceTitles() throws Exception {
        PublicHealthRequest r = PublicHealthTestFixture.parsed("weight_management").request;
        ObjectNode recipes = PublicHealthTestFixture.recipes();
        ArrayNode references = recipes.withArray("documents");
        ((ObjectNode)references.get(2)).put("title", "同一来源文件名.txt").put("text", "合成来源标题2\n" + PublicHealthTestFixture.NOTICE);
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(null, recipes, PublicHealthTestFixture.versions()));
        when(retrieval.retrieve(any(AiDevice.class), any(), anyString(), any())).thenReturn(references);
        ObjectNode raw = mealRaw(references);
        ArrayNode options = (ArrayNode)raw.path("options");
        JsonNode first = options.get(0); options.set(0, options.get(2)); options.set(2, first);
        when(model.generate(any(), any(), any(), anyString(), eq("meal_plan"), anyString())).thenReturn(raw);
        ObjectNode result = service.generate(PublicHealthTestFixture.device(), r, "generation", service.prepare(PublicHealthTestFixture.device(), r), mock(PublicHealthOrchestrator.Listener.class));
        JsonNode meal = result.path("sections").get(1);
        assertEquals("CHUNK-2", meal.path("options").get(0).path("id").asText());
        assertEquals("合成来源标题2", meal.path("options").get(0).path("title").asText());
        assertEquals(raw.path("options").get(0).path("content"), meal.path("content"));
        verify(model).generate(any(), any(), any(), anyString(), eq("meal_plan"), contains("首项为默认推荐"));
    }

    @Test void insufficientSourcesKeepOneMealWithoutInventingAlternatives() throws Exception {
        PublicHealthRequest r = PublicHealthTestFixture.parsed("weight_management").request;
        ObjectNode recipes = PublicHealthTestFixture.recipes();
        ArrayNode references = recipes.withArray("documents"); references.remove(3); references.remove(2);
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(null, recipes, PublicHealthTestFixture.versions()));
        when(retrieval.retrieve(any(AiDevice.class), any(), anyString(), any())).thenReturn(references);
        JsonNode meal = service.generate(PublicHealthTestFixture.device(), r, "generation", service.prepare(PublicHealthTestFixture.device(), r), mock(PublicHealthOrchestrator.Listener.class)).path("sections").get(1);
        assertFalse(meal.has("options")); assertEquals(PublicHealthTestFixture.NOTICE, meal.path("content").asText());
    }

    @Test void rejectsMalformedMealOptionsBeforeEmittingMealWithoutRetry() throws Exception {
        PublicHealthRequest r = PublicHealthTestFixture.parsed("weight_management").request;
        ObjectNode recipes = PublicHealthTestFixture.recipes(); ArrayNode references = recipes.withArray("documents");
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(null, recipes, PublicHealthTestFixture.versions()));
        when(retrieval.retrieve(any(AiDevice.class), any(), anyString(), any())).thenReturn(references);
        List<Consumer<ObjectNode>> mutations = Arrays.asList(
            raw -> ((ArrayNode)raw.path("options")).remove(2),
            raw -> ((ObjectNode)raw.path("options").get(1)).put("recipeId", "CHUNK-0"),
            raw -> ((ObjectNode)raw.path("options").get(2)).put("recipeId", "outside-source"),
            raw -> ((ObjectNode)raw.path("options").get(1)).put("content", " "),
            raw -> ((ObjectNode)raw.path("options").get(1)).put("content", String.join("", java.util.Collections.nCopies(16001, "𠮷"))),
            raw -> ((ObjectNode)raw.path("options").get(2)).put("extra", true),
            raw -> ((ObjectNode)raw.path("options").get(1)).put("recipeId", 2),
            raw -> raw.put("extra", true)
        );
        for (Consumer<ObjectNode> mutate:mutations) {
            ObjectNode raw = mealRaw(references); mutate.accept(raw);
            when(model.generate(any(), any(), any(), anyString(), eq("meal_plan"), anyString())).thenReturn(raw);
            clearInvocations(model);
            PublicHealthOrchestrator.Listener listener = mock(PublicHealthOrchestrator.Listener.class);
            assertThrows(PublicHealthException.class, () -> service.generate(PublicHealthTestFixture.device(), r, "generation", service.prepare(PublicHealthTestFixture.device(), r), listener));
            verify(model, times(2)).generate(any(), any(), any(), anyString(), anyString(), anyString());
            verify(listener, times(1)).section(any());
        }
    }

    @Test void invalidOutputAbortsRemainingNodesWithoutRetry() throws Exception {
        PublicHealthRequest r = PublicHealthTestFixture.parsed("health_prescription").request;
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(PublicHealthTestFixture.policy(), null, PublicHealthTestFixture.versions()));
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(PublicHealthTestFixture.M.createObjectNode().put("unknown",
        "bad"));
        assertThrows(PublicHealthException.class, () -> service.generate(PublicHealthTestFixture.device(), r,"generation", service.prepare(PublicHealthTestFixture.device(),
        r), mock(PublicHealthOrchestrator.Listener.class)));
        verify(model, times(1)).generate(any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test void rejectsOverlongPromptVersionMetadataBeforeModelCalls() throws Exception {
        PublicHealthRequest request = PublicHealthTestFixture.parsed("health_prescription").request;
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(
                PublicHealthTestFixture.policy(), null, PublicHealthTestFixture.versions()));
        String overlong = String.join("", java.util.Collections.nCopies(129, "x"));
        PromptView prompt = new PromptView();
        prompt.setCdPrompt("publicHealthHealthPrescription");
        prompt.setSysPrompt(PublicHealthTestFixture.NOTICE);
        prompt.setVersionNum(overlong);
        when(prompts.resolveEffectivePrompt(anyString(), anyString(), any())).thenReturn(prompt);
        assertEquals("POLICY_NOT_READY", assertThrows(PublicHealthException.class,
                () -> service.prepare(PublicHealthTestFixture.device(), request)).getCode());
        prompt.setVersionNum("synthetic-v1");
        prompt.setCdPrompt(overlong);
        assertEquals("POLICY_NOT_READY", assertThrows(PublicHealthException.class,
                () -> service.prepare(PublicHealthTestFixture.device(), request)).getCode());
        verifyNoInteractions(model);
    }

    @Test void abnormalRetestRequiresControlledEvidenceAndPreservesReasonPriority() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        PublicHealthTestFixture.observation((ArrayNode)input.path("facts").path("observations"),"SYNTHETIC-E","SYNTHETIC-CHECK","synthetic-unit",
        10);
        PublicHealthRequest r = new PublicHealthValidator(PublicHealthTestFixture.M).parse(PublicHealthTestFixture.M.writeValueAsBytes(input)).request;
        ObjectNode policy = PublicHealthTestFixture.policy();
        ((ObjectNode)policy.path("checkWhitelist").get(0)).putArray("abnormalCriteria").addObject().put("observationCode","SYNTHETIC-CHECK").put("operator",
        "gt").put("threshold", 5).put("maxEvidenceAgeDays", 7).put("unit", "synthetic-unit");
        when(assets.load(any(), anyString())).thenReturn(new PublicHealthAssetService.Pack(policy, null, PublicHealthTestFixture.versions()));
        ObjectNode raw = PublicHealthTestFixture.M.createObjectNode();
        ObjectNode check = raw.putArray("checks").addObject();
        check.put("code","SYNTHETIC-CHECK");
        check.put("plannedDate","2026-10-05");
        check.put("priority","high");
        check.put("reason", PublicHealthTestFixture.NOTICE);
        check.put("intervalKind","abnormal_retest");
        check.putArray("evidenceObservationIds").add("SYNTHETIC-E");
        when(model.generate(any(), any(), any(), anyString(), eq("check_plan"), anyString())).thenReturn(raw);
        ObjectNode out = service.generate(PublicHealthTestFixture.device(), r,"generation", service.prepare(PublicHealthTestFixture.device(), r),
        mock(PublicHealthOrchestrator.Listener.class));
        String text = out.path("sections").get(0).path("content").asText();
        assertTrue(text.contains("优先级：高"));
        assertTrue(text.contains("SYNTHETIC-CHECK：10 synthetic-unit"));
        assertTrue(text.contains(PublicHealthTestFixture.NOTICE));
        ((ObjectNode)policy.path("checkWhitelist").get(0).path("abnormalCriteria").get(0))
                .put("unit", "different-unit");
        assertEquals("POLICY_NOT_READY", assertThrows(PublicHealthException.class,
                () -> service.generate(PublicHealthTestFixture.device(), r, "generation",
                        service.prepare(PublicHealthTestFixture.device(), r),
                        mock(PublicHealthOrchestrator.Listener.class))).getCode());
        ((ObjectNode)policy.path("checkWhitelist").get(0)).remove("abnormalCriteria");
        assertEquals("POLICY_NOT_READY", assertThrows(PublicHealthException.class, () -> service.generate(PublicHealthTestFixture.device(), r,"generation",
        service.prepare(PublicHealthTestFixture.device(), r), mock(PublicHealthOrchestrator.Listener.class))).getCode());
    }
}
