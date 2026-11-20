package com.regionalai.floatingball.server.modules.emrtemplate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateFieldSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientFieldPolicy;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientFieldPolicy.Detail;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientFieldPolicy.Settings;

import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateDictionaryItemSnapshot;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The admin draft, effective preview and signed device response use this compiler. */
final class OutpatientFieldRuleCompiler {
    private static final Pattern ADMIN_CHAPTER = Pattern.compile("header|footer|页眉|页脚|签名信息|签名", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADMIN_FIELD = Pattern.compile("页[眉脚].*|(?:医师|医生)?签名(?:时间)?|签署时间|就诊时间|记录时间|就诊科室|(?:病历|门诊|住院|证件|身份证)号|(?:患者)?(?:姓名|性别|年龄)|idpi|napi|patientname|patientid|cdclinic|clinicno", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile("持续(?:时间|时长|多久)|病程(?:时间|时长)|(?:发作|症状|病情|患病)时长|duration", Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORY = Pattern.compile("(?:既往(?:病)?史|个人史|家族史|(?:药物)?过敏史|(?:既往|长期)?用药史|服药史)(?:正文|文本|内容)?");
    private static final Set<String> NEUTRAL = new HashSet<>(Arrays.asList("无","否认","否","没有","不存在","无异常","未见异常","无特殊","无明显诱因","如常","正常","尚可","良好","安"));
    private final Map<String, JsonNode> definitions = new HashMap<>();
    private final Map<String, Integer> chapterSizes = new HashMap<>();
    private final Map<String, Settings> settings;

    OutpatientFieldRuleCompiler(ObjectMapper mapper, String definition, List<OutpatientEmrTemplateFieldSnapshot> fields,
                                Map<String, Settings> settings) {
        this.settings = settings;
        JsonNode chapters;
        try { chapters = definition == null ? null : mapper.readTree(definition); }
        catch (Exception ex) { chapters = null; }
        if (chapters != null && chapters.isArray()) {
            for (JsonNode chapter : chapters) {
                for (JsonNode field : chapter.path("eles")) {
                    definitions.put(chapter.path("ID").asText() + "\u0000" + field.path("ID").asText(), field);
                }
            }
        }
        for (OutpatientEmrTemplateFieldSnapshot field : fields) {
            if (!Boolean.TRUE.equals(field.getReadonly())) chapterSizes.merge(field.getArticleTemplateId(), 1, Integer::sum);
        }
    }

    Detail describe(OutpatientEmrTemplateFieldSnapshot f) {
        Settings config = settings.getOrDefault(f.getId(), new Settings());
        JsonNode definition = definitions.get(f.getArticleTemplateId() + "\u0000" + f.getId());
        boolean protectedField = Boolean.TRUE.equals(f.getReadonly())
            || Arrays.asList(f.getArticleId(), f.getArticleTemplateId(), f.getArticleName(), f.getArticleDefinitionName())
                .stream().anyMatch(name -> name != null && ADMIN_CHAPTER.matcher(name.trim()).matches())
            || ADMIN_FIELD.matcher(f.getId()).matches() || ADMIN_FIELD.matcher(f.getName()).matches()
            || Arrays.asList("import", "button", "signature").contains(f.getType().toLowerCase(Locale.ROOT));
        String reason = protectedField ? (Boolean.TRUE.equals(f.getReadonly()) ? "模板只读字段" : "HIS 身份、登记或签名字段") : null;
        boolean defaultAi = !protectedField && (definition != null
            ? definition.path("AI_SUITABLE").asBoolean(true) : Boolean.TRUE.equals(f.getAiSuitable()));
        boolean ai = !protectedField && ("inherit".equals(config.getGenerationMode()) ? defaultAi : "ai".equals(config.getGenerationMode()));
        String meaning = defaultMeaning(f);
        String effectiveMeaning = config.getMeaningOverride() == null ? meaning : config.getMeaningOverride();
        String body = body(f, effectiveMeaning, chapterSizes.getOrDefault(f.getArticleTemplateId(), 0) > 1);
        String format = "仅依据本次有来源的资料。只返回本栏正文。";
        if (!f.getDictionaryItems().isEmpty()) format += "有依据时选用本栏字典的编码或显示文本。";
        else if ("number".equals(f.getType())) format += "数值栏只填数值，单位沿用模板。";
        boolean custom = config.getCustomBody() != null || config.getAdditionalConstraint() != null;
        OutpatientFieldPolicy.Field rule = new OutpatientFieldPolicy.Field();
        rule.setFieldId(f.getId());
        rule.setMeaning(effectiveMeaning);
        rule.setAiSuitable(ai);
        rule.setGenerationSource(protectedField ? "protected" : "inherit".equals(config.getGenerationMode()) ? "default" : "manual-override");
        rule.setPromptSource(ai ? (custom ? "custom" : "default") : "not_ai");
        rule.setResolvedPrompt(ai ? combine(config.getCustomBody() == null ? body : config.getCustomBody(), config.getAdditionalConstraint(), format) : null);
        rule.setUnmentionedDefault(ai ? unmentioned(f) : null);
        Detail detail = new Detail();
        detail.setOriginalField(f);
        detail.setSettings(config);
        detail.setEffective(rule);
        detail.setDefaultMeaning(meaning);
        detail.setDefaultAiSuitable(defaultAi);
        detail.setDefaultBody(body);
        detail.setDefaultPrompt(combine(body(f, meaning, chapterSizes.getOrDefault(f.getArticleTemplateId(), 0) > 1), null, format));
        detail.setFormatRule(format);
        detail.setRestriction(reason);
        return detail;
    }

    static String combine(String body, String constraint, String format) {
        return body + (constraint == null ? "" : "\n" + constraint) + "\n" + format;
    }

    private static String defaultMeaning(OutpatientEmrTemplateFieldSnapshot field) {
        String label = field.getName() + " " + field.getId();
        if (DURATION.matcher(label).find()) return "主要症状在本次就诊前已持续的时间";
        if ("chiefComplaint".equals(field.getRecordField())) {
            if (label.matches("(?i).*(症状名称|主要症状|chiefSymptom).*")) return "本次就诊的主要症状名称";
            if ("direct".equals(field.getProjectionMode())) return "患者本次就诊的主要症状及持续时间";
        }
        if ("direct".equals(field.getProjectionMode())) {
            if ("historyOfPresentIllness".equals(field.getRecordField())) return "本次疾病的起病、演变及已知诊疗经过";
            if ("pastMedicalHistory".equals(field.getRecordField())) return "本次就诊前已经记录或由患者陈述的既往病史";
            if ("physicalExam".equals(field.getRecordField())) return "本次就诊实际记录的体征和查体所见";
            if ("precautions".equals(field.getRecordField())) return "本次已交代的注意事项与随访安排";
        }
        return field.getName() + "（" + field.getArticleName() + "）";
    }

    private static String body(OutpatientEmrTemplateFieldSnapshot f, String meaning, boolean split) {
        String name = f.getName() + " " + f.getId();
        String task;
        if (DURATION.matcher(name).find()) task = "只提取该症状的持续时间。模板原句已印单位时只填数量，否则保留资料中的单位。";
        else if ("chiefComplaint".equals(f.getRecordField()) && split && !("direct".equals(f.getProjectionMode())))
            task = "仅填写这一栏描述的主诉成分。症状名称栏只写症状，持续时间由相邻栏承载。";
        else if ("chiefComplaint".equals(f.getRecordField())) task = "简要写主要症状或体征以及有依据的持续时间。";
        else if ("section-compose".equals(f.getProjectionMode())) task = "按原句位置填写该小项，其余内容由同章其他栏承载。";
        else if ("historyOfPresentIllness".equals(f.getRecordField())) task = "按时间顺序组织本次起病、症状演变及已知诊疗经过。";
        else task = "按本栏临床含义和原句组织已有事实。";
        return "字段含义：" + meaning + "。\n" + task;
    }

    private static String unmentioned(OutpatientEmrTemplateFieldSnapshot f) {
        String labels = f.getName() + f.getId() + f.getArticleName() + f.getArticleDefinitionName();
        if (labels.matches(".*(?:怀孕|妊娠|哺乳|月经|检验|检测|检查|查体|测量).*") ||
            Arrays.asList("physicalExam","maritalReproductiveHistory","menstrualHistory").contains(f.getRecordField())) return null;
        if (f.getDictionaryItems().isEmpty())
            return (HISTORY.matcher(f.getName()).matches() || HISTORY.matcher(f.getId()).matches())
                && (f.getBaselineValue() == null || f.getBaselineValue().isEmpty()) ? "无" : null;
        if (!labels.matches(".*(?:史|过敏|主诉).*") && !Arrays.asList("chiefComplaint", "historyOfPresentIllness",
            "pastMedicalHistory", "personalHistory", "familyHistory").contains(f.getRecordField())) return null;
        String neutral = null;
        for (OutpatientEmrTemplateDictionaryItemSnapshot item : f.getDictionaryItems()) {
            if (NEUTRAL.contains(item.getText())) {
                if (neutral != null) return null;
                neutral = item.getText();
            }
        }
        return neutral;
    }

    static Settings settings(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 6 || !body.has("fieldId") || !body.has("expectedRevision"))
            throw new BusinessException("请提交字段 ID、修订号和完整书写配置");
        Settings settings = new Settings();
        String mode = nullableText(body, "generationMode", 20);
        if (!Arrays.asList("inherit","ai","manual_or_his").contains(mode)) throw new BusinessException("生成类型不支持");
        settings.setGenerationMode(mode);
        settings.setMeaningOverride(nullableText(body, "meaningOverride", 2000));
        settings.setAdditionalConstraint(nullableText(body, "additionalConstraint", 16000));
        settings.setCustomBody(nullableText(body, "customBody", 16000));
        return settings;
    }

    static String nullableText(JsonNode body, String key, int max) {
        JsonNode value = body.get(key);
        if (value == null || (!value.isNull() && !value.isTextual()) || (value.isTextual() && value.textValue().length() > max))
            throw new BusinessException(key + " 缺失、类型或长度错误");
        return value.isNull() || value.textValue().trim().isEmpty() ? null : value.textValue().trim();
    }
}
