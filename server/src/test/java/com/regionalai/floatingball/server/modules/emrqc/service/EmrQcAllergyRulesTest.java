package com.regionalai.floatingball.server.modules.emrqc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmrQcAllergyRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"过敏史", "食品、药品过敏史", "药物及食物过敏史", "食物/药物过敏史"})
    void recognizesFixedFieldLabelsAndPreservesExplicitEmpty(String name) {
        ArrayNode pages = mapper.createArrayNode();
        pages.addObject().put("ARTICLE", "既往史").putArray("eles").addObject().put("NAME", name).put("TEXT", "");
        assertEquals("", EmrQcChiefComplaintRules.structuredField(mapper, pages.toString(), "过敏史"));
        String preview = "<p class='tag-article'><b class='tag-article-title'>" + name + "：</b>否认过敏史</p>";
        EmrQcHtmlPreview.Projection result = EmrQcHtmlPreview.project(preview, new PhisEmrRecord(), Collections.emptyList(), pages.toString());
        assertEquals("否认过敏史", result.allergyHistory);
    }

    @Test
    void familyAndPersonalHistoryAreNotAllergyFieldEvidenceEvenWithNestedLabel() {
        for (String section : new String[]{"家族史", "个人史"}) {
            ArrayNode pages = mapper.createArrayNode();
            pages.addObject().put("ARTICLE", section).putArray("eles").addObject().put("NAME", "过敏史").put("TEXT", "否认过敏史");
            assertEquals(null, EmrQcChiefComplaintRules.structuredField(mapper, pages.toString(), "过敏史"));
            String html = "<section class='tag-article'><b class='tag-article-title'>" + section + "：</b><p>过敏史：否认过敏史</p></section>";
            assertEquals(null, EmrQcHtmlPreview.project(html, new PhisEmrRecord(), Collections.emptyList(), null).allergyHistory);
        }
    }

    @Test
    void visiblePreviewOverridesEmptyTemplateAndRemovesExecutableContent() {
        String html = "<p>过敏史：青霉素过敏<script>secret</script><span hidden>海鲜过敏</span></p>";
        EmrQcHtmlPreview.Projection result = EmrQcHtmlPreview.project(html, new PhisEmrRecord(), Collections.emptyList(), null);
        assertEquals("青霉素过敏", result.allergyHistory);
        assertThat(EmrQcAllergyRules.aiEvidence(result.allergyHistory, new PhisEmrRecord(), new SensitiveDataMasker()))
            .contains("已填写", "青霉素过敏").doesNotContain("secret", "海鲜", "<");
    }

    @Test
    void distinguishesUnknownBlankAndFilledWithoutClassifyingClinicalText() {
        assertEquals(null, EmrQcAllergyRules.filled(null));
        assertEquals(Boolean.FALSE, EmrQcAllergyRules.filled(" \n\u00a0"));
        assertEquals(Boolean.TRUE, EmrQcAllergyRules.filled("否认过敏史"));
        assertEquals(Boolean.TRUE, EmrQcAllergyRules.filled("青霉素及海鲜过敏"));
        assertThat(EmrQcAllergyRules.aiEvidence(null, new PhisEmrRecord(), new SensitiveDataMasker())).contains("未知，不代表未填写");
    }

    @Test
    void dropsUnsupportedFieldFindingsAndRejectsStatusContradiction() {
        EmrQcAnalysisVO.Finding finding = new EmrQcAnalysisVO.Finding();
        finding.setRuleCode(EmrQcAllergyRules.MISSING);
        assertThat(EmrQcAllergyRules.withEvidence(Collections.singletonList(finding), null)).isEmpty();
        assertThrows(ServiceUnavailableException.class, () -> EmrQcAllergyRules.withEvidence(Collections.singletonList(finding), true));
        assertThat(EmrQcAllergyRules.withEvidence(Collections.singletonList(finding), false)).containsExactly(finding);
        finding.setRuleCode(EmrQcAllergyRules.INCOMPLETE);
        assertThrows(ServiceUnavailableException.class, () -> EmrQcAllergyRules.withEvidence(Collections.singletonList(finding), false));
        assertThat(EmrQcAllergyRules.withEvidence(Collections.singletonList(finding), true)).containsExactly(finding);
    }

    @Test
    void redactsEvidenceBeforeSendingItToAi() {
        PhisEmrRecord record = new PhisEmrRecord(); record.setPatientName("张三");
        assertThat(EmrQcAllergyRules.aiEvidence("张三青霉素过敏，电话13800138000", record, new SensitiveDataMasker()))
            .contains("青霉素过敏", "[姓名]").doesNotContain("张三", "13800138000");
    }

    @Test
    void extractsUnlabelledAllergyStatementsFromPastHistoryOnly() {
        String phisPast = "平素体健；高血压病史标志高血压，糖尿病病史标志糖尿病，否认肝炎史，否认结核史，否认疟疾史，否认其他传染病史；"
            + "否认心脏病史；否认脑血管病史；否认肺部疾病史；否认肾脏疾病史；否认其他重大疾病史；否认手术史；否认外伤史；否认输血史；否认过敏史。";
        assertEquals("否认过敏史", EmrQcAllergyRules.fromPastHistory(phisPast));
        assertEquals("否认药物及食物过敏史", EmrQcAllergyRules.fromPastHistory("既往体健，否认高血压、糖尿病等慢性病史，否认药物及食物过敏史。"));
        assertEquals("否认过敏史", EmrQcAllergyRules.fromPastHistory("平素体健，否认肝炎史，否认过敏史，否认手术史。"));
        assertEquals("青霉素过敏，否认食物过敏史", EmrQcAllergyRules.fromPastHistory("平素体健；青霉素过敏，否认食物过敏史；否认手术史。"));
        assertEquals("药物过敏史：无", EmrQcAllergyRules.fromPastHistory("平素体健；药物过敏史：无。"));
        assertEquals("", EmrQcAllergyRules.fromPastHistory("平素体健，过敏史："));
        assertEquals(null, EmrQcAllergyRules.fromPastHistory("平素体健；否认手术史；否认外伤史。"));

        String html = "<p class='tag-article'><b class='tag-article-title'>既往史：</b><span>" + phisPast + "</span></p>"
            + "<p class='tag-article'><b class='tag-article-title'>个人史：</b>否认吸毒史。</p>"
            + "<p class='tag-article'><b class='tag-article-title'>家族史：</b>否认家族史。</p>";
        EmrQcHtmlPreview.Projection projection = EmrQcHtmlPreview.project(html, new PhisEmrRecord(), Collections.emptyList(), null);
        assertEquals(null, projection.allergyHistory);
        assertEquals("否认过敏史", EmrQcAllergyRules.fromPastHistory(projection.pastHistory));

        String multiParagraphHtml = "<div class='tag-article'><b class='tag-article-title'>既往史：</b><p>否认肝炎史</p><p>否认过敏史</p></div>";
        EmrQcHtmlPreview.Projection multi = EmrQcHtmlPreview.project(multiParagraphHtml, new PhisEmrRecord(), Collections.emptyList(), null);
        assertEquals("否认过敏史", EmrQcAllergyRules.fromPastHistory(multi.pastHistory));
    }

    @Test
    void structuredFallbackSupportsDescNameExtendedLabelsAndMultiElementPastHistory() {
        ArrayNode pages = mapper.createArrayNode();
        pages.addObject().put("ARTICLE", "既往史").putArray("eles").addObject().put("DESCNAME", "食品、药品过敏史").put("TEXT", "海鲜过敏");
        assertEquals("海鲜过敏", EmrQcChiefComplaintRules.structuredField(mapper, pages.toString(), "过敏史"));

        ArrayNode flagged = mapper.createArrayNode();
        flagged.addObject().put("ARTICLE", "既往史").putArray("eles").addObject().put("NAME", "过敏史标志").put("TEXT", "否认过敏史");
        assertEquals("否认过敏史", EmrQcChiefComplaintRules.structuredField(mapper, flagged.toString(), "过敏史"));

        ArrayNode combined = mapper.createArrayNode();
        ArrayNode eles = combined.addObject().put("ARTICLE", "既往史").putArray("eles");
        eles.addObject().put("NAME", "传染病").put("TEXT", "否认肝炎史");
        eles.addObject().put("NAME", "其他").put("TEXT", "否认过敏史");
        assertEquals("否认过敏史", EmrQcAllergyRules.fromPastHistory(
            EmrQcChiefComplaintRules.structuredField(mapper, combined.toString(), "既往史")));
    }
}
