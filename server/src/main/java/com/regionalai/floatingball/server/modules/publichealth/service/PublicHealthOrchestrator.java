package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.prompt.dto.PromptView;
import com.regionalai.floatingball.server.modules.prompt.service.PromptService;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest.Observation;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest.MedicationClass;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class PublicHealthOrchestrator {
    static final String CHECK_OUTPUT_SHAPE = "{\"checks\":[{\"code\":\"白名单编码\",\"plannedDate\":\"YYYY-MM-DD\","
            + "\"priority\":\"low|medium|high\",\"reason\":\"基于事实的理由\",\"evidenceObservationIds\":[],"
            + "\"evidenceRefs\":[\"patient:ageYears\"],\"intervalKind\":\"routine|abnormal_retest\"}]}。"
            + "原六字段全部必传；evidenceRefs为可选字段，出现时须为1至16个唯一的healthProfile.evidence已有ref。"
            + "evidenceObservationIds为0至64个唯一观察ID，两类引用至少一类非空，不能跨两类重复引用同一观察。"
            + "缺少本项目基线的例行检查可引用真实年龄或已提供病种（如patient:ageYears、condition:0），"
            + "理由注明尚未提供本项目既往结果，不冒充已有异常。日期在snapshotAt后90天内；"
            + "abnormal_retest必须提供非空evidenceObservationIds，且全部abnormalCriteria由这些实际数值观察满足，"
            + "画像引用不能代替异常复查的数值证据或缩短间隔。";
    private final PublicHealthAssetService assets;
    private final PublicHealthRecipeRetriever retrieval;
    private final WeightMetricsCalculator calculator;
    private final PromptService prompts;
    private final PublicHealthModelService model;
    private final PublicHealthProperties properties;
    private final ObjectMapper mapper;
    public PublicHealthOrchestrator(PublicHealthAssetService assets, PublicHealthRecipeRetriever retrieval, WeightMetricsCalculator calculator,
    PromptService prompts, PublicHealthModelService model, PublicHealthProperties properties, ObjectMapper mapper) {
        this.assets = assets;
        this.retrieval = retrieval;
        this.calculator = calculator;
        this.prompts = prompts;
        this.model = model;
        this.properties = properties;
        this.mapper = mapper;
    }

    public Prepared prepare(AiDevice device, PublicHealthRequest request) {
        PublicHealthAssetService.Pack pack = assets.load(device, request.getCapability());
        Map<String, PromptView> resolved = new LinkedHashMap<String, PromptView>();
        for (String section:PublicHealthValidator.sections(request.getCapability())) {
            String code = "health_prescription".equals(request.getCapability())?"publicHealthHealthPrescription":("exercise_plan".equals(section)?"publicHealthExercisePlan":"publicHealthMealPlan");
            PromptView p = prompts.resolveEffectivePrompt(code, device.getIdOrg(), device.getIdRegion());
            if (p == null || p.getSysPrompt() == null || p.getSysPrompt().trim().isEmpty()
                    || !validVersionText(p.getCdPrompt()) || !validVersionText(p.getVersionNum())) {
                throw PublicHealthAssetService.unavailable("POLICY_NOT_READY");
            }
            resolved.put(section, p);
        }
        if ("weight_management".equals(request.getCapability()) && !properties.isRetrievalEnabled()) {
            throw PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY");
        }
        if (pack.policy != null) {
            for (MedicationClass m:request.getFacts().getMedicationClasses()) {
                if (!pack.policy.path("medicationReminders").hasNonNull(m.getCode())) {
                    throw PublicHealthAssetService.unavailable("POLICY_NOT_READY");
                }
            }
        }
        return new Prepared(pack, resolved);
    }

    private boolean validVersionText(String value) {
        return value != null && !value.trim().isEmpty()
                && value.codePointCount(0, value.length()) <= 128;
    }

    public ObjectNode generate(AiDevice device, PublicHealthRequest request, String generationId, Prepared prepared, Listener listener) {
        ObjectNode result = identity(request);
        result.put("schemaVersion","public-health.v1");
        result.put("requestId", request.getRequestId());
        result.put("generationId", generationId);
        ArrayNode sections = result.putArray("sections"), versions = result.putArray("promptVersions");
        Set<String> promptIds = new HashSet<String>();
        result.set("knowledgeVersions", prepared.pack.versions.deepCopy());
        ArrayNode warnings = result.putArray("warnings");
        ObjectNode context = mapper.valueToTree(request);
        context.remove("writebackTargets");
        ObjectNode healthProfile = null;
        if ("health_prescription".equals(request.getCapability())) {
            healthProfile = PublicHealthHealthProfile.build(request, mapper);
            context.set("healthProfile", healthProfile);
            Set<String> usableIds = PublicHealthHealthProfile.observationEvidenceIds(healthProfile);
            ArrayNode observations = ((ObjectNode) context.path("facts")).putArray("observations");
            for (Observation observation : request.getFacts().getObservations()) {
                if (usableIds.contains(observation.getId())) observations.add(mapper.valueToTree(observation));
            }
        }
        if (prepared.pack.policy != null) {
            context.set("policy", healthProfile == null ? prepared.pack.policy
                    : PublicHealthCheckEligibility.filter(prepared.pack.policy, request, healthProfile));
            if (healthProfile != null) context.set("checkScheduleConstraints",
                    PublicHealthCheckEligibility.schedule(request, prepared.pack.policy, healthProfile, mapper));
        }
        if ("weight_management".equals(request.getCapability())) {
            listener.progress("calculating_metrics");
            ObjectNode metrics = calculator.calculate(request);
            result.set("metrics", metrics);
            context.set("metrics", metrics);
            listener.metrics(metrics);
            listener.progress("retrieving_knowledge");
            String query = "地区:"+request.getFacts().getWeightProfile().getRegion()+", 季节:"+request.getFacts().getWeightProfile().getSeason()+",基础能量消耗:"+metrics.path("bmrKcalPerDay").asText()+"kcal";
            context.set("recipeReferences", retrieval.retrieve(device, prepared.pack.recipes, query, () -> listener.progress("retrieving_knowledge")));
        }
        int length = 0;
        for (String id:PublicHealthValidator.sections(request.getCapability())) {
            listener.progress("generating_sections");
            context.put("requestedSection", id);
            PromptView prompt = prepared.prompts.get(id);
            String shape;
            if ("check_plan".equals(id)) {
                shape = CHECK_OUTPUT_SHAPE;
            } else if ("medication_guidance".equals(id)) {
                shape = "{\"medicationGuidance\":[{\"classCode\":\"已提供类别编码\",\"content\":\"仅该类别供患者执行的指导\"}]}";
            } else if ("lifestyle_guidance".equals(id)) {
                shape = PublicHealthHealthEducation.OUTPUT_SHAPE;
            } else if ("meal_plan".equals(id) && context.path("recipeReferences").size() >= 3) {
                shape = "{\"options\":[{\"recipeId\":\"提供的来源id\",\"content\":\"本份完整食谱\"},{\"recipeId\":\"另一来源id\",\"content\":\"本份完整食谱\"},{\"recipeId\":\"第三个来源id\",\"content\":\"本份完整食谱\"}]}。恰好三项、源ID唯一且来自recipeReferences；首项为默认推荐、其余为备选，每份仅含一个原食谱，保留原食材份量；默认推荐不是医学适用性确认，不根据BMR设定摄入目标。";
            } else {
                shape = "{\"content\":\"本章节非空正文\"}";
            }
            JsonNode raw = model.generate(device, prompt, context, generationId, id, shape);
            raw = PublicHealthPatientText.separateDoctorNotes(raw, warnings, id, "");
            ObjectNode section = mapper.createObjectNode();
            section.put("id", id);
            section.put("title", title(id));
            if ("meal_plan".equals(id) && context.path("recipeReferences").size() >= 3) {
                section.set("options", mealOptions(raw, context.path("recipeReferences"), warnings));
            }
            String text;
            if ("check_plan".equals(id)) text = checks(raw, prepared.pack.policy, request, healthProfile, warnings);
            else if ("medication_guidance".equals(id)) text = medications(raw, prepared.pack.policy, request, warnings);
            else if ("lifestyle_guidance".equals(id)) text = PublicHealthHealthEducation.render(raw, healthProfile, warnings);
            else if (section.has("options")) text = section.path("options").get(0).path("content").asText();
            else text = content(raw);
            if ((text.trim().isEmpty() && !"health_prescription".equals(request.getCapability())) || text.codePointCount(0, text.length())>16000) {
                throw PublicHealthModelService.invalid();
            }
            PublicHealthPatientText.requirePatientText(text);
            if ("exercise_plan".equals(id)) PublicHealthPatientText.requireExercise(text);
            if (section.has("options")) {
                for (JsonNode option:section.path("options")) {
                    String optionText = option.path("content").asText();
                    length += optionText.codePointCount(0, optionText.length());
                }
            } else {
                length += text.codePointCount(0, text.length());
            }
            if (length>64000) {
                throw PublicHealthModelService.invalid();
            }
            section.put("content", text);
            sections.add(section);
            listener.section(section);
            String code = prompt.getCdPrompt();
            if (code == null || code.trim().isEmpty()) {
                throw PublicHealthAssetService.unavailable("POLICY_NOT_READY");
            }
            if (promptIds.add(code)) {
                ObjectNode pv = versions.addObject();
                pv.put("code", code);
                pv.put("version", prompt.getVersionNum());
            }
        }
        if (length == 0) throw PublicHealthModelService.invalid();
        listener.progress("validating_output");
        result.put("generatedAt", OffsetDateTime.now().toString());
        return result;
    }

    private String checks(JsonNode raw, JsonNode policy, PublicHealthRequest request, JsonNode profile, ArrayNode warnings) {
        only(raw,"checks");
        JsonNode checks = raw.path("checks");
        if (!checks.isArray() || checks.size()>100) {
            throw PublicHealthModelService.invalid();
        }
        Map<String, JsonNode> allowed = new HashMap<String, JsonNode>();
        for (JsonNode p:policy.path("checkWhitelist")) {
            if (PublicHealthCheckEligibility.matches(p.path("eligibility"), request, profile)) allowed.put(p.path("code").asText(), p);
        }
        Set<String> usableIds = PublicHealthHealthProfile.observationEvidenceIds(profile);
        Map<String, JsonNode> availableRefs = new LinkedHashMap<String, JsonNode>();
        for (JsonNode entry : profile.path("evidence")) availableRefs.put(entry.path("ref").asText(), entry);
        Set<String> seen = new HashSet<String>();
        StringBuilder text = new StringBuilder();
        StringBuilder doctorText = new StringBuilder();
        LocalDate day = OffsetDateTime.parse(request.getSnapshotAt()).toLocalDate();
        for (JsonNode item:checks) {
            if (item.has("evidenceRefs")) only(item,"code","plannedDate","priority","reason","evidenceObservationIds","intervalKind","evidenceRefs");
            else only(item,"code","plannedDate","priority","reason","evidenceObservationIds","intervalKind");
            String code = PublicHealthHealthEducation.read(item.path("code"), 128);
            JsonNode rule = allowed.get(code);
            if (rule == null || !seen.add(code) || !item.path("plannedDate").isTextual()
                    || !item.path("plannedDate").asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw PublicHealthModelService.invalid();
            }
            LocalDate plan;
            try {
                plan = LocalDate.parse(item.path("plannedDate").asText());
            } catch (Exception e) {
                throw PublicHealthModelService.invalid();
            }
            if (plan.isBefore(day) || plan.isAfter(day.plusDays(90))) {
                throw PublicHealthModelService.invalid();
            }
            String priority = PublicHealthHealthEducation.read(item.path("priority"), 6);
            String reason = PublicHealthHealthEducation.read(item.path("reason"), 2000);
            String intervalKind = PublicHealthHealthEducation.read(item.path("intervalKind"), 15);
            if (!Arrays.asList("low","medium","high").contains(priority)
                    || !Arrays.asList("routine","abnormal_retest").contains(intervalKind)) {
                throw PublicHealthModelService.invalid();
            }
            JsonNode evidenceIds = item.path("evidenceObservationIds");
            if (!evidenceIds.isArray() || evidenceIds.size()>64
                    || ("abnormal_retest".equals(intervalKind) && evidenceIds.size() == 0)) {
                throw PublicHealthModelService.invalid();
            }
            List<Observation> evidence = new ArrayList<Observation>();
            List<JsonNode> citations = new ArrayList<JsonNode>();
            Set<String> evidenceSeen = new HashSet<String>();
            for (JsonNode id:evidenceIds) {
                String value = PublicHealthHealthEducation.read(id, 128);
                String ref = "observation:" + value;
                if (!evidenceSeen.add(ref)) {
                    throw PublicHealthModelService.invalid();
                }
                Observation matched = null;
                for (Observation obs:request.getFacts().getObservations()) {
                    if (value.equals(obs.getId())) {
                        matched = obs;
                    }
                }
                if (matched == null || !usableIds.contains(matched.getId())) {
                    throw PublicHealthModelService.invalid();
                }
                evidence.add(matched);
                citations.add(availableRefs.get(ref));
            }
            if (item.has("evidenceRefs")) {
                JsonNode refs = item.path("evidenceRefs");
                if (!refs.isArray() || refs.size() == 0 || refs.size() > 16) throw PublicHealthModelService.invalid();
                for (JsonNode ref : refs) {
                    String value = PublicHealthHealthEducation.read(ref, 256);
                    if (!evidenceSeen.add(value) || !availableRefs.containsKey(value)) throw PublicHealthModelService.invalid();
                    citations.add(availableRefs.get(value));
                }
            }
            if (citations.isEmpty()) throw PublicHealthModelService.invalid();
            int interval = rule.path("minIntervalDays").asInt();
            if ("abnormal_retest".equals(intervalKind)) {
                if (!PublicHealthCheckEligibility.abnormalSupported(rule.path("abnormalCriteria"), evidence, day)) {
                    throw PublicHealthAssetService.unavailable("POLICY_NOT_READY");
                }
                interval = rule.path("abnormalRetestIntervalDays").asInt();
            }
            boolean hasHistoricalResult = false;
            for (Observation obs:request.getFacts().getObservations()) {
                if (code.equals(obs.getCode())
                        && !OffsetDateTime.parse(obs.getObservedAt()).toInstant().isAfter(OffsetDateTime.parse(request.getSnapshotAt()).toInstant())) {
                    hasHistoricalResult = true;
                    if (plan.isBefore(OffsetDateTime.parse(obs.getObservedAt()).toLocalDate().plusDays(interval))) throw PublicHealthModelService.invalid();
                }
            }
            if (text.length()>0) {
                text.append("\n\n");
            }
            text.append(rule.path("name").asText()).append(" · ").append(plan);
            StringBuilder doctor = new StringBuilder(rule.path("name").asText()).append(" · ").append(plan)
                    .append("\n优先级：").append("high".equals(priority)?"高":("medium".equals(priority)?"中":"低"));
            doctor.append("\n依据：").append(reason).append("\n证据：");
            for (int i = 0; i<citations.size(); i++) {
                if (i>0) {
                    doctor.append("；");
                }
                doctor.append(PublicHealthHealthEducation.describe(citations.get(i), request.getSnapshotAt()));
            }
            if (!hasHistoricalResult) doctor.append("\n既往结果：尚未提供本项目既往结果。");
            else for (JsonNode conflict : profile.path("conflicts")) {
                if (code.equals(conflict.path("code").asText())) doctor.append("\n既往结果：本项目最新结果存在冲突，请医生核对。");
            }
            String prep = rule.path("prep").asText();
            List<String> doctorPrep = new ArrayList<String>();
            String patientPrep = PublicHealthPatientText.patientPreparation(prep, doctorPrep);
            if (!patientPrep.isEmpty()) text.append("\n准备事项：").append(patientPrep);
            for (String block : doctorPrep) doctor.append("\n准备事项：").append(block);
            if (doctorText.length() > 0) doctorText.append("\n\n");
            doctorText.append(doctor);
        }
        if (doctorText.length() > 0) PublicHealthPatientText.warning(warnings, "CHECK_RATIONALE", "check_plan", doctorText.toString());
        if (text.length() == 0) PublicHealthPatientText.warning(warnings, "NO_PATIENT_ADVICE", "check_plan",
                "本次未生成可回写的检查计划，请医生核对资料、适用目录及检查间隔。");
        return text.toString();
    }

    private String medications(JsonNode raw, JsonNode policy, PublicHealthRequest request, ArrayNode warnings) {
        only(raw,"medicationGuidance");
        JsonNode list = raw.path("medicationGuidance");
        if (!list.isArray() || list.size()>64) {
            throw PublicHealthModelService.invalid();
        }
        Map<String, String> allowed = new HashMap<String, String>();
        for (MedicationClass m:request.getFacts().getMedicationClasses()) {
            allowed.put(m.getCode(), m.getName());
        }
        Set<String> seen = new HashSet<String>();
        StringBuilder text = new StringBuilder();
        StringBuilder doctorText = new StringBuilder();
        for (JsonNode item:list) {
            only(item,"classCode","content");
            String code = item.path("classCode").asText();
            if (!allowed.containsKey(code) || !seen.add(code) || !policy.path("medicationReminders").hasNonNull(code)) {
                throw PublicHealthModelService.invalid();
            }
            String content = readContent(item);
            if (text.length()>0) {
                text.append("\n\n");
            }
            text.append(allowed.get(code)).append("\n").append(content);
            if (doctorText.length() > 0) doctorText.append("\n\n");
            doctorText.append(allowed.get(code)).append("：").append(policy.path("medicationReminders").path(code).asText());
        }
        if (!seen.equals(allowed.keySet())) {
            throw PublicHealthModelService.invalid();
        }
        if (doctorText.length() > 0) PublicHealthPatientText.warning(warnings, "MEDICATION_REMINDER", "medication_guidance", doctorText.toString());
        if (text.length() == 0) PublicHealthPatientText.warning(warnings, "NO_PATIENT_ADVICE", "medication_guidance",
                "未提供用药类别资料，本次没有可回写的用药指导，请先核对用药信息。");
        return text.toString();
    }

    private String content(JsonNode raw) {
        only(raw,"content");
        return readContent(raw);
    }

    private ArrayNode mealOptions(JsonNode raw, JsonNode references, ArrayNode warnings) {
        only(raw, "options");
        JsonNode options = raw.path("options");
        if (!options.isArray() || options.size() != 3) throw PublicHealthModelService.invalid();
        Set<String> seen = new HashSet<String>();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode option:options) {
            if (!option.isObject()) throw PublicHealthModelService.invalid();
            if (!option.path("recipeId").isTextual()) throw PublicHealthModelService.invalid();
            String id = option.path("recipeId").asText();
            if (id.trim().isEmpty() || id.codePointCount(0, id.length()) > 128 || !seen.add(id)) throw PublicHealthModelService.invalid();
            JsonNode source = null;
            for (JsonNode reference:references) {
                if (reference.path("id").isTextual() && id.equals(reference.path("id").asText())) { source = reference; break; }
            }
            if (source == null) throw PublicHealthModelService.invalid();
            String sourceTitle = source.path("text").asText().split("\\r?\\n", 2)[0].trim();
            if (sourceTitle.isEmpty()) sourceTitle = source.path("title").asText();
            JsonNode patientOption = PublicHealthPatientText.separateDoctorNotes(option, warnings, "meal_plan", sourceTitle + "：");
            only(patientOption, "recipeId", "content");
            String text = readContent(patientOption);
            if (sourceTitle.trim().isEmpty() || sourceTitle.codePointCount(0, sourceTitle.length()) > 100 || text.codePointCount(0, text.length()) > 16000) throw PublicHealthModelService.invalid();
            result.addObject().put("id", id).put("title", sourceTitle).put("content", text);
        }
        return result;
    }

    private String readContent(JsonNode raw) {
        JsonNode c = raw.path("content");
        if (!c.isTextual() || c.asText().trim().isEmpty()) {
            throw PublicHealthModelService.invalid();
        }
        return PublicHealthPatientText.requirePatientText(c.asText());
    }

    private void only(JsonNode raw, String... fields) {
        if (!raw.isObject() || raw.size() != fields.length) {
            throw PublicHealthModelService.invalid();
        }
        for (String f:fields) {
            if (!raw.hasNonNull(f)) {
                throw PublicHealthModelService.invalid();
            }
        }
    }

    private String title(String id) {
        if ("check_plan".equals(id)) {
            return "检查计划";
        }
        if ("medication_guidance".equals(id)) {
            return "用药指导";
        }
        if ("lifestyle_guidance".equals(id)) {
            return "生活方式建议";
        }
        if ("exercise_plan".equals(id)) {
            return "运动建议";
        }
        return "食谱推荐";
    }

    public ObjectNode identity(PublicHealthRequest r) {
        ObjectNode out = mapper.createObjectNode();
        out.put("originRequestId", r.getOriginRequestId());
        out.put("capability", r.getCapability());
        out.put("scene", r.getScene());
        out.put("patientId", r.getPatientId());
        out.put("businessRecordId", r.getBusinessRecordId());
        out.put("recordVersion", r.getRecordVersion());
        out.put("snapshotAt", r.getSnapshotAt());
        return out;
    }

    public interface Listener {
        void progress(String stage);
        void metrics(ObjectNode metrics);
        void section(ObjectNode section);
    }

    public static final class Prepared {
        public final PublicHealthAssetService.Pack pack;
        public final Map<String, PromptView> prompts;
        public Prepared(PublicHealthAssetService.Pack p, Map<String, PromptView> prompts) {
            this.pack = p;
            this.prompts = prompts;
        }
    }
}
