package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import org.jsoup.nodes.Element;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** Identifies field evidence and validates AI findings; it does not classify clinical allergy text. */
final class EmrQcAllergyRules {
    static final String MISSING = "R-ALLERGY-MISSING";
    static final String INCOMPLETE = "R-ALLERGY-INCOMPLETE";
    static final String FIELD_LABEL = "(?:(?:食[物品]|药[物品])(?:[、，,/／及与和\\s]*(?:食[物品]|药[物品]))?)?过敏史";

    private EmrQcAllergyRules() { }

    static boolean isLabel(String label) {
        return label != null && label.matches(FIELD_LABEL);
    }

    static boolean excludedSection(String name) {
        return "家族史".equals(name) || "个人史".equals(name);
    }

    static boolean excludedPreview(Element block) {
        for (Element element = block; element != null; element = element.parent()) {
            if (excludedSection(element.attr("data-emr-section"))) return true;
        }
        return false;
    }

    static boolean isRule(String code) {
        return MISSING.equals(code) || INCOMPLETE.equals(code);
    }

    static boolean validFinding(String code, String dimension, int deduction) {
        return EmrQcRubric.PATIENT_INFO.equals(dimension)
            && (MISSING.equals(code) && deduction == 5 || INCOMPLETE.equals(code) && deduction == 1);
    }

    /** null=unknown, false=explicitly blank, true=filled; no clinical inference is made. */
    static Boolean filled(String value) {
        return value == null ? null : StringUtils.hasText(value.replace('\u00a0', ' '));
    }

    static String aiEvidence(String value, PhisEmrRecord record, SensitiveDataMasker masker) {
        Boolean filled = filled(value);
        String state = filled == null ? "未识别（未知，不代表未填写）" : filled ? "已填写" : "明确为空";
        String text = value == null ? "" : masker.maskText(value, record.getPatientName(), record.getIdCard(), record.getIdArchives());
        return "【过敏史固定字段证据】\n字段状态：" + state + "\n填写内容：" + text + "\n";
    }

    static List<EmrQcAnalysisVO.Finding> withEvidence(List<EmrQcAnalysisVO.Finding> source, Boolean filled) {
        List<EmrQcAnalysisVO.Finding> result = new ArrayList<EmrQcAnalysisVO.Finding>();
        for (EmrQcAnalysisVO.Finding finding : source) {
            if (isRule(finding.getRuleCode())) {
                // A model cannot turn an unrecognized field or another history into this rule.
                if (filled == null) continue;
                if (MISSING.equals(finding.getRuleCode()) == filled) {
                    throw new ServiceUnavailableException("EMR-QC-UNAVAILABLE", "AI 过敏史问题与字段填写状态不一致，请重新质控");
                }
            }
            result.add(finding);
        }
        return result;
    }
}
