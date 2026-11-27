package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import org.jsoup.nodes.Element;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Identifies field evidence and validates AI findings; it does not classify clinical allergy text. */
final class EmrQcAllergyRules {
    static final String MISSING = "R-ALLERGY-MISSING";
    static final String INCOMPLETE = "R-ALLERGY-INCOMPLETE";
    static final String FIELD_LABEL = "(?:(?:食[物品]|药[物品])(?:[、，,/／及与和\\s]*(?:食[物品]|药[物品]))?)?过敏史";
    private static final Pattern STRUCTURED_LABEL = Pattern.compile("(?:有无|既往)?" + FIELD_LABEL + "(?:标志|情况)?");
    private static final Pattern GENERIC_PREFIX = Pattern.compile(
        "^(?:有无|既往)?(?:(?:食品[、，,/／及与和\\s]*药品|药品[、，,/／及与和\\s]*食品|药物[、，,/／及与和\\s]*食物|食物[、，,/／及与和\\s]*药物)?过敏史)(?:标志|情况)?\\s*[：:]\\s*(.*)$");
    private static final Pattern UNRELATED_PAST_HISTORY = Pattern.compile(
        "^(?:否认|无|有|曾有|既往)?[^，,]*(?:体健|肝炎|结核|疟疾|传染病|心脏病|脑血管|肺部|肾脏|重大疾病|高血压|糖尿病|冠心病|手术|外伤|输血)[^，,]*$");

    private EmrQcAllergyRules() { }

    static boolean isLabel(String label) {
        return label != null && label.matches(FIELD_LABEL);
    }

    static boolean isStructuredLabel(String label) {
        return label != null && STRUCTURED_LABEL.matcher(label).matches();
    }

    /** Extract explicit allergy statements embedded in past history when no colon-labelled field exists. */
    static String fromPastHistory(String pastHistory) {
        if (!StringUtils.hasText(pastHistory)) return null;
        Set<String> statements = new LinkedHashSet<String>();
        boolean foundEmptyLabel = false;
        for (String rawClause : pastHistory.split("[；;。\\r\\n]+")) {
            String clause = rawClause.trim();
            if (!clause.contains("过敏")) continue;
            String extracted = extractAllergyClause(clause);
            if (extracted == null) continue;
            if (extracted.isEmpty()) foundEmptyLabel = true;
            else statements.add(extracted);
        }
        if (!statements.isEmpty()) return String.join("；", statements);
        return foundEmptyLabel ? "" : null;
    }

    private static String extractAllergyClause(String clause) {
        String[] parts = clause.split("[，,]");
        int first = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].contains("过敏")) {
                first = i;
                break;
            }
        }
        if (first < 0) return null;
        List<String> kept = new ArrayList<String>();
        for (int i = first; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;
            if (!part.contains("过敏") && isUnrelatedPastHistoryItem(part)) continue;
            kept.add(part);
        }
        String joined = String.join("，", kept).trim();
        Matcher generic = GENERIC_PREFIX.matcher(joined);
        if (generic.matches()) return generic.group(1).trim();
        return joined;
    }

    private static boolean isUnrelatedPastHistoryItem(String part) {
        return !part.contains("药物") && !part.contains("药品") && !part.contains("食物") && !part.contains("食品")
            && UNRELATED_PAST_HISTORY.matcher(part).matches();
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
