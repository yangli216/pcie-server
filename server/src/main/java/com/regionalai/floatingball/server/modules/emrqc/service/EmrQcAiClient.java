package com.regionalai.floatingball.server.modules.emrqc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.emrqc.config.EmrQcProperties;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EmrQcAiClient {

    private static final Logger log = LoggerFactory.getLogger(EmrQcAiClient.class);
    private static final int MAX_INPUT_CHARS = 24000;
    private static final int MAX_EXCERPT_CHARS = 500;

    private final ObjectMapper objectMapper;
    private final OutboundSecurityService outboundSecurityService;
    private final RestTemplate restTemplate;

    public EmrQcAiClient(ObjectMapper objectMapper,
                         OutboundSecurityService outboundSecurityService,
                         EmrQcProperties properties) {
        this.objectMapper = objectMapper;
        this.outboundSecurityService = outboundSecurityService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = Math.max(1000, properties.getAnalysisTimeoutSeconds() * 1000);
        factory.setConnectTimeout(Math.min(timeout, 30000));
        factory.setReadTimeout(timeout);
        this.restTemplate = new RestTemplate(factory);
    }

    public Result analyze(ResolvedAiConfig config, String maskedText) {
        if (config == null || !StringUtils.hasText(config.getBaseUrl())
            || !StringUtils.hasText(config.getApiKey()) || !StringUtils.hasText(config.getModel())) {
            throw new ServiceUnavailableException("EMR-QC-UNAVAILABLE", "未找到可用的 AI 质控配置，请联系管理员");
        }
        String endpoint = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("model", config.getModel());
        payload.put("messages", messages(maskedText));
        payload.put("stream", Boolean.FALSE);
        payload.put("temperature", 0.2);
        payload.put("enable_thinking", Boolean.FALSE);
        OutboundSecurityService.OutboundCall call =
            outboundSecurityService.acquireHttp(endpoint, "AI病历质控");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(config.getApiKey());

        long startedAt = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                call.getUrl(), HttpMethod.POST, new HttpEntity<Map<String, Object>>(payload, headers), String.class);
            call.success();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ServiceUnavailableException("EMR-QC-UNAVAILABLE",
                    "AI 质控服务返回 HTTP " + response.getStatusCodeValue());
            }
            JsonNode body = objectMapper.readTree(response.getBody());
            String content = extractContent(body);
            Result result = parseResult(content);
            for (EmrQcAnalysisVO.Finding finding : result.getFindings()) {
                if (StringUtils.hasText(finding.getExcerpt())
                    && (maskedText == null || !maskedText.contains(finding.getExcerpt()))) {
                    finding.setExcerpt(null);
                }
            }
            log.info("emr qc ai succeeded. model={}, durationMs={}", config.getModel(), System.currentTimeMillis() - startedAt);
            return result;
        } catch (ServiceUnavailableException ex) {
            call.failure(ex);
            throw ex;
        } catch (RestClientException ex) {
            call.failure(ex);
            log.warn("emr qc ai call failed. model={}", config.getModel(), ex);
            throw new ServiceUnavailableException("EMR-QC-UNAVAILABLE", "AI 质控服务调用超时或网络异常，请稍后重试");
        } catch (Exception ex) {
            call.failure(ex);
            log.warn("emr qc ai response parsing failed. model={}", config.getModel(), ex);
            throw new ServiceUnavailableException("EMR-QC-UNAVAILABLE", "AI 质控服务返回内容无法解析，请稍后重试");
        }
    }

    private List<Map<String, Object>> messages(String maskedText) {
        String content = maskedText == null ? "" : maskedText;
        if (content.length() > MAX_INPUT_CHARS) {
            throw new ServiceUnavailableException("EMR-QC-UNAVAILABLE", "病历内容过长，无法完整质控，请联系管理员");
        }
        Map<String, Object> system = new LinkedHashMap<String, Object>();
        system.put("role", "system");
        system.put("content", "你是门诊病历书写质控专家。请严格按以下门诊病历评分标准评审，只输出一个 JSON 对象，不要输出 Markdown 或解释性文字。"
            + "评分标准（各维度得分为 0 至该维度满分的整数，按扣分项扣分，最低 0 分）："
            + "1.patient_info 患者基础信息，满分10：病历应有患者姓名、就诊号/病案号、性别、出生年月、身份证号、联系电话、工作单位/地址、就诊时间、科别、食物/药物过敏史等，缺项每项扣2分；缺食物/药物过敏史扣5分；无就诊时间及科室扣2分。"
            + "2.chief_complaint 主诉，满分5：记录主要症状、体征及持续时间；明确诊断的复诊、随访可以诊断代替症状体征。"
            + "3.present_illness 现病史，满分20：记录本次起病的主要症状、体征（10分）；记录发病以来主要诊治经过及结果（10分）。"
            + "4.past_history 既往史，满分10：初诊应记录重要既往病史、传染病史、手术史、月经史、生育史、家族史、长期用药史等；育龄期妇女无月经史扣5分。"
            + "5.physical_exam 查体与辅助检查，满分15：记录重要辅助检查结果、阳性体征和必要阴性体征，能支持疾病诊断。"
            + "6.diagnosis 诊断，满分10：规范书写疾病诊断。"
            + "7.treatment 诊疗措施，满分20：检查治疗项目明确、规范（10分）；药品使用规范，超说明书用药应说明（5分）；有复诊建议（5分）。"
            + "8.writing 病历书写基本要求，满分5：字迹清晰可辨，病历修改有医师签名及修改时间；字迹无法辨认扣2分，医师未签名扣3分，无修改时间扣2分。"
            + "9.other 其他，满分5：仅急诊病历适用，就诊时间未具体到分钟扣2分；急诊病人无T、P、R、BP生命体征记录扣3分。普通门诊不得因未记录急诊项目扣分。"
            + "字段格式：{\"dimensions\":[{\"code\":\"上述9个维度code之一\",\"score\":该维度得分整数,\"summary\":\"简要说明扣分原因\"}],"
            + "\"findings\":[{\"severity\":\"high|medium|low|info\",\"category\":\"对应维度名称\","
            + "\"sectionName\":\"章节名\",\"excerpt\":\"原文摘录\",\"issue\":\"问题\",\"suggestion\":\"修改建议\","
            + "\"deduction\":该问题扣分整数}]}。"
            + "dimensions 必须完整包含 9 个维度且不得重复，不需要输出总分，总分由服务端求和。"
            + "findings 的 category 必须是上述维度名称，deduction 为0至对应维度满分的整数；同一问题只列一次，服务端按有效问题扣分计算维度分。"
            + "请综合所有章节和就诊上下文判断，不要仅因信息出现在不同章节而判缺失。脱敏占位符和已提供（已脱敏）表示信息已提供，不代表缺失；上下文未提供表示未知，不可认定病历缺失。"
            + "既往史中的过敏史、婚育史中的生育情况、注意事项中的复查安排均应纳入判断；主诉的1个月、一个月等均为持续时间。不要要求病历使用特定字段名称或位置。"
            + "仅对有依据且符合评分标准的问题扣分；不确定的情况不要判为缺失或扣分。不得编造病历中不存在的检查或诊断，excerpt 必须来自给定文本。");

        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "user");
        user.put("content", "以下是已脱敏的门诊病历章节文本，请完成质控：\n" + content);

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(system);
        messages.add(user);
        return messages;
    }

    private String extractContent(JsonNode body) {
        JsonNode choices = body == null ? null : body.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode message = choices.get(0).get("message");
            if (message != null && message.hasNonNull("content")) {
                return message.get("content").asText();
            }
            if (choices.get(0).hasNonNull("text")) {
                return choices.get(0).get("text").asText();
            }
        }
        throw new ServiceUnavailableException("EMR-QC-UNAVAILABLE", "AI 质控服务未返回有效结果，请稍后重试");
    }

    private Result parseResult(String content) {
        if (!StringUtils.hasText(content)) {
            throw invalid();
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw invalid();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(content.substring(start, end + 1));
        } catch (Exception ex) {
            throw invalid();
        }
        if (root == null || !root.isObject()) {
            throw invalid();
        }
        Result result = new Result();

        Map<String, EmrQcAnalysisVO.Dimension> dimensions = new LinkedHashMap<String, EmrQcAnalysisVO.Dimension>();
        JsonNode dimensionNodes = root.get("dimensions");
        if (dimensionNodes == null || !dimensionNodes.isArray()) {
            throw invalid();
        }
        for (JsonNode node : dimensionNodes) {
            String code = text(node, "code");
            EmrQcRubric.Spec spec = code == null ? null : EmrQcRubric.spec(code);
            if (spec == null || dimensions.containsKey(code)) {
                throw invalid();
            }
            Integer score = score(node.get("score"), spec.getWeight());
            if (score == null) {
                throw invalid();
            }
            EmrQcAnalysisVO.Dimension dimension = EmrQcRubric.dimension(code, score,
                truncate(text(node, "summary"), 200));
            dimensions.put(code, dimension);
        }
        if (dimensions.size() != EmrQcRubric.dimensions().size()) {
            throw invalid();
        }
        List<EmrQcAnalysisVO.Dimension> orderedDimensions = new ArrayList<EmrQcAnalysisVO.Dimension>();
        int totalScore = 0;
        for (EmrQcRubric.Spec spec : EmrQcRubric.dimensions()) {
            EmrQcAnalysisVO.Dimension dimension = dimensions.get(spec.getCode());
            orderedDimensions.add(dimension);
            totalScore += dimension.getScore();
        }
        result.setDimensions(orderedDimensions);
        result.setScore(totalScore);

        JsonNode findingNodes = root.get("findings");
        if (findingNodes == null || !findingNodes.isArray()) {
            throw invalid();
        }
        List<EmrQcAnalysisVO.Finding> findings = new ArrayList<EmrQcAnalysisVO.Finding>();
        for (JsonNode node : findingNodes) {
            String issue = text(node, "issue");
            String severity = text(node, "severity");
            if (!StringUtils.hasText(issue) || !isSeverity(severity)) {
                throw invalid();
            }
            String category = defaultText(text(node, "category"), EmrQcRubric.name(EmrQcRubric.OTHER));
            EmrQcRubric.Spec categorySpec = EmrQcRubric.specByCategory(category);
            Integer deduction = categorySpec == null ? null : score(node.get("deduction"), categorySpec.getWeight());
            if (categorySpec == null || deduction == null) {
                throw invalid();
            }
            EmrQcAnalysisVO.Finding finding = new EmrQcAnalysisVO.Finding();
            finding.setSeverity(severity);
            finding.setCategory(categorySpec.getName());
            finding.setRuleCode(null);
            finding.setSectionName(truncate(text(node, "sectionName"), 128));
            finding.setExcerpt(truncate(text(node, "excerpt"), MAX_EXCERPT_CHARS));
            finding.setIssue(truncate(issue, 1000));
            finding.setSuggestion(truncate(text(node, "suggestion"), 1000));
            finding.setDeduction(deduction);
            finding.setIgnored(Boolean.FALSE);
            findings.add(finding);
        }
        result.setFindings(findings);
        return result;
    }

    private ServiceUnavailableException invalid() {
        return new ServiceUnavailableException("EMR-QC-UNAVAILABLE", "AI 质控结果结构不符合要求，请稍后重试");
    }

    private Integer score(JsonNode node, int max) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        int value = node.intValue();
        if (value < 0 || value > max) {
            return null;
        }
        return value;
    }

    private boolean isSeverity(String value) {
        return "high".equals(value) || "medium".equals(value) || "low".equals(value) || "info".equals(value);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public static class Result {
        private Integer score;
        private List<EmrQcAnalysisVO.Dimension> dimensions;
        private List<EmrQcAnalysisVO.Finding> findings;

        public Integer getScore() {
            return score;
        }

        public void setScore(Integer score) {
            this.score = score;
        }

        public List<EmrQcAnalysisVO.Dimension> getDimensions() {
            return dimensions;
        }

        public void setDimensions(List<EmrQcAnalysisVO.Dimension> dimensions) {
            this.dimensions = dimensions;
        }

        public List<EmrQcAnalysisVO.Finding> getFindings() {
            return findings;
        }

        public void setFindings(List<EmrQcAnalysisVO.Finding> findings) {
            this.findings = findings;
        }
    }
}
