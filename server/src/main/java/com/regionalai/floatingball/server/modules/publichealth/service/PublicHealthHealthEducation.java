package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.*;
import java.util.regex.Pattern;

/** Validates the internal HealthEducationDraft and renders the existing text-only section. */
public final class PublicHealthHealthEducation {
    public static final String OUTPUT_SHAPE = "{\"risks\":[{\"type\":\"progression|complication\",\"name\":\"风险名称\","
            + "\"concern\":\"attention|needs_assessment\",\"evidenceRefs\":[\"healthProfile.evidence中已有ref\"],\"intervention\":\"干预建议\"}],"
            + "\"prevention\":[\"疾病预防条目\"],\"diet\":[\"饮食建议\"],\"exercise\":[\"运动建议\"],\"followup\":[\"随访安排\"],\"missingFacts\":[\"待补充资料\"]}。"
            + "六字段全部必传，可选doctorNotes为0至8条医生提醒，除此不添加其他字段；risks最多6项，每项必须引用1至16个唯一的已有证据ref；"
            + "其余数组各最多8条，条目非空且最多1000字，不重复。没有依据的类别用空数组。"
            + "风险名称/分类/关注状态/证据和missingFacts仅供医生查看，intervention及prevention/diet/exercise/followup为交给患者的具体行动；"
            + "患者正文不得有解释、证据出处或待医生核实事项，医生提醒写doctorNotes。风险不是患病概率、诊断、量化风险分数或发起方已有风险等级。";
    private static final String[] ADVICE = {"prevention", "diet", "exercise", "followup", "missingFacts"};
    private static final String[] TITLES = {"疾病预防", "饮食建议", "运动建议", "随访安排"};
    private static final Pattern DISALLOWED_MODEL_TEXT = Pattern.compile(
            "BMI|BMR|kcal|mm\\s*Hg|mmol\\s*[/／]\\s*L|减重目标|目标体重|三餐参考食谱|备选食谱|千卡|卡路里",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private PublicHealthHealthEducation() { }

    public static String render(JsonNode raw, JsonNode profile) {
        return render(raw, profile, JsonNodeFactory.instance.arrayNode());
    }

    public static String render(JsonNode raw, JsonNode profile, ArrayNode warnings) {
        raw = PublicHealthPatientText.separateDoctorNotes(raw, warnings, "lifestyle_guidance", "");
        only(raw, "risks", "prevention", "diet", "exercise", "followup", "missingFacts");
        Map<String, JsonNode> evidence = new LinkedHashMap<String, JsonNode>();
        for (JsonNode item : profile.path("evidence")) evidence.put(item.path("ref").asText(), item);
        JsonNode risks = raw.path("risks");
        if (!risks.isArray() || risks.size() > 6) fail();
        StringBuilder text = new StringBuilder();
        if (risks.size() == 0) PublicHealthPatientText.warning(warnings, "RISK_ASSESSMENT", "lifestyle_guidance",
                "本次未生成可核实的潜在风险条目；不能据此判断无风险，请医生结合资料评估。");
        for (int i = 0; i < risks.size(); i++) {
            JsonNode risk = risks.get(i);
            only(risk, "type", "name", "concern", "evidenceRefs", "intervention");
            String type = read(risk.path("type"), 100);
            String concern = read(risk.path("concern"), 100);
            if (!Arrays.asList("progression", "complication").contains(type)
                    || !Arrays.asList("attention", "needs_assessment").contains(concern)) fail();
            String name = modelText(risk.path("name"), 100);
            String intervention = PublicHealthPatientText.requirePatientText(modelText(risk.path("intervention"), 1000));
            JsonNode refs = risk.path("evidenceRefs");
            if (!refs.isArray() || refs.size() < 1 || refs.size() > 16) fail();
            Set<String> seen = new HashSet<String>();
            List<JsonNode> cited = new ArrayList<JsonNode>();
            for (JsonNode ref : refs) {
                String value = read(ref, 256);
                if (!seen.add(value) || !evidence.containsKey(value)) fail();
                cited.add(evidence.get(value));
            }
            StringBuilder doctor = new StringBuilder("潜在风险提示（AI 定性分析；不表示概率或诊断）\n").append(name).append("（")
                    .append("progression".equals(type) ? "慢病进展" : "并发症").append("；")
                    .append("attention".equals(concern) ? "关注" : "待评估").append("）\n证据：");
            for (int j = 0; j < cited.size(); j++) {
                if (j > 0) doctor.append("；");
                doctor.append(describe(cited.get(j), profile.path("snapshotAt").asText()));
            }
            PublicHealthPatientText.warning(warnings, "RISK_ASSESSMENT", "lifestyle_guidance", doctor.toString());
            if (text.length() == 0) text.append("健康管理");
            text.append("\n").append(i + 1).append(". ").append(intervention);
        }
        for (int i = 0; i < ADVICE.length; i++) {
            JsonNode list = raw.path(ADVICE[i]);
            if (!list.isArray() || list.size() > 8) fail();
            Set<String> seen = new HashSet<String>();
            if (list.size() > 0 && !"missingFacts".equals(ADVICE[i])) {
                if (text.length() > 0) text.append("\n\n");
                text.append(TITLES[i]);
            }
            for (int j = 0; j < list.size(); j++) {
                String value = modelText(list.get(j), 1000);
                if (!seen.add(value)) fail();
                if ("missingFacts".equals(ADVICE[i])) {
                    PublicHealthPatientText.warning(warnings, "MISSING_FACTS", "lifestyle_guidance", "待补充资料：" + value);
                } else {
                    text.append("\n").append(j + 1).append(". ").append(PublicHealthPatientText.requirePatientText(value));
                }
            }
        }
        if (text.length() == 0) PublicHealthPatientText.warning(warnings, "NO_PATIENT_ADVICE", "lifestyle_guidance",
                "本次资料不足以生成患者健康教育条目，请医生核对并补充资料。");
        if (text.codePointCount(0, text.length()) > 16000) fail();
        return text.toString();
    }

    private static String modelText(JsonNode value, int max) {
        String text = read(value, max);
        if (DISALLOWED_MODEL_TEXT.matcher(text).find()) fail();
        return text;
    }

    static String describe(JsonNode evidence, String snapshotAt) {
        JsonNode value = evidence.path("value");
        String rendered = value.asText();
        if ("patient:sex".equals(evidence.path("ref").asText())) rendered = "male".equals(rendered) ? "男" : "女";
        StringBuilder text = new StringBuilder(evidence.path("label").asText()).append("：").append(rendered);
        if (evidence.has("unit")) text.append(" ").append(evidence.path("unit").asText());
        if (evidence.has("observedAt")) text.append("；观察时间：").append(evidence.path("observedAt").asText());
        else text.append("；资料快照：").append(snapshotAt);
        if (evidence.has("source")) text.append("；来源：").append(evidence.path("source").path("system").asText())
                .append(" / 记录：").append(evidence.path("source").path("recordId").asText());
        return text.toString();
    }

    static String read(JsonNode value, int max) {
        if (!value.isTextual()) fail();
        String text = value.asText();
        boolean hasText = text.codePoints().anyMatch(c -> c != 0xFEFF && !Character.isWhitespace(c) && !Character.isSpaceChar(c));
        if (!hasText || text.codePointCount(0, text.length()) > max) fail();
        return text;
    }

    private static void only(JsonNode value, String... fields) {
        if (!value.isObject() || value.size() != fields.length) fail();
        for (String field : fields) if (!value.hasNonNull(field)) fail();
    }

    private static void fail() { throw PublicHealthModelService.invalid(); }
}
