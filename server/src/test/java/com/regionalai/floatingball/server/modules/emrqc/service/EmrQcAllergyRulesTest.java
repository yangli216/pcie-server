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
}
