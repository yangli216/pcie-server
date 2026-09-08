package com.regionalai.floatingball.server.modules.prompt.service;

import com.regionalai.floatingball.server.modules.prompt.dto.PromptView;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PromptDefaultCatalog {

    private final Map<String, Definition> definitions;

    public PromptDefaultCatalog() {
        Map<String, Definition> values = new LinkedHashMap<String, Definition>();
        values.put("voiceTranscriptCalibration", new Definition(
            "voiceTranscriptCalibration",
            "语音转写校准",
            "consultation",
            "v1.0",
            "prompt-defaults/voiceTranscriptCalibration.system.txt",
            "prompt-defaults/voiceTranscriptCalibration.user.txt"
        ));
        values.put("voiceIntentRecognition", new Definition(
            "voiceIntentRecognition",
            "语音问诊结构化抽取",
            "consultation",
            "v2.1",
            "prompt-defaults/voiceIntentRecognition.system.txt",
            "prompt-defaults/voiceIntentRecognition.user.txt"
        ));
        values.put("voiceIntentRecognitionStream", new Definition(
            "voiceIntentRecognitionStream",
            "语音问诊渐进结构化抽取",
            "consultation",
            "v1.3",
            "prompt-defaults/voiceIntentRecognitionStream.system.txt",
            "prompt-defaults/voiceIntentRecognitionStream.user.txt"
        ));
        values.put("voiceIntentRepair", new Definition(
            "voiceIntentRepair",
            "语音问诊结构修复",
            "consultation",
            "v1.3",
            "prompt-defaults/voiceIntentRepair.system.txt",
            "prompt-defaults/voiceIntentRepair.user.txt"
        ));
        values.put("medicalRecordGeneration", new Definition(
            "medicalRecordGeneration",
            "门诊病历生成",
            "consultation",
            "v1.0",
            "prompt-defaults/medicalRecordGeneration.system.txt",
            "prompt-defaults/medicalRecordGeneration.user.txt"
        ));
        values.put("diagnosisRecommendation", new Definition(
            "diagnosisRecommendation",
            "诊断推荐",
            "consultation",
            "v1.0",
            "prompt-defaults/diagnosisRecommendation.system.txt",
            "prompt-defaults/diagnosisRecommendation.user.txt"
        ));
        values.put("treatmentRecommendation", new Definition(
            "treatmentRecommendation",
            "治疗方案推荐",
            "consultation",
            "v2.1",
            "prompt-defaults/treatmentRecommendation.system.txt",
            "prompt-defaults/treatmentRecommendation.user.txt"
        ));
        values.put("examinationRecommendation", new Definition(
            "examinationRecommendation",
            "检查推荐",
            "consultation",
            "v1.0",
            "prompt-defaults/examinationRecommendation.system.txt",
            "prompt-defaults/examinationRecommendation.user.txt"
        ));
        values.put("labTestRecommendation", new Definition(
            "labTestRecommendation",
            "检验推荐",
            "consultation",
            "v1.0",
            "prompt-defaults/labTestRecommendation.system.txt",
            "prompt-defaults/labTestRecommendation.user.txt"
        ));
        values.put("procedureRecommendation", new Definition(
            "procedureRecommendation",
            "处置推荐",
            "consultation",
            "v1.1",
            "prompt-defaults/procedureRecommendation.system.txt",
            "prompt-defaults/procedureRecommendation.user.txt"
        ));
        values.put("voiceSafetyReview", new Definition(
            "voiceSafetyReview",
            "语音问诊安全复核",
            "review",
            "v1.0",
            "prompt-defaults/voiceSafetyReview.system.txt",
            "prompt-defaults/voiceSafetyReview.user.txt"
        ));
        values.put("diagnosisCheck", new Definition(
            "diagnosisCheck",
            "诊断事实核查",
            "review",
            "v1.0",
            "prompt-defaults/diagnosisCheck.system.txt",
            "prompt-defaults/diagnosisCheck.user.txt"
        ));
        values.put("medicineCheck", new Definition(
            "medicineCheck",
            "用药合理性核查",
            "review",
            "v1.0",
            "prompt-defaults/medicineCheck.system.txt",
            "prompt-defaults/medicineCheck.user.txt"
        ));
        values.put("medicalRecordCheck", new Definition(
            "medicalRecordCheck",
            "病历一致性核查",
            "review",
            "v1.0",
            "prompt-defaults/medicalRecordCheck.system.txt",
            "prompt-defaults/medicalRecordCheck.user.txt"
        ));
        values.put("consultationRecordDraft", new Definition(
            "consultationRecordDraft",
            "症状问诊病历草稿",
            "symptom_consultation",
            "v1.0",
            "prompt-defaults/consultationRecordDraft.system.txt",
            "prompt-defaults/consultationRecordDraft.user.txt"
        ));
        values.put("inpatientEmrGenerate", new Definition(
            "inpatientEmrGenerate",
            "住院病历字段生成",
            "inpatient_emr",
            "v1.0",
            "prompt-defaults/inpatientEmrGenerate.system.txt",
            "prompt-defaults/inpatientEmrGenerate.user.txt"
        ));
        definitions = Collections.unmodifiableMap(values);
    }

    public PromptView resolve(String cdPrompt) {
        if (!StringUtils.hasText(cdPrompt)) {
            return null;
        }
        Definition definition = definitions.get(cdPrompt.trim());
        if (definition == null) {
            return null;
        }
        PromptView view = new PromptView();
        view.setIdPrompt("builtin:" + definition.cdPrompt);
        view.setCdPrompt(definition.cdPrompt);
        view.setNaPrompt(definition.naPrompt);
        view.setSysPrompt(load(definition.systemPath));
        view.setUserTemplate(load(definition.userTemplatePath));
        view.setVersionNum(definition.versionNum);
        view.setSdPromptType(definition.promptType);
        view.setSdStatus("1");
        view.setSource("built_in");
        view.setBuiltIn(Boolean.TRUE);
        return view;
    }

    public List<PromptView> list() {
        List<PromptView> views = new ArrayList<PromptView>();
        for (String cdPrompt : definitions.keySet()) {
            PromptView view = resolve(cdPrompt);
            if (view != null) {
                views.add(view);
            }
        }
        return views;
    }

    private String load(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return "";
            }
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            return "";
        }
    }

    private static class Definition {
        private final String cdPrompt;
        private final String naPrompt;
        private final String promptType;
        private final String versionNum;
        private final String systemPath;
        private final String userTemplatePath;

        Definition(String cdPrompt,
                   String naPrompt,
                   String promptType,
                   String versionNum,
                   String systemPath,
                   String userTemplatePath) {
            this.cdPrompt = cdPrompt;
            this.naPrompt = naPrompt;
            this.promptType = promptType;
            this.versionNum = versionNum;
            this.systemPath = systemPath;
            this.userTemplatePath = userTemplatePath;
        }
    }
}
