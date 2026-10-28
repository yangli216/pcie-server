package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门诊病历质控评分表：维度得分为 0 至该维度满分，总分为各维度得分之和。
 */
public final class EmrQcRubric {

    public static final String PATIENT_INFO = "patient_info";
    public static final String CHIEF_COMPLAINT = "chief_complaint";
    public static final String PRESENT_ILLNESS = "present_illness";
    public static final String PAST_HISTORY = "past_history";
    public static final String PHYSICAL_EXAM = "physical_exam";
    public static final String DIAGNOSIS = "diagnosis";
    public static final String TREATMENT = "treatment";
    public static final String WRITING = "writing";
    public static final String OTHER = "other";

    private static final Map<String, Spec> SPECS = specs();

    private EmrQcRubric() {
    }

    public static List<Spec> dimensions() {
        return new ArrayList<Spec>(SPECS.values());
    }

    public static Spec spec(String code) {
        return SPECS.get(code);
    }

    public static Spec specByCategory(String category) {
        Spec spec = SPECS.get(category);
        if (spec != null) {
            return spec;
        }
        for (Spec candidate : SPECS.values()) {
            if (candidate.name.equals(category)) {
                return candidate;
            }
        }
        return null;
    }

    public static String name(String code) {
        Spec spec = SPECS.get(code);
        return spec == null ? null : spec.name;
    }

    public static int totalWeight() {
        int total = 0;
        for (Spec spec : SPECS.values()) {
            total += spec.weight;
        }
        return total;
    }

    /** Calculate scores from the complete active finding list, capped at each dimension's weight. */
    public static void scoreFromFindings(List<EmrQcAnalysisVO.Dimension> dimensions,
                                         List<EmrQcAnalysisVO.Finding> findings) {
        for (EmrQcAnalysisVO.Dimension dimension : dimensions) {
            Spec spec = spec(dimension.getCode());
            long deducted = 0;
            for (EmrQcAnalysisVO.Finding finding : findings) {
                if (!Boolean.TRUE.equals(finding.getIgnored()) && specByCategory(finding.getCategory()) == spec) {
                    deducted += finding.getDeduction() == null ? 0 : finding.getDeduction();
                }
            }
            dimension.setScore((int) Math.max(0, spec.weight - Math.min(spec.weight, deducted)));
        }
    }

    public static EmrQcAnalysisVO.Dimension dimension(String code, Integer score, String summary) {
        Spec spec = SPECS.get(code);
        EmrQcAnalysisVO.Dimension dimension = new EmrQcAnalysisVO.Dimension();
        dimension.setCode(code);
        dimension.setName(spec.name);
        dimension.setWeight(spec.weight);
        dimension.setScore(score);
        dimension.setSummary(summary);
        return dimension;
    }

    private static Map<String, Spec> specs() {
        Map<String, Spec> specs = new LinkedHashMap<String, Spec>();
        specs.put(PATIENT_INFO, new Spec(PATIENT_INFO, "患者基础信息", 10));
        specs.put(CHIEF_COMPLAINT, new Spec(CHIEF_COMPLAINT, "主诉", 5));
        specs.put(PRESENT_ILLNESS, new Spec(PRESENT_ILLNESS, "现病史", 20));
        specs.put(PAST_HISTORY, new Spec(PAST_HISTORY, "既往史", 10));
        specs.put(PHYSICAL_EXAM, new Spec(PHYSICAL_EXAM, "查体与辅助检查", 15));
        specs.put(DIAGNOSIS, new Spec(DIAGNOSIS, "诊断", 10));
        specs.put(TREATMENT, new Spec(TREATMENT, "诊疗措施", 20));
        specs.put(WRITING, new Spec(WRITING, "病历书写基本要求", 5));
        specs.put(OTHER, new Spec(OTHER, "其他", 5));
        return Collections.unmodifiableMap(specs);
    }

    public static class Spec {
        private final String code;
        private final String name;
        private final int weight;

        private Spec(String code, String name, int weight) {
            this.code = code;
            this.name = name;
            this.weight = weight;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public int getWeight() {
            return weight;
        }
    }
}
