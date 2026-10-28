package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmrQcRuleEngineTest {

    private final EmrQcRuleEngine engine = new EmrQcRuleEngine();

    @Test
    void mapsFindingsToRubricDimensionsAndSpecificDeductions() {
        List<PhisEmrSection> sections = completeSections();
        section(sections, "主诉", "咳嗽");
        section(sections, "现病史", "咳嗽");
        section(sections, "处理", "口服药");
        section(sections, "签名信息", "病历有修改");

        Map<String, EmrQcAnalysisVO.Finding> findings = byRuleCode(engine.evaluate(sections));

        assertEquals("患者基础信息", findings.get("R-ALLERGY").getCategory());
        assertEquals("主诉", findings.get("R-COMPLAINT-DURATION").getCategory());
        assertEquals("现病史", findings.get("R-PRESENT-ILLNESS").getCategory());
        assertEquals("诊疗措施", findings.get("R-FOLLOW-UP").getCategory());
        assertEquals("病历书写基本要求", findings.get("R-SIGNATURE").getCategory());
        assertEquals("病历书写基本要求", findings.get("R-MODIFY-TIME").getCategory());
        assertEquals(0, findings.get("R-ALLERGY").getDeduction());
        assertEquals(0, findings.get("R-COMPLAINT-DURATION").getDeduction());
        assertEquals(0, findings.get("R-PRESENT-ILLNESS").getDeduction());
        assertEquals(0, findings.get("R-FOLLOW-UP").getDeduction());
        assertEquals(0, findings.get("R-SIGNATURE").getDeduction());
        assertEquals(0, findings.get("R-MODIFY-TIME").getDeduction());
        assertTrue(findings.values().stream().allMatch(finding -> !finding.getIgnored()));
    }

    @Test
    void completeContentDoesNotProduceOptionalFindings() {
        List<PhisEmrSection> sections = completeSections();
        section(sections, "患者基础信息", "否认食物及药物过敏史");
        section(sections, "主诉", "咳嗽3天");
        section(sections, "现病史", "三天前起病，咳嗽逐渐加重，门诊治疗后症状有所缓解");
        section(sections, "处理", "口服药，三日后复诊");
        section(sections, "签名信息", "医师签名：张三");

        assertTrue(engine.evaluate(sections).isEmpty());
    }

    @Test
    void missingSectionsUseRubricCategoriesAndMissingSignatureIsRejected() {
        List<EmrQcAnalysisVO.Finding> evaluated = engine.evaluate(new ArrayList<PhisEmrSection>());
        Map<String, EmrQcAnalysisVO.Finding> findings = byRuleCode(evaluated);

        assertEquals("主诉", findings.get("R-REQUIRED-SECTION").getCategory());
        assertEquals(6, evaluated.stream()
            .filter(finding -> "R-REQUIRED-SECTION".equals(finding.getRuleCode())).count());
        assertTrue(findings.containsKey("R-ALLERGY"));
        assertTrue(findings.containsKey("R-SIGNATURE"));
        assertFalse(findings.containsKey("R-COMPLAINT-DURATION"));
    }

    @Test
    void emergencyVisitTimeAndMissingVitalSignsUseOtherDimensionDeductions() {
        List<PhisEmrSection> sections = completeSections();
        section(sections, "患者基础信息", "姓名：张三；否认食物及药物过敏史；就诊时间：2026-10-26 09时");

        Map<String, EmrQcAnalysisVO.Finding> findings = byRuleCode(
            engine.evaluate(sections, "急诊科", "急诊病历"));

        EmrQcAnalysisVO.Finding visitTime = findings.get("R-EMERGENCY-VISIT-TIME");
        EmrQcAnalysisVO.Finding vitalSigns = findings.get("R-EMERGENCY-VITAL-SIGNS");
        assertEquals("其他", visitTime.getCategory());
        assertEquals(2, visitTime.getDeduction());
        assertEquals(Boolean.FALSE, visitTime.getIgnored());
        assertEquals("其他", vitalSigns.getCategory());
        assertEquals(3, vitalSigns.getDeduction());
        assertEquals(Boolean.FALSE, vitalSigns.getIgnored());
    }

    @Test
    void emergencyContentWithMinuteAndVitalSignsDoesNotProduceEmergencyFindings() {
        List<PhisEmrSection> sections = completeSections();
        section(sections, "患者基础信息", "姓名：张三；否认食物及药物过敏史；就诊时间：2026-10-26 09:05");
        section(sections, "体格检查", "T：36.8，P：80，R：18，BP：120/80");

        List<EmrQcAnalysisVO.Finding> findings = engine.evaluate(sections, "急诊科", "急诊病历");

        assertTrue(findings.stream().noneMatch(finding -> finding.getRuleCode().startsWith("R-EMERGENCY-")));
    }

    @Test
    void ordinaryOutpatientAndEmergencyDiagnosisSectionDoNotTriggerEmergencyRules() {
        List<PhisEmrSection> sections = completeSections();
        section(sections, "患者基础信息", "姓名：张三；否认食物及药物过敏史；就诊时间：2026-10-26 09时");

        List<EmrQcAnalysisVO.Finding> findings = engine.evaluate(sections, "门诊内科", "门诊病历");

        assertTrue(findings.isEmpty());
    }

    private List<PhisEmrSection> completeSections() {
        List<PhisEmrSection> sections = new ArrayList<PhisEmrSection>();
        section(sections, "患者基础信息", "姓名：张三");
        section(sections, "主诉", "咳嗽3天");
        section(sections, "现病史", "三天前起病，咳嗽逐渐加重，门诊治疗后症状有所缓解");
        section(sections, "既往史", "否认重要既往病史");
        section(sections, "体格检查", "咽部充血，双肺呼吸音清");
        section(sections, "门急诊诊断", "急性上呼吸道感染");
        section(sections, "处理", "口服药，三日后复诊");
        section(sections, "签名信息", "医师签名：张三");
        return sections;
    }

    private void section(List<PhisEmrSection> sections, String name, String value) {
        for (PhisEmrSection existing : sections) {
            if (name.equals(existing.getName())) {
                existing.getFields().get(0).setValue(value);
                return;
            }
        }
        PhisEmrSection section = new PhisEmrSection();
        section.setName(name);
        PhisEmrSection.Field field = new PhisEmrSection.Field();
        field.setName("内容");
        field.setValue(value);
        section.getFields().add(field);
        sections.add(section);
    }

    private Map<String, EmrQcAnalysisVO.Finding> byRuleCode(List<EmrQcAnalysisVO.Finding> findings) {
        return findings.stream().collect(Collectors.toMap(
            EmrQcAnalysisVO.Finding::getRuleCode, Function.identity(),
            (first, second) -> first));
    }
}
