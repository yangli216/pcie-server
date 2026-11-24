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
    private static final Pattern DURATION_UNIT = Pattern.compile("(?:持续时间|时间|时长|病程)单位[0-9]*|durationUnit[0-9]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEASUREMENT = Pattern.compile("体温|脉搏|心率|呼吸(?:频率)?|收缩压|舒张压|血压|身高|体重|腰围|臀围|BMI|血氧(?:饱和度)?|SpO2|(?:空腹|随机|指尖)血糖", Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORY = Pattern.compile("(?:既往(?:病)?史|个人史|家族史|(?:药物)?过敏史|(?:既往|长期)?用药史|服药史)(?:正文|文本|内容)?");
    private static final Set<String> NEUTRAL = new HashSet<>(Arrays.asList("无","否认","否","没有","不存在","无异常","未见异常","无特殊","无明显诱因","如常","正常","尚可","良好","安"));
    private final Map<String, JsonNode> definitions = new HashMap<>();
    private final Map<String, Integer> chapterSizes = new HashMap<>();
    private final Set<String> chaptersWithDurationUnit = new HashSet<>();
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
            if (isDurationUnit(field)) chaptersWithDurationUnit.add(field.getArticleTemplateId());
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
        String body = body(f, effectiveMeaning);
        String format = "仅依据本次有来源的资料。优先医生本轮修订和已确认事实，缺项可回看问诊或转写原文。"
            + "未问不等于否认，预置、建议和候选不作事实。栏名与模板原句所指冲突且未经医生核实时返回空字符串。只返回本栏正文。";
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
        detail.setDefaultPrompt(combine(body(f, meaning), null, format));
        detail.setFormatRule(format);
        detail.setRestriction(reason);
        return detail;
    }

    static String combine(String body, String constraint, String format) {
        return body + (constraint == null ? "" : "\n" + constraint) + "\n" + format;
    }

    private static boolean isDurationUnit(OutpatientEmrTemplateFieldSnapshot f) {
        return DURATION_UNIT.matcher(f.getName()).matches() || DURATION_UNIT.matcher(f.getId()).matches();
    }

    private static boolean isTimeChoice(OutpatientEmrTemplateFieldSnapshot f) {
        return "时间".equals(f.getName()) && !f.getDictionaryItems().isEmpty();
    }

    private boolean isDurationQuantity(OutpatientEmrTemplateFieldSnapshot f) {
        if (Arrays.asList("date", "time", "datetime").contains(f.getType())) return false;
        if (DURATION.matcher(f.getName() + " " + f.getId()).find()) return true;
        return f.getDictionaryItems().isEmpty() && "时间".equals(f.getName()) && chaptersWithDurationUnit.contains(f.getArticleTemplateId())
            && ("chiefComplaint".equals(f.getRecordField()) || "historyOfPresentIllness".equals(f.getRecordField()));
    }

    private String defaultMeaning(OutpatientEmrTemplateFieldSnapshot field) {
        String label = field.getName() + " " + field.getId();
        if (isDurationUnit(field)) return "对应症状持续时间的单位";
        if (isTimeChoice(field)) return "本栏字典所指的时间特征";
        if (isDurationQuantity(field)) return "主要症状在本次就诊前已持续的时间";
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

    private String body(OutpatientEmrTemplateFieldSnapshot f, String meaning) {
        return "字段含义：" + meaning + "。\n" + clinicalTask(f);
    }

    private String clinicalTask(OutpatientEmrTemplateFieldSnapshot f) {
        String name = f.getName();
        String section = f.getArticleName() + " " + f.getArticleDefinitionName();
        boolean leaf = "section-compose".equals(f.getProjectionMode());
        if (isTimeChoice(f)) return "按本栏字典显示的含义填写。阶段选项只能匹配明确的阶段，不能将时长数量或字典编码当作阶段；仅有数量且无匹配选项时返回空字符串。";
        if (isDurationUnit(f)) return "只写原句中有依据的时间单位。有字典时匹配原有选项，不重复数量。";
        if (isDurationQuantity(f)) return "只提取该症状的持续时间。模板原句已印单位或相邻有单位槽时只填数量，否则保留资料中的单位。";
        if ("chiefComplaint".equals(f.getRecordField())) {
            if (leaf && chapterSizes.getOrDefault(f.getArticleTemplateId(), 0) > 1)
                return "仅填写这一栏描述的主诉成分。症状名称栏只写症状，持续时间由相邻栏承载。";
            return "普通主诉简写主要症状或体征及有据的持续时间，如“咳嗽3天”，不输出加号。仅在明确为慢病复诊配药时写本次已选病种及“复诊配药”。";
        }
        if ("historyOfPresentIllness".equals(f.getRecordField()))
            return leaf ? "只填写原句中本次现病史的这一小项。诱因、伴随症状、饮食或睡眠各须有据，阴性须明确对应本项。"
                : "按时间顺序写本次起病、诱因、症状演变、伴随表现、明确的关键阴性和已有诊疗经过。一般情况有据才写。";
        if (name.contains("过敏史"))
            return "只写患者本人已明确的对应过敏史。有据时记录过敏原与反应，明确否认本项才可选否定。";
        if ("pastMedicalHistory".equals(f.getRecordField()) || section.contains("既往史"))
            return leaf ? "按原句确认本项疾病及主体，肯否须有同一项目的明确依据，补充栏只填已知阳性细节。笼统病名不能推断具体亚型。"
                : "记录本人已知的既往疾病、手术外伤、输血、长期用药及过敏事实，保留有来源的时间。只在明确询问并否认对应项目时写否认。";
        if ("physicalExam".equals(f.getRecordField()) || section.contains("体格检查")) {
            if (MEASUREMENT.matcher(name).matches() || "number".equals(f.getType()))
                return "只用本次明确测得或记录的本项原值。血压分量与血糖采样时相分别匹配，BMI 不估算；固定单位由模板承载。";
            return "只写本次医生实际完成的本项查体，部位、侧别、程度及阴性均须有据。模板印好的正常项和候选呼吸音不能作为查体所见。仅有建议或未查时返回空字符串，即使候选命中字典。";
        }
        if (section.contains("辅助检查") || name.matches(".*(?:检验结果|检查结果).*"))
            return "只写已有检查检验结果，保留项目、数值、单位及必要的时间来源。仅有申请、预约或待出报告时返回空字符串，不复述待查状态，不推断诊断。";
        if (name.contains("诊断") || section.contains("诊断"))
            return "仅写医生本次已选正式诊断并保留主次顺序。按本栏区分中医与西医，不互推，不把疑似、待排或候选写成确诊。";
        if ("precautions".equals(f.getRecordField()) || section.contains("宣教") || section.contains("注意事项"))
            return "针对已选正式诊断与已确认处置写具体注意事项和就医指征，保留医生指定的复诊时间。慢病配药宣教不超过160字，涵盖规律用药、家庭监测、饮食和复诊。修订和回写时不补回默认期限。";
        if (section.matches(".*(?:医嘱|处置|治疗).*"))
            return "只整理医生已选、已开立或明确交代的医嘱，保留药名、用法、频次和剂量。不自行增加或换算，不把建议写成已开。";
        if ("personalHistory".equals(f.getRecordField())) return "仅写患者本人明确提供的个人史，保留暴露来源、用量与年限，不移用亲属资料。";
        if ("familyHistory".equals(f.getRecordField())) return "保留亲属与患者的关系，仅写该亲属明确的疾病或对应的明确否认。";
        if ("menstrualHistory".equals(f.getRecordField()) || "maritalReproductiveHistory".equals(f.getRecordField()))
            return "仅填写本栏已有明确依据的月经或婚育资料，两栏独立取证，不根据年龄、婚姻或既往孕产次数推断妊娠和哺乳。";
        return leaf ? "按原句位置填写该小项，其余内容由同章其他栏承载。" : "按本栏临床含义和原句组织已有事实。";
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
