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
            + "1.patient_info 患者基础信息，满分10：根据可用信息评估姓名、就诊号/病案号、性别、身份证号、就诊时间、科别和食物/药物过敏史；出生年月、联系电话、工作单位和地址由患者档案维护，明确不检查、不提示缺失、不扣分。其他项仅在有充分依据确定缺失时按标准扣分。"
            + "过敏史新增完整度规则仅检查【过敏史固定字段证据】中的填写内容（证据通常来自既往史），不扩展家族史、个人史等其他病史。"
            + "字段状态明确为空时，保留R06空字段扣5分，使用ruleCode=R-ALLERGY-MISSING、category=患者基础信息、deduction=5，提示补充药物与食物过敏史。"
            + "未识别字段表示未知，不能当作空字段或仅凭其他病史的笼统表述触发本规则。"
            + "字段已填写时，必须同时覆盖药物与食物两类；‘否认过敏史’等笼统写法或只写一类均为表述不完整，"
            + "使用ruleCode=R-ALLERGY-INCOMPLETE、category=患者基础信息、deduction=1。两类均未体现仍只扣1分，不能再叠加空字段5分。"
            + "笼统阴性表述建议‘否认药物及食物过敏史’；‘青霉素过敏’或‘否认药物过敏史’须提示补充食物过敏情况，"
            + "仅写食物时提示补充药物过敏情况。不得把已记录的阳性过敏建议改为否认。"
            + "药物、药品、抗菌药物和具体药名属于药物类，食物、食品和具体食物属于食物类；需要理解语义，不只按字面类别词匹配。"
            + "‘否认药物及食物过敏史’‘否认食品、药物过敏史’‘青霉素过敏，否认食物过敏史’‘青霉素及海鲜过敏’均覆盖两类，不扣本项。"
            + "字段标题本身不能代替填写内容覆盖类别；两个过敏史规则互斥，整份病历最多列一条，不同时扣5+1，不重复列入既往史维度或其他问题。"
            + "2.chief_complaint 主诉，满分5：记录主要症状、体征及持续时间。"
            + "新增诊断名代替症状规则（R-CHIEF-DIAGNOSIS）：必须联合主诉、现病史和当前就诊上下文判断。"
            + "现病史已描述具体症状，主诉仍使用诊断名称替代症状时扣1分，不要求完全匹配PHIS可开具诊断字典。"
            + "例如主诉‘阴道炎1周，要求配药’、现病史‘外阴不适1周’，应提示改为‘外阴不适1周’；"
            + "‘上呼吸道感染3天’中的上呼吸道感染是诊断而非症状，应结合现病史提示改为咳嗽、咽痛等实际记录的症状，不得编造症状。"
            + "仅当现病史明确记录‘无不适/无症状’，且上下文为明确诊断复诊或单纯配药随访时可使用诊断名，"
            + "如‘高血压复诊’与‘无不适，继续配药’不扣该项；‘要求配药’本身不是豁免条件。"
            + "现病史为空时本项不命中，只按原现病史规则判断缺项；主诉已提炼具体症状时，不能仅因同时含诊断名而扣本项。"
            + "不额外查询历史就诊，依据不足不扣本项。该项必须使用ruleCode=R-CHIEF-DIAGNOSIS、category=主诉、deduction=1，最多列一次。"
            + "主诉加号（半角+或全角＋、任意位置）与超20字规则由服务端确定性检查并各扣1分，"
            + "你不得在findings再次报告或变相合并报告这两条问题，也不得因这两项另扣书写等维度；其余主诉规则保持不变。"
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
            + "\"deduction\":该问题扣分整数,\"ruleCode\":null}]}。诊断代替症状项将ruleCode设为字符串R-CHIEF-DIAGNOSIS；"
            + "过敏史空字段/完整度项分别使用R-ALLERGY-MISSING/R-ALLERGY-INCOMPLETE，其余使用JSON null。"
            + "dimensions 必须完整包含 9 个维度且不得重复，不需要输出总分，总分由服务端求和。"
            + "findings 的 category 必须是上述维度名称，deduction 为0至对应维度满分的整数；同一问题只列一次，服务端按有效问题扣分计算维度分。"
            + "请综合所有章节和就诊上下文判断，不要仅因信息出现在不同章节而判缺失。脱敏占位符和已提供（已脱敏）表示信息已提供，不代表缺失；上下文未提供表示未知，不可认定病历缺失。"
            + "既往史中的过敏史、独立月经史章节中的月经情况、婚育史中的生育情况、注意事项中的复查安排均应纳入判断；月经史单独成章与写在既往史中等效，不能因位置不同判为缺失或扣分；主诉的1个月、一个月等均为持续时间。不要要求病历使用特定字段名称或位置。"
            + "结构化章节可能不包含预览中的全部内容，结合 PHIS 病历预览纯文字评估；同一章节内容不一致时以安全预览可见内容为准。仅因某内容未出现在结构化章节中不能认定病历缺失；缺少足够依据时不要提示缺失或扣分。仅对有依据且符合评分标准的问题扣分；不确定的情况不要判为缺失或扣分。不得编造病历中不存在的检查或诊断，excerpt 必须来自给定文本。");

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
        String allergyRule = null;
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
            String ruleCode = text(node, "ruleCode");
            if (ruleCode != null) {
                boolean chief = EmrQcChiefComplaintRules.isRule(ruleCode)
                    && EmrQcRubric.CHIEF_COMPLAINT.equals(categorySpec.getCode()) && deduction == 1;
                if (!chief && !EmrQcAllergyRules.validFinding(ruleCode, categorySpec.getCode(), deduction)) throw invalid();
                if (EmrQcAllergyRules.isRule(ruleCode)) {
                    if (allergyRule != null && !allergyRule.equals(ruleCode)) throw invalid();
                    if (allergyRule != null) continue;
                    allergyRule = ruleCode;
                }
            }
            EmrQcAnalysisVO.Finding finding = new EmrQcAnalysisVO.Finding();
            finding.setSeverity(severity);
            finding.setCategory(categorySpec.getName());
            finding.setRuleCode(ruleCode);
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
