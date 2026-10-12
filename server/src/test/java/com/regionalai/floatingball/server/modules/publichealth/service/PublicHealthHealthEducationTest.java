package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PublicHealthHealthEducationTest {
    JsonNode profile;

    @BeforeEach void setup() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        PublicHealthTestFixture.observation((ArrayNode) input.path("facts").path("observations"), "SYNTHETIC-E", "SYNTHETIC-RESULT", "synthetic-unit", 10);
        profile = PublicHealthHealthProfile.build(new PublicHealthValidator(PublicHealthTestFixture.M)
                .parse(PublicHealthTestFixture.M.writeValueAsBytes(input)).request, PublicHealthTestFixture.M);
    }

    private ObjectNode valid() {
        ObjectNode draft = PublicHealthTestFixture.healthEducation();
        ObjectNode risk = draft.withArray("risks").addObject();
        risk.put("type", "progression").put("name", "合成进展关注").put("concern", "needs_assessment").put("intervention", PublicHealthTestFixture.NOTICE);
        risk.putArray("evidenceRefs").add("observation:SYNTHETIC-E").add("patient:ageYears");
        for (String field : Arrays.asList("prevention", "exercise", "followup", "missingFacts")) draft.withArray(field).add(PublicHealthTestFixture.NOTICE);
        return draft;
    }

    @Test void rendersFixedDoctorSectionsAndCitationsFromProfileNotModel() {
        ObjectNode draft = valid();
        draft.withArray("risks").add(draft.path("risks").get(0).deepCopy());
        ((ObjectNode) draft.path("risks").get(1)).put("type", "complication").put("concern", "attention").put("name", "合成并发症关注");
        String text = PublicHealthHealthEducation.render(draft, profile);
        for (String expected : Arrays.asList("AI 定性分析", "不表示概率或诊断", "慢病进展；待评估", "并发症；关注", "SYNTHETIC-RESULT", "10 synthetic-unit", "2026-09-28T09:00:00+08:00", "SYNTHETIC-FACT", "疾病预防", "饮食建议", "运动建议", "随访安排", "待补充资料")) assertTrue(text.contains(expected), expected);
        assertEquals(text, PublicHealthHealthEducation.render(draft, profile));
        assertFalse(text.contains("制定依据"));
    }

    @Test void permitsEmptyArraysWithoutMakingUpRecommendationsOrProbability() {
        ObjectNode draft = PublicHealthTestFixture.healthEducation();
        draft.withArray("diet").removeAll();
        assertEquals("本次资料不足以生成健康教育条目，请医生核对并补充资料。", PublicHealthHealthEducation.render(draft, profile));
    }

    @Test void emptyRiskListWithOtherAdviceDoesNotImplyNoRisk() {
        String text = PublicHealthHealthEducation.render(PublicHealthTestFixture.healthEducation(), profile);
        assertTrue(text.startsWith("潜在风险提示（AI 定性分析，待医生审核；不表示概率或诊断）"));
        assertTrue(text.contains("不能据此判断无风险"));
        assertTrue(text.contains("饮食建议"));
        assertFalse(text.contains("疾病预防"));
    }

    @Test void rejectsCrossCapabilityAndMetricTokensInEveryGeneratedModelTextField() {
        List<String> tokens = Arrays.asList("prefixbMipostfix", "BmR", "1 KcAl", "130 mMhG", "130 MM \tHg",
                "130 mm\nHg", "130 mm\u3000Hg", "6 mMoL / l", "6 MMOL ／ L", "6 mmol\t/\nL", "减重目标", "目标体重",
                "三餐参考食谱", "备选食谱", "千卡", "卡路里");
        for (String field : Arrays.asList("name", "intervention", "prevention", "diet", "exercise", "followup", "missingFacts")) {
            for (String token : tokens) {
                ObjectNode draft = valid();
                String generated = "合成文本包含" + token + "的条目";
                if (Arrays.asList("name", "intervention").contains(field)) ((ObjectNode) draft.path("risks").get(0)).put(field, generated);
                else draft.withArray(field).removeAll().add(generated);
                assertEquals("INVALID_MODEL_OUTPUT", assertThrows(PublicHealthException.class,
                        () -> PublicHealthHealthEducation.render(draft, profile), field + " / " + token).getCode());
            }
        }
    }

    @Test void keepsPassedMetricUnitsInProgramEvidenceAndDoesNotScanReferenceIdentifiers() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "SYNTHETIC-BMI", "systolic_bp", "mmHg", 138);
        PublicHealthTestFixture.observation(observations, "SYNTHETIC-BMR", "fasting_glucose", "mmol/L", 6);
        ((ObjectNode) observations.get(1)).put("value", 6.2);
        JsonNode metricProfile = PublicHealthHealthProfile.build(new PublicHealthValidator(PublicHealthTestFixture.M)
                .parse(PublicHealthTestFixture.M.writeValueAsBytes(input)).request, PublicHealthTestFixture.M);
        ObjectNode draft = valid();
        ((ObjectNode) draft.path("risks").get(0)).putArray("evidenceRefs").add("observation:SYNTHETIC-BMI").add("observation:SYNTHETIC-BMR");
        String text = PublicHealthHealthEducation.render(draft, metricProfile);
        assertTrue(text.contains("138 mmHg"));
        assertTrue(text.contains("6.2 mmol/L"));
        assertTrue(text.contains("来源：SYNTHETIC / 记录：SYNTHETIC-FACT"));
        assertFalse(text.contains("observation:SYNTHETIC-BMI"));
        assertFalse(text.contains("observation:SYNTHETIC-BMR"));
    }

    @Test void permitsActionableDurationsAndFrequenciesWithoutBroaderMedicalTextFiltering() {
        ObjectNode draft = valid();
        String exercise = PublicHealthTestFixture.NOTICE + "：平地步行每周3次，每次20分钟，安排由医生核定。";
        String diet = PublicHealthTestFixture.NOTICE + "：清淡饮食并结合既有随访管理计划核对。";
        draft.withArray("exercise").removeAll().add(exercise);
        draft.withArray("diet").removeAll().add(diet);
        String text = PublicHealthHealthEducation.render(draft, profile);
        assertTrue(text.contains(exercise));
        assertTrue(text.contains(diet));
    }

    @Test void rejectsUnknownFieldsTypesProbabilityFieldsAndUnboundOrDuplicatedEvidence() {
        List<Consumer<ObjectNode>> mutations = Arrays.asList(
                draft -> draft.remove("exercise"),
                draft -> draft.put("probability", 0.2),
                draft -> draft.putNull("prevention"),
                draft -> draft.putObject("diet"),
                draft -> ((ObjectNode) draft.path("risks").get(0)).put("probability", 0.2),
                draft -> ((ObjectNode) draft.path("risks").get(0)).put("type", "diagnosis"),
                draft -> ((ObjectNode) draft.path("risks").get(0)).put("concern", "high"),
                draft -> ((ObjectNode) draft.path("risks").get(0)).put("name", " "),
                draft -> ((ObjectNode) draft.path("risks").get(0)).put("intervention", 1),
                draft -> ((ObjectNode) draft.path("risks").get(0)).putArray("evidenceRefs"),
                draft -> ((ArrayNode) draft.path("risks").get(0).path("evidenceRefs")).add("invented:ref"),
                draft -> ((ArrayNode) draft.path("risks").get(0).path("evidenceRefs")).add("observation:SYNTHETIC-E"),
                draft -> ((ArrayNode) draft.path("risks").get(0).path("evidenceRefs")).add(1),
                draft -> draft.withArray("prevention").add(PublicHealthTestFixture.NOTICE),
                draft -> draft.withArray("diet").add("\u00a0"),
                draft -> draft.withArray("exercise").addObject().put("content", "unsupported")
        );
        for (Consumer<ObjectNode> mutate : mutations) {
            ObjectNode draft = valid(); mutate.accept(draft);
            assertEquals("INVALID_MODEL_OUTPUT", assertThrows(PublicHealthException.class,
                    () -> PublicHealthHealthEducation.render(draft, profile)).getCode());
        }
    }

    @Test void rejectsFutureAndConflictingObservationReferences() throws Exception {
        ObjectNode input = PublicHealthTestFixture.request("health_prescription");
        ArrayNode observations = (ArrayNode) input.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "future", "future-result", "synthetic-unit", 10);
        ((ObjectNode) observations.get(0)).put("observedAt", "2026-09-30T09:00:00+08:00");
        PublicHealthTestFixture.observation(observations, "conflict-a", "same-code", "synthetic-unit", 10);
        PublicHealthTestFixture.observation(observations, "conflict-b", "same-code", "synthetic-unit", 11);
        JsonNode untrusted = PublicHealthHealthProfile.build(new PublicHealthValidator(PublicHealthTestFixture.M)
                .parse(PublicHealthTestFixture.M.writeValueAsBytes(input)).request, PublicHealthTestFixture.M);
        for (String id : Arrays.asList("future", "conflict-a", "conflict-b")) {
            ObjectNode draft = valid();
            ((ObjectNode) draft.path("risks").get(0)).putArray("evidenceRefs").add("observation:" + id);
            assertThrows(PublicHealthException.class, () -> PublicHealthHealthEducation.render(draft, untrusted));
        }
    }

    @Test void enforcesArrayStringAndRenderedSectionLimitsInCodePoints() {
        List<Consumer<ObjectNode>> mutations = Arrays.asList(
                draft -> ((ObjectNode) draft.path("risks").get(0)).put("name", repeat("𠮷", 101)),
                draft -> ((ObjectNode) draft.path("risks").get(0)).put("intervention", repeat("𠮷", 1001)),
                draft -> draft.withArray("diet").add(repeat("𠮷", 1001)),
                draft -> { for (int i = 1; i < 7; i++) draft.withArray("risks").add(draft.path("risks").get(0).deepCopy()); },
                draft -> { for (int i = 1; i < 9; i++) draft.withArray("exercise").add("item-" + i); },
                draft -> { for (int i = 0; i < 15; i++) ((ArrayNode) draft.path("risks").get(0).path("evidenceRefs")).add("lifestyle:unknown-" + i); }
        );
        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode draft = valid(); mutation.accept(draft);
            assertThrows(PublicHealthException.class, () -> PublicHealthHealthEducation.render(draft, profile));
        }
        ObjectNode draft = PublicHealthTestFixture.healthEducation();
        draft.withArray("diet").removeAll().add(repeat("𠮷", 1000));
        assertTrue(PublicHealthHealthEducation.render(draft, profile).contains(repeat("𠮷", 1000)));
        for (String field : Arrays.asList("prevention", "diet", "exercise", "followup", "missingFacts")) {
            ArrayNode advice = draft.withArray(field).removeAll();
            for (int i = 0; i < 8; i++) advice.add(i + repeat("x", 999));
        }
        assertThrows(PublicHealthException.class, () -> PublicHealthHealthEducation.render(draft, profile));
    }

    private String repeat(String text, int count) { return String.join("", Collections.nCopies(count, text)); }
}
