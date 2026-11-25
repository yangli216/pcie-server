package com.regionalai.floatingball.server.modules.emrqc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.emrqc.config.EmrQcProperties;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class EmrQcAiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmrQcAiClient client = new EmrQcAiClient(
        objectMapper, mock(OutboundSecurityService.class), new EmrQcProperties());

    @Test
    void parseResultOrdersNineDimensionsAndCalculatesTotalOnServer() {
        ObjectNode payload = validPayload();
        List<EmrQcRubric.Spec> specs = EmrQcRubric.dimensions();
        ArrayNode reversed = payload.putArray("dimensions");
        for (int index = specs.size() - 1; index >= 0; index--) {
            EmrQcRubric.Spec spec = specs.get(index);
            reversed.addObject()
                .put("code", spec.getCode())
                .put("score", spec.getWeight())
                .put("summary", spec.getName());
        }
        payload.put("score", 1);
        ((ObjectNode) payload.get("findings").get(0)).put("ignored", true);

        EmrQcAiClient.Result result = parse(payload);

        assertEquals(100, result.getScore());
        assertEquals(100, EmrQcRubric.totalWeight());
        assertEquals(9, result.getDimensions().size());
        for (int index = 0; index < specs.size(); index++) {
            EmrQcAnalysisVO.Dimension dimension = result.getDimensions().get(index);
            assertEquals(specs.get(index).getCode(), dimension.getCode());
            assertEquals(specs.get(index).getWeight(), dimension.getWeight());
            assertEquals(specs.get(index).getWeight(), dimension.getScore());
        }
        assertEquals("其他", result.getFindings().get(0).getCategory());
        assertEquals(3, result.getFindings().get(0).getDeduction());
        assertEquals(Boolean.FALSE, result.getFindings().get(0).getIgnored());
    }

    @Test
    void parseResultRejectsMissingDuplicateUnknownAndInvalidScores() {
        ObjectNode missing = validPayload();
        ((ArrayNode) missing.get("dimensions")).remove(0);
        assertInvalid(missing);

        ObjectNode duplicate = validPayload();
        ((ArrayNode) duplicate.get("dimensions")).add(duplicate.get("dimensions").get(0).deepCopy());
        assertInvalid(duplicate);

        ObjectNode unknown = validPayload();
        ((ArrayNode) unknown.get("dimensions")).addObject().put("code", "unknown").put("score", 1);
        assertInvalid(unknown);

        ObjectNode outOfRange = validPayload();
        ((ObjectNode) outOfRange.get("dimensions").get(0)).put("score", 11);
        assertInvalid(outOfRange);

        ObjectNode fractional = validPayload();
        ((ObjectNode) fractional.get("dimensions").get(0)).put("score", 9.5);
        assertInvalid(fractional);
    }

    @Test
    void parseResultRejectsMissingFractionalOutOfRangeAndUnknownFindingDeductions() {
        ObjectNode missing = validPayload();
        ((ObjectNode) missing.get("findings").get(0)).remove("deduction");
        assertInvalid(missing);

        ObjectNode fractional = validPayload();
        ((ObjectNode) fractional.get("findings").get(0)).put("deduction", 2.5);
        assertInvalid(fractional);

        ObjectNode negative = validPayload();
        ((ObjectNode) negative.get("findings").get(0)).put("deduction", -1);
        assertInvalid(negative);

        ObjectNode outOfRange = validPayload();
        ((ObjectNode) outOfRange.get("findings").get(0)).put("deduction", 6);
        assertInvalid(outOfRange);

        ObjectNode unknownCategory = validPayload();
        ((ObjectNode) unknownCategory.get("findings").get(0)).put("category", "未知分类");
        assertInvalid(unknownCategory);
    }

    @Test
    void promptsAiToUseCrossSectionEvidenceAndDistinguishMaskedFromMissing() {
        List<java.util.Map<String, Object>> messages = ReflectionTestUtils.invokeMethod(client, "messages", "主诉：外阴瘙痒1个月\n婚育史：已婚已育");
        String instructions = (String) messages.get(0).get("content");
        org.assertj.core.api.Assertions.assertThat(instructions).contains("脱敏占位符", "上下文未提供表示未知", "综合所有章节", "婚育史", "复查", "1个月", "月经史单独成章", "出生年月、联系电话、工作单位和地址", "明确不检查、不提示缺失、不扣分", "结构化章节可能不包含预览中的全部内容");
        org.assertj.core.api.Assertions.assertThat((String) messages.get(1).get("content")).contains("外阴瘙痒1个月", "已婚已育");
    }

    @Test
    void refusesToAnalyzeTruncatedInput() {
        char[] chars = new char[24001];
        java.util.Arrays.fill(chars, '病');
        assertThrows(ServiceUnavailableException.class,
            () -> ReflectionTestUtils.invokeMethod(client, "messages", new String(chars)));
    }

    @Test
    void diagnosisRuleKeepsItsStableCodeAndRejectsWrongDeductionCategoryOrUnknownCode() {
        ObjectNode payload = validPayload();
        ObjectNode finding = (ObjectNode) payload.get("findings").get(0);
        finding.put("ruleCode", EmrQcChiefComplaintRules.DIAGNOSIS).put("category", "主诉").put("deduction", 1);
        assertEquals(EmrQcChiefComplaintRules.DIAGNOSIS, parse(payload).getFindings().get(0).getRuleCode());
        finding.put("deduction", 2); assertInvalid(payload);
        finding.put("deduction", 1).put("category", "现病史"); assertInvalid(payload);
        finding.put("category", "主诉").put("ruleCode", "R-CHIEF-UNKNOWN"); assertInvalid(payload);
    }

    @Test
    void fakeSupplierResponseExercisesRealRequestPromptAndDiagnosisParser() throws Exception {
        com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties properties =
            new com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties();
        properties.setAllowAllHosts(true); properties.setAllowPrivateNetwork(true); properties.setAllowInsecureHttp(true);
        EmrQcAiClient realClient = new EmrQcAiClient(objectMapper, new OutboundSecurityService(properties), new EmrQcProperties());
        org.springframework.test.web.client.MockRestServiceServer server = org.springframework.test.web.client.MockRestServiceServer.createServer(
            (org.springframework.web.client.RestTemplate) ReflectionTestUtils.getField(realClient, "restTemplate"));
        ObjectNode payload = validPayload();
        ((ObjectNode) payload.get("findings").get(0)).put("ruleCode", EmrQcChiefComplaintRules.DIAGNOSIS)
            .put("category", "主诉").put("deduction", 1).put("excerpt", "阴道炎1周，要求配药")
            .put("suggestion", "改为外阴不适1周");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("choices").addObject().putObject("message").put("content", payload.toString());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://127.0.0.1:9999/chat/completions"))
            .andExpect(request -> {
                JsonNode body = objectMapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                String system = body.path("messages").get(0).path("content").asText();
                org.assertj.core.api.Assertions.assertThat(system).contains("不要求完全匹配PHIS", "现病史为空", "不是豁免条件", "无不适/无症状", "不得在findings再次报告", "deduction=1");
                org.assertj.core.api.Assertions.assertThat(body.path("messages").get(1).path("content").asText()).contains("外阴不适1周").doesNotContain("<p>");
            }).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(response.toString(), org.springframework.http.MediaType.APPLICATION_JSON));
        com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig config = new com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig();
        config.setBaseUrl("http://127.0.0.1:9999"); config.setApiKey("synthetic-test-key"); config.setModel("fake");
        EmrQcAiClient.Result result = realClient.analyze(config, "主诉：阴道炎1周，要求配药\n现病史：外阴不适1周");
        server.verify();
        assertEquals(EmrQcChiefComplaintRules.DIAGNOSIS, result.getFindings().get(0).getRuleCode());
        assertEquals(1, result.getFindings().get(0).getDeduction());
        assertEquals("改为外阴不适1周", result.getFindings().get(0).getSuggestion());
    }

    private ObjectNode validPayload() {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode dimensions = root.putArray("dimensions");
        for (EmrQcRubric.Spec spec : EmrQcRubric.dimensions()) {
            dimensions.addObject()
                .put("code", spec.getCode())
                .put("score", spec.getWeight())
                .put("summary", spec.getName());
        }
        root.putArray("findings").addObject()
            .put("severity", "low")
            .put("category", "其他")
            .put("issue", "测试问题")
            .put("suggestion", "测试建议")
            .put("deduction", 3);
        return root;
    }

    private EmrQcAiClient.Result parse(JsonNode payload) {
        return ReflectionTestUtils.invokeMethod(client, "parseResult", payload.toString());
    }

    private void assertInvalid(ObjectNode payload) {
        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class,
            () -> parse(payload));
        assertEquals("EMR-QC-UNAVAILABLE", exception.getCode());
    }
}
