package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrSection;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class EmrQcRuleEngine {

    public static final String RULE_VERSION = "emr-qc-rules-v4";

    private static final Pattern DURATION = Pattern.compile("\\d+\\s*(天|日|小时|周|月|年|分钟|min|h|d)");
    private static final Pattern FOLLOW_UP = Pattern.compile("复诊|随访|随诊");
    private static final Pattern VISIT_TIME_TO_MINUTE = Pattern.compile(
        "就诊时间\\s*[：:]?\\s*(?:\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?[T\\s]*)?"
            + "(?:[01]?\\d|2[0-3])\\s*[:：时]\\s*[0-5]\\d");
    private static final Pattern TEMPERATURE = Pattern.compile("(?i)(?:体温|(?<![A-Za-z])T)\\s*[:：]?\\s*\\d");
    private static final Pattern PULSE = Pattern.compile("(?i)(?:脉搏(?:频率)?|(?<![A-Za-z])P)\\s*[:：]?\\s*\\d");
    private static final Pattern RESPIRATION = Pattern.compile("(?i)(?:呼吸(?:频率)?|(?<![A-Za-z])R)\\s*[:：]?\\s*\\d");
    private static final Pattern BLOOD_PRESSURE = Pattern.compile("(?i)(?:血压|(?<![A-Za-z])BP)\\s*[:：]?\\s*\\d");
    private static final String[][] REQUIRED_SECTIONS = {
        {"主诉", EmrQcRubric.CHIEF_COMPLAINT},
        {"现病史", EmrQcRubric.PRESENT_ILLNESS},
        {"既往史", EmrQcRubric.PAST_HISTORY},
        {"体格检查", EmrQcRubric.PHYSICAL_EXAM},
        {"门急诊诊断", EmrQcRubric.DIAGNOSIS},
        {"处理", EmrQcRubric.TREATMENT}
    };

    public List<EmrQcAnalysisVO.Finding> evaluate(List<PhisEmrSection> sections) {
        return evaluate(sections, null, null);
    }

    public List<EmrQcAnalysisVO.Finding> evaluate(List<PhisEmrSection> sections,
                                                  String deptName,
                                                  String templateName) {
        Map<String, String> sectionTexts = new LinkedHashMap<String, String>();
        StringBuilder allText = new StringBuilder();
        for (PhisEmrSection section : sections) {
            StringBuilder builder = new StringBuilder();
            for (PhisEmrSection.Field field : section.getFields()) {
                builder.append(field.getName()).append('：').append(field.getValue()).append(' ');
            }
            if (StringUtils.hasText(section.getName())) {
                sectionTexts.put(section.getName().trim(), builder.toString().trim());
            }
            allText.append(builder).append('\n');
        }

        List<EmrQcAnalysisVO.Finding> findings = new ArrayList<EmrQcAnalysisVO.Finding>();
        for (String[] required : REQUIRED_SECTIONS) {
            String text = sectionTexts.get(required[0]);
            if (!StringUtils.hasText(text)) {
                findings.add(finding("high", required[1], "R-REQUIRED-SECTION", required[0],
                    "", "缺少「" + required[0] + "」章节内容。",
                    "补充「" + required[0] + "」记录后再提交质控。"));
            }
        }

        if (!allText.toString().contains("过敏")) {
            findings.add(finding("medium", EmrQcRubric.PATIENT_INFO, "R-ALLERGY", "患者基础信息",
                "", "未记录食物/药物过敏史。",
                "在病历首页补充食物/药物过敏史，无过敏也应明确记录“否认过敏史”。"));
        }

        String complaint = sectionTexts.get("主诉");
        if (StringUtils.hasText(complaint) && !DURATION.matcher(complaint).find()) {
            findings.add(finding("medium", EmrQcRubric.CHIEF_COMPLAINT, "R-COMPLAINT-DURATION", "主诉",
                truncate(complaint, 120), "主诉未描述症状持续时间。",
                "按「症状 + 持续时间」补全主诉，例如“反复咳嗽3天”。"));
        }

        String presentIllness = sectionTexts.get("现病史");
        if (StringUtils.hasText(presentIllness) && presentIllness.length() < 10) {
            findings.add(finding("medium", EmrQcRubric.PRESENT_ILLNESS, "R-PRESENT-ILLNESS", "现病史",
                truncate(presentIllness, 120), "现病史描述过于简略，缺少起病、演变与诊疗经过。",
                "补充起病时间、主要症状演变、伴随症状、既往诊疗与用药情况。"));
        }

        String treatment = sectionTexts.get("处理");
        if (StringUtils.hasText(treatment) && treatment.length() < 4) {
            findings.add(finding("low", EmrQcRubric.TREATMENT, "R-TREATMENT", "处理",
                truncate(treatment, 120), "处理意见过于简略。",
                "补充治疗措施、用药方案或随访建议。"));
        }
        if (StringUtils.hasText(treatment) && !FOLLOW_UP.matcher(treatment).find()) {
            findings.add(finding("low", EmrQcRubric.TREATMENT, "R-FOLLOW-UP", "处理",
                truncate(treatment, 120), "诊疗措施中缺少复诊建议。",
                "补充复诊时间、复诊条件或随访安排。"));
        }

        String signature = sectionTexts.get("签名信息");
        if (!StringUtils.hasText(signature) || !signature.contains("医师签名")) {
            findings.add(finding("high", EmrQcRubric.WRITING, "R-SIGNATURE", "签名信息",
                truncate(signature, 120), "病历缺少医师签名信息。",
                "完成医师签名后再归档病历。"));
        }
        if (StringUtils.hasText(signature) && signature.contains("修改") && !signature.contains("修改时间")) {
            findings.add(finding("low", EmrQcRubric.WRITING, "R-MODIFY-TIME", "签名信息",
                truncate(signature, 120), "病历修改缺少修改时间。",
                "补充病历修改时间及修改医师签名。"));
        }

        if (isEmergency(sections, deptName, templateName)) {
            String normalizedText = allText.toString();
            if (!VISIT_TIME_TO_MINUTE.matcher(normalizedText).find()) {
                findings.add(finding("medium", EmrQcRubric.OTHER, "R-EMERGENCY-VISIT-TIME", "患者基础信息",
                    truncate(sectionTexts.get("患者基础信息"), 120),
                    "急诊病历就诊时间未具体到分钟。",
                    "将急诊就诊时间补充到小时和分钟。"));
            }
            boolean hasVitalSigns = TEMPERATURE.matcher(normalizedText).find()
                || PULSE.matcher(normalizedText).find()
                || RESPIRATION.matcher(normalizedText).find()
                || BLOOD_PRESSURE.matcher(normalizedText).find();
            if (!hasVitalSigns) {
                findings.add(finding("high", EmrQcRubric.OTHER, "R-EMERGENCY-VITAL-SIGNS", "体格检查",
                    truncate(sectionTexts.get("体格检查"), 120),
                    "急诊病人无 T、P、R、BP 生命体征记录。",
                    "记录体温、脉搏、呼吸和血压等生命体征。"));
            }
        }

        return findings;
    }

    private boolean isEmergency(List<PhisEmrSection> sections, String deptName, String templateName) {
        if (containsEmergency(deptName) || containsEmergency(templateName)) {
            return true;
        }
        for (PhisEmrSection section : sections) {
            if (containsEmergency(section.getName())) {
                return true;
            }
            for (PhisEmrSection.Field field : section.getFields()) {
                String fieldName = field.getName() == null ? "" : field.getName();
                boolean visitScope = fieldName.contains("科室") || fieldName.contains("科别")
                    || fieldName.contains("就诊类型") || fieldName.contains("病历类型");
                if (visitScope && containsEmergency(field.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsEmergency(String value) {
        return StringUtils.hasText(value) && value.replace("门急诊", "").contains("急诊");
    }

    private EmrQcAnalysisVO.Finding finding(String severity,
                                            String dimensionCode,
                                            String ruleCode,
                                            String sectionName,
                                            String excerpt,
                                            String issue,
                                            String suggestion) {
        EmrQcAnalysisVO.Finding finding = new EmrQcAnalysisVO.Finding();
        finding.setSeverity(severity);
        finding.setCategory(EmrQcRubric.name(dimensionCode));
        finding.setRuleCode(ruleCode);
        finding.setSectionName(sectionName);
        finding.setExcerpt(excerpt);
        finding.setIssue(issue);
        finding.setSuggestion(suggestion);
        finding.setDeduction("R-EMERGENCY-VISIT-TIME".equals(ruleCode) ? 2
            : ("R-EMERGENCY-VITAL-SIGNS".equals(ruleCode) ? 3 : 0));
        finding.setIgnored(Boolean.FALSE);
        return finding;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
