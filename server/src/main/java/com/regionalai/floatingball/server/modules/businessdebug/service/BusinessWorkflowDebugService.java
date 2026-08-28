package com.regionalai.floatingball.server.modules.businessdebug.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.ai.dto.ChatRequest;
import com.regionalai.floatingball.server.modules.ai.service.AiProxyService;
import com.regionalai.floatingball.server.modules.businessdebug.dto.BusinessDebugConsultationItem;
import com.regionalai.floatingball.server.modules.businessdebug.dto.BusinessDebugContextVO;
import com.regionalai.floatingball.server.modules.businessdebug.dto.BusinessDebugExecuteRequest;
import com.regionalai.floatingball.server.modules.businessdebug.dto.BusinessDebugExecuteResponse;
import com.regionalai.floatingball.server.modules.businessdebug.dto.BusinessDebugNodeVO;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.mapper.AiDeviceMapper;
import com.regionalai.floatingball.server.modules.prompt.dto.PromptView;
import com.regionalai.floatingball.server.modules.prompt.service.PromptService;
import com.regionalai.floatingball.server.modules.userlog.entity.AiUserConsultationLog;
import com.regionalai.floatingball.server.modules.userlog.mapper.AiUserConsultationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BusinessWorkflowDebugService {

    private static final String SCENE_VOICE_CONSULTATION = "voice_consultation";
    private static final String CONSULTATION_TYPE_VOICE = "voice";

    private final AiUserConsultationLogMapper consultationLogMapper;
    private final AiDeviceMapper deviceMapper;
    private final PromptService promptService;
    private final AiProxyService aiProxyService;
    private final ObjectMapper objectMapper;
    private final List<NodeDefinition> voiceNodes;

    public BusinessWorkflowDebugService(AiUserConsultationLogMapper consultationLogMapper,
                                        AiDeviceMapper deviceMapper,
                                        PromptService promptService,
                                        AiProxyService aiProxyService,
                                        ObjectMapper objectMapper) {
        this.consultationLogMapper = consultationLogMapper;
        this.deviceMapper = deviceMapper;
        this.promptService = promptService;
        this.aiProxyService = aiProxyService;
        this.objectMapper = objectMapper;
        this.voiceNodes = buildVoiceNodes();
    }

    public PageResponse<BusinessDebugConsultationItem> listConsultations(long current,
                                                                         long size,
                                                                         String keyword,
                                                                         String status) {
        PageRequest pageRequest = PageRequest.of(current, size);
        Page<AiUserConsultationLog> page = new Page<AiUserConsultationLog>(pageRequest.getCurrent(), pageRequest.getSize());
        LambdaQueryWrapper<AiUserConsultationLog> wrapper = new LambdaQueryWrapper<AiUserConsultationLog>()
            .eq(AiUserConsultationLog::getFgActive, "1")
            .eq(AiUserConsultationLog::getConsultationType, CONSULTATION_TYPE_VOICE)
            .orderByDesc(AiUserConsultationLog::getConsultationTime);
        if (StringUtils.hasText(keyword)) {
            String text = keyword.trim();
            wrapper.and(q -> q.like(AiUserConsultationLog::getConsultationId, text)
                .or()
                .like(AiUserConsultationLog::getPatientName, text)
                .or()
                .like(AiUserConsultationLog::getNaDoctor, text)
                .or()
                .like(AiUserConsultationLog::getNaOrg, text));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(AiUserConsultationLog::getStatus, status.trim());
        }
        Page<AiUserConsultationLog> result = consultationLogMapper.selectPage(page, wrapper);
        List<BusinessDebugConsultationItem> records = new ArrayList<BusinessDebugConsultationItem>();
        for (AiUserConsultationLog log : result.getRecords()) {
            records.add(toItem(log));
        }
        return new PageResponse<BusinessDebugConsultationItem>(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    public BusinessDebugContextVO context(String idRun) {
        AiUserConsultationLog log = loadRunLog(idRun);
        AiDevice device = resolveDevice(log);
        Map<String, Object> context = buildContext(log);
        BusinessDebugContextVO vo = new BusinessDebugContextVO();
        vo.setRun(toItem(log));
        vo.setContext(context);
        vo.setNodes(resolveNodes(log, device, context));
        return vo;
    }

    public BusinessDebugExecuteResponse execute(BusinessDebugExecuteRequest request) {
        if (request == null) {
            throw new BusinessException("调试请求不能为空");
        }
        AiUserConsultationLog log = loadRunLog(request.getIdRun());
        AiDevice device = loadDevice(log);
        NodeDefinition definition = findNode(request.getNodeCode());
        Map<String, Object> context = buildContext(log);
        Map<String, Object> input = request.getInputPayload() == null
            ? Collections.<String, Object>emptyMap()
            : request.getInputPayload();

        ResolvedPrompt resolvedPrompt = resolvePrompt(definition, log, device, context);
        String systemPrompt = firstNonBlank(trimToNull(request.getSystemPrompt()), resolvedPrompt.systemPrompt);
        String userPrompt = firstNonBlank(trimToNull(request.getUserPrompt()), renderUserPrompt(definition, log, device, context, input));
        if (!StringUtils.hasText(systemPrompt)) {
            throw new BusinessException("System Prompt 不能为空");
        }
        if (!StringUtils.hasText(userPrompt)) {
            throw new BusinessException("User Prompt 不能为空");
        }

        String traceId = "business-debug-" + UUID.randomUUID().toString().replace("-", "");
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setTraceId(traceId);
        chatRequest.setScene("business-workflow-debug-" + definition.nodeCode);
        chatRequest.setSourceModule("business_workflow_debug");
        chatRequest.setSessionId(log.getIdLog());
        chatRequest.setConsultationId(log.getConsultationId());
        chatRequest.setConfigProfile(normalizeProfile(firstNonBlank(request.getConfigProfile(), definition.defaultConfigProfile)));
        chatRequest.setTemperature(request.getTemperature() == null ? definition.defaultTemperature : request.getTemperature());
        chatRequest.setStream(Boolean.FALSE);
        chatRequest.setMessages(Arrays.asList(message("system", systemPrompt), message("user", userPrompt)));

        long start = System.currentTimeMillis();
        String content = aiProxyService.chat(device, chatRequest);
        long duration = System.currentTimeMillis() - start;

        BusinessDebugExecuteResponse response = new BusinessDebugExecuteResponse();
        response.setNodeCode(definition.nodeCode);
        response.setTraceId(traceId);
        response.setContent(content);
        response.setParsedJson(parseJsonQuietly(content));
        response.setDurationMs(duration);
        return response;
    }

    private List<BusinessDebugNodeVO> resolveNodes(AiUserConsultationLog log,
                                                   AiDevice device,
                                                   Map<String, Object> context) {
        List<BusinessDebugNodeVO> result = new ArrayList<BusinessDebugNodeVO>();
        for (NodeDefinition definition : voiceNodes) {
            ResolvedPrompt resolved = resolvePrompt(definition, log, device, context);
            BusinessDebugNodeVO node = new BusinessDebugNodeVO();
            node.setNodeCode(definition.nodeCode);
            node.setTitle(definition.title);
            node.setDescription(definition.description);
            node.setPromptCode(definition.promptCode);
            node.setPromptName(resolved.promptName);
            node.setPromptSource(resolved.promptSource);
            node.setVersionNum(resolved.versionNum);
            node.setDefaultConfigProfile(definition.defaultConfigProfile);
            node.setDefaultTemperature(definition.defaultTemperature);
            node.setSystemPrompt(resolved.systemPrompt);
            node.setUserPrompt(renderUserPrompt(definition, log, device, context, Collections.<String, Object>emptyMap()));
            node.setInputPresets(definition.inputPresets);
            result.add(node);
        }
        return result;
    }

    private ResolvedPrompt resolvePrompt(NodeDefinition definition,
                                         AiUserConsultationLog log,
                                         AiDevice device,
                                         Map<String, Object> context) {
        String regionId = device == null ? null : device.getIdRegion();
        PromptView prompt = promptService.resolveEffectivePrompt(definition.promptCode, log.getIdOrg(), regionId);
        ResolvedPrompt resolved = new ResolvedPrompt();
        resolved.promptName = prompt == null ? definition.title : prompt.getNaPrompt();
        resolved.promptSource = prompt == null ? "missing" : prompt.getSource();
        resolved.versionNum = prompt == null ? null : prompt.getVersionNum();
        resolved.systemPrompt = prompt == null ? "" : prompt.getSysPrompt();
        resolved.userTemplate = prompt == null ? "" : prompt.getUserTemplate();
        return resolved;
    }

    private String renderUserPrompt(NodeDefinition definition,
                                    AiUserConsultationLog log,
                                    AiDevice device,
                                    Map<String, Object> context,
                                    Map<String, Object> input) {
        ResolvedPrompt prompt = resolvePrompt(definition, log, device, context);
        String template = StringUtils.hasText(prompt.userTemplate) ? prompt.userTemplate : "{{input}}";
        Map<String, Object> values = new LinkedHashMap<String, Object>(context);
        values.put("input", stringify(firstNonNull(input.get("input"), input.get("currentInput"), context.get("speechText"))));
        values.put("upstreamOutput", stringify(firstNonNull(input.get("upstreamOutput"), "")));
        values.put("patientContext", stringify(context.get("patientContext")));
        values.put("speechText", stringify(context.get("speechText")));
        values.put("transcribedText", stringify(context.get("speechText")));
        values.put("consultationId", stringify(context.get("consultationId")));
        values.put("patientName", stringify(context.get("patientName")));
        values.put("doctorName", stringify(context.get("doctorName")));
        values.put("orgName", stringify(context.get("orgName")));
        String rendered = template;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", stringify(entry.getValue()));
        }
        return rendered;
    }

    private Map<String, Object> buildContext(AiUserConsultationLog log) {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("idRun", log.getIdLog());
        context.put("consultationId", log.getConsultationId());
        context.put("scene", SCENE_VOICE_CONSULTATION);
        context.put("patientName", firstNonBlank(log.getPatientName(), log.getPatientId()));
        context.put("patientGender", log.getPatientGender());
        context.put("patientAge", log.getPatientAge());
        context.put("doctorName", firstNonBlank(log.getNaDoctor(), log.getIdDoctor()));
        context.put("orgName", firstNonBlank(log.getNaOrg(), log.getIdOrg()));
        context.put("startedAt", log.getConsultationTime());
        context.put("speechText", log.getSpeechText());
        Map<String, Object> patientContext = new LinkedHashMap<String, Object>();
        patientContext.put("name", firstNonBlank(log.getPatientName(), log.getPatientId()));
        patientContext.put("gender", log.getPatientGender());
        patientContext.put("age", log.getPatientAge());
        patientContext.put("doctor", firstNonBlank(log.getNaDoctor(), log.getIdDoctor()));
        patientContext.put("org", firstNonBlank(log.getNaOrg(), log.getIdOrg()));
        context.put("patientContext", patientContext);
        context.put("firstSnapshot", parseJsonQuietly(log.getFirstSnapshotJson()));
        context.put("finalSnapshot", parseJsonQuietly(log.getFinalSnapshotJson()));
        context.put("selectionSnapshot", parseJsonQuietly(log.getSelectionJson()));
        context.put("changeSummary", parseJsonQuietly(log.getChangeSummaryJson()));
        return context;
    }

    private AiUserConsultationLog loadRunLog(String idRun) {
        if (!StringUtils.hasText(idRun)) {
            throw new BusinessException("就诊记录 ID 不能为空");
        }
        AiUserConsultationLog log = consultationLogMapper.selectById(idRun);
        if (log == null || !"1".equals(log.getFgActive())) {
            throw new BusinessException("就诊记录不存在");
        }
        if (!CONSULTATION_TYPE_VOICE.equals(log.getConsultationType())) {
            throw new BusinessException("当前版本仅支持语音接诊业务调试");
        }
        return log;
    }

    private AiDevice loadDevice(AiUserConsultationLog log) {
        AiDevice device = resolveDevice(log);
        if (device == null || !"1".equals(device.getFgActive())) {
            throw new BusinessException("原设备不存在或已停用，无法重放");
        }
        return device;
    }

    private AiDevice resolveDevice(AiUserConsultationLog log) {
        if (log == null || !StringUtils.hasText(log.getIdDevice())) {
            return null;
        }
        return deviceMapper.selectById(log.getIdDevice());
    }

    private BusinessDebugConsultationItem toItem(AiUserConsultationLog log) {
        BusinessDebugConsultationItem item = new BusinessDebugConsultationItem();
        item.setIdRun(log.getIdLog());
        item.setConsultationId(log.getConsultationId());
        item.setScene(SCENE_VOICE_CONSULTATION);
        item.setSceneName("语音接诊");
        item.setPatientName(firstNonBlank(log.getPatientName(), log.getPatientId()));
        item.setPatientGender(log.getPatientGender());
        item.setPatientAge(log.getPatientAge());
        item.setDoctorName(firstNonBlank(log.getNaDoctor(), log.getIdDoctor()));
        item.setOrgName(firstNonBlank(log.getNaOrg(), log.getIdOrg()));
        item.setStatus(log.getStatus());
        item.setStartedAt(log.getConsultationTime());
        item.setHasSpeechText(log.getHasSpeechText());
        return item;
    }

    private NodeDefinition findNode(String nodeCode) {
        if (!StringUtils.hasText(nodeCode)) {
            throw new BusinessException("调试节点不能为空");
        }
        for (NodeDefinition node : voiceNodes) {
            if (node.nodeCode.equals(nodeCode)) {
                return node;
            }
        }
        throw new BusinessException("暂不支持该业务节点");
    }

    private List<NodeDefinition> buildVoiceNodes() {
        List<NodeDefinition> nodes = new ArrayList<NodeDefinition>();
        nodes.add(new NodeDefinition("voice_transcript_calibration", "语音文本校准", "校准 ASR 文本、整理说话人、提炼临床事实", "voiceTranscriptCalibration", "fast", 0.1,
            Arrays.asList("原始语音文本", "患者/医生/机构上下文")));
        nodes.add(new NodeDefinition("medical_record_generation", "病历生成", "根据语音文本或校准结果生成门诊病历草稿", "medicalRecordGeneration", "default", 0.2,
            Arrays.asList("原始语音文本", "语音文本校准输出", "患者上下文")));
        nodes.add(new NodeDefinition("diagnosis_recommendation", "诊断推荐", "基于病历草稿与结构化事实生成候选诊断", "diagnosisRecommendation", "default", 0.2,
            Arrays.asList("病历生成输出", "语音文本校准输出")));
        nodes.add(new NodeDefinition("treatment_recommendation", "治疗方案推荐", "基于诊断和病历生成用药、非药物、随访建议", "treatmentRecommendation", "default", 0.2,
            Arrays.asList("诊断推荐输出", "病历生成输出", "患者上下文")));
        nodes.add(new NodeDefinition("examination_recommendation", "检查推荐", "推荐影像、心电、内镜等检查项目", "examinationRecommendation", "default", 0.2,
            Arrays.asList("诊断推荐输出", "病历生成输出")));
        nodes.add(new NodeDefinition("lab_test_recommendation", "检验推荐", "推荐实验室检验项目", "labTestRecommendation", "default", 0.2,
            Arrays.asList("诊断推荐输出", "病历生成输出")));
        nodes.add(new NodeDefinition("procedure_recommendation", "处置推荐", "推荐门诊处置、护理处理或转诊处置", "procedureRecommendation", "default", 0.2,
            Arrays.asList("诊断推荐输出", "病历生成输出")));
        nodes.add(new NodeDefinition("voice_safety_review", "安全复核", "复核病历、诊断和治疗建议中的安全底线问题", "voiceSafetyReview", "reviewer", 0.1,
            Arrays.asList("全部上游输出", "病历生成输出", "治疗方案推荐输出")));
        return nodes;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private Object parseJsonQuietly(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception ex) {
            return value;
        }
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof LocalDateTime) {
            return value.toString();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String normalizeProfile(String value) {
        if (!StringUtils.hasText(value)) {
            return "default";
        }
        String profile = value.trim().toLowerCase();
        if ("default".equals(profile) || "fast".equals(profile) || "reviewer".equals(profile)) {
            return profile;
        }
        throw new BusinessException("configProfile 只允许 default / fast / reviewer");
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static class NodeDefinition {
        private final String nodeCode;
        private final String title;
        private final String description;
        private final String promptCode;
        private final String defaultConfigProfile;
        private final Double defaultTemperature;
        private final List<String> inputPresets;

        NodeDefinition(String nodeCode,
                       String title,
                       String description,
                       String promptCode,
                       String defaultConfigProfile,
                       Double defaultTemperature,
                       List<String> inputPresets) {
            this.nodeCode = nodeCode;
            this.title = title;
            this.description = description;
            this.promptCode = promptCode;
            this.defaultConfigProfile = defaultConfigProfile;
            this.defaultTemperature = defaultTemperature;
            this.inputPresets = inputPresets;
        }
    }

    private static class ResolvedPrompt {
        private String promptName;
        private String promptSource;
        private String versionNum;
        private String systemPrompt;
        private String userTemplate;
    }
}
