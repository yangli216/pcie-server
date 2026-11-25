package com.regionalai.floatingball.server.modules.emrqc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmrQcChiefComplaintRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "反复咳嗽20+天|1|0", "咳嗽+发热3天|1|0", "咳嗽＋发热3天|1|0",
        "咳嗽++发热＋3天|1|0", "高血压复诊|0|0", "外阴不适1周|0|0",
        "咳嗽咳痰伴气促反复发作20余天，门诊治疗|0|0",
        "咳嗽咳痰伴气促反复发作20+天，门诊治疗|1|0",
        "咳嗽咳痰伴气促反复发作20余天，门诊治疗后|0|1",
        "咳嗽咳痰伴气促反复发作20+天，门诊治疗后|1|1"
    })
    void independentlyChecksBothMechanicalRules(String complaint, int plus, int length) {
        List<EmrQcAnalysisVO.Finding> findings = evaluate(complaint);
        assertEquals(plus, findings.stream().filter(f -> EmrQcChiefComplaintRules.PLUS.equals(f.getRuleCode())).count());
        assertEquals(length, findings.stream().filter(f -> EmrQcChiefComplaintRules.LENGTH.equals(f.getRuleCode())).count());
        assertThat(findings).allSatisfy(f -> {
            assertEquals(1, f.getDeduction());
            assertEquals("主诉", f.getCategory());
            assertEquals(Boolean.FALSE, f.getIgnored());
        });
    }

    @Test
    void countsPunctuationDigitsLettersAndUnicodeCharactersAtBoundary() {
        String twenty = String.join("", Collections.nCopies(15, "咳")) + "，20Ab";
        assertThat(evaluate(twenty)).isEmpty();
        assertThat(evaluate(twenty + "。" )).extracting(EmrQcAnalysisVO.Finding::getRuleCode)
            .containsExactly(EmrQcChiefComplaintRules.LENGTH);
        assertThat(evaluate(twenty + "。！？").get(0).getIssue()).contains("23字");
        assertThat(evaluate(String.join("", Collections.nCopies(20, "𠮷")))).isEmpty();
    }

    @Test
    void givesSpecificDurationSuggestionAndMasksExcerptBeforeSaving() {
        PhisEmrRecord record = new PhisEmrRecord(); record.setPatientName("张三");
        List<EmrQcAnalysisVO.Finding> findings = EmrQcChiefComplaintRules.evaluate("张三反复咳嗽20＋天", record, masker);
        assertThat(findings.get(0).getSuggestion()).contains("20天");
        assertThat(findings.get(0).getExcerpt()).doesNotContain("张三").contains("[姓名]");
        // A longer redaction token must not turn a 20-character complaint into a length finding.
        assertThat(EmrQcChiefComplaintRules.evaluate("张三" + String.join("", Collections.nCopies(18, "咳")), record, masker)).isEmpty();
    }

    @Test
    void extractsPreviewArticleWithoutTitleAndDoesNotCountDuplicateRepresentation() {
        String html = "<p class='tag-article'><strong class='tag-article-title'>主诉：</strong>"
            + "<span class='tag-article-content'>咳嗽<b>20＋天</b></span></p>"
            + "<p>主诉：咳嗽20＋天</p><p>现病史：咳嗽20天，伴发热</p>";
        EmrQcHtmlPreview.Projection projection = EmrQcHtmlPreview.project(html, new PhisEmrRecord(), Collections.emptyList(), null);
        assertEquals("咳嗽20＋天", projection.chiefComplaint);
        assertEquals("咳嗽20天，伴发热", projection.presentIllness);
        assertThat(evaluate(projection.chiefComplaint)).hasSize(1);
    }

    @Test
    void extractsPlainLabelledPreviewWithInlineSectionBoundaryAndEmptyHistory() {
        String html = "<p>主诉：阴道炎1周，要求配药；现病史：</p><p>既往史：无</p>";
        assertEquals("阴道炎1周，要求配药", EmrQcChiefComplaintRules.previewField(Jsoup.parse(html).body(), "主诉"));
        assertEquals("", EmrQcChiefComplaintRules.previewField(Jsoup.parse(html).body(), "现病史"));
    }

    @Test
    void ignoresHiddenAndExecutablePreviewContentBeforeRulesRun() {
        String html = "<p class='tag-article'><b class='tag-article-title'>主诉：</b>咳嗽3天"
            + "<span hidden>+" + String.join("", Collections.nCopies(30, "坏")) + "</span>"
            + "<script>secret+++</script><span style='display:none'>＋</span></p>";
        EmrQcHtmlPreview.Projection projection = EmrQcHtmlPreview.project(html, new PhisEmrRecord(), Collections.emptyList(), null);
        assertEquals("咳嗽3天", projection.chiefComplaint);
        assertThat(evaluate(projection.chiefComplaint)).isEmpty();
    }

    @Test
    void plainLabelDoesNotConsumeFollowingSignatureOrUnrelatedParagraph() {
        String html = "<div><p>主诉：咳嗽3天</p><p>医生签名：合成医生</p><p>其他说明20+天</p></div>";
        assertEquals("咳嗽3天", EmrQcChiefComplaintRules.previewField(Jsoup.parse(html).body(), "主诉"));
    }

    @Test
    void structuredFallbackPreservesActualWhitespaceAndExcludesHiddenFields() {
        ArrayNode pages = mapper.createArrayNode();
        ArrayNode fields = pages.addObject().put("ARTICLE", "主诉").putArray("eles");
        fields.addObject().put("TEXT", " 咳嗽 20+天 ");
        fields.addObject().put("TEXT", "不计入＋").put("HIDDEN", "1");
        fields.addObject().put("TEXT", "<span hidden>不计入+</span><script>不计入+</script>");
        assertEquals(" 咳嗽 20+天 ", EmrQcChiefComplaintRules.structuredField(mapper, pages.toString(), "主诉"));
        assertEquals(" 咳嗽 20+天 ", EmrQcChiefComplaintRules.structuredField(mapper, mapper.valueToTree(pages.toString()).toString(), "主诉"));
    }

    @Test
    void doesNotGuessUnlabelledPreviewOrCombineConflictingSections() {
        assertEquals(null, EmrQcChiefComplaintRules.previewField(Jsoup.parse("<p>其他说明20+天</p>").body(), "主诉"));
        assertThrows(ServiceUnavailableException.class, () -> EmrQcChiefComplaintRules.previewField(
            Jsoup.parse("<p>主诉：咳嗽</p><p>主诉：头痛</p>").body(), "主诉"));
    }

    @Test
    void presentIllnessCanSpanMultipleParagraphsWithoutIntroducingNewFailure() {
        assertEquals("咳嗽3天\n伴发热", EmrQcChiefComplaintRules.previewField(
            Jsoup.parse("<p>现病史：咳嗽3天</p><p>现病史：伴发热</p>").body(), "现病史"));
        ArrayNode pages = mapper.createArrayNode();
        pages.addObject().put("ARTICLE", "现病史").putArray("eles").addObject().put("TEXT", "咳嗽3天");
        pages.addObject().put("ARTICLE", "现病史").putArray("eles").addObject().put("TEXT", "伴发热");
        assertEquals("咳嗽3天\n伴发热", EmrQcChiefComplaintRules.structuredField(mapper, pages.toString(), "现病史"));
    }

    @Test
    void mergesThreeRulesOnceAndPreservesOtherFindingsWithDimensionCapAndIgnore() {
        String complaint = "阴道炎反复发作伴不适要求配药治疗20+天，随访";
        EmrQcAnalysisVO.Finding diagnosis = diagnosis("诊断名代替症状");
        EmrQcAnalysisVO.Finding duplicate = diagnosis("同一问题另一种文案");
        EmrQcAnalysisVO.Finding original = diagnosis("其他主诉问题"); original.setRuleCode(null); original.setDeduction(3);
        List<EmrQcAnalysisVO.Finding> ai = new ArrayList<EmrQcAnalysisVO.Finding>(Arrays.asList(diagnosis, duplicate, original));
        ai.addAll(evaluate(complaint)); // An unexpected model copy must not double-charge the mechanical rules.
        List<EmrQcAnalysisVO.Finding> merged = EmrQcChiefComplaintRules.merge(ai, evaluate(complaint), true);
        assertThat(merged).hasSize(4);
        assertThat(merged).filteredOn(f -> f.getRuleCode() != null).hasSize(3);
        List<EmrQcAnalysisVO.Dimension> dimensions = Collections.singletonList(EmrQcRubric.dimension(EmrQcRubric.CHIEF_COMPLAINT, 5, null));
        EmrQcRubric.scoreFromFindings(dimensions, merged);
        assertEquals(0, dimensions.get(0).getScore());
        merged.forEach(f -> f.setIgnored(true));
        EmrQcRubric.scoreFromFindings(dimensions, merged);
        assertEquals(5, dimensions.get(0).getScore());
    }

    @Test
    void emptyHistorySuppressesOnlyDiagnosisRuleAndMechanicalRulesStillApply() {
        List<EmrQcAnalysisVO.Finding> merged = EmrQcChiefComplaintRules.merge(
            Collections.singletonList(diagnosis("诊断名代替症状")), evaluate("上呼吸道感染20+天"), false);
        assertThat(merged).extracting(EmrQcAnalysisVO.Finding::getRuleCode).containsExactly(EmrQcChiefComplaintRules.PLUS);
    }

    private List<EmrQcAnalysisVO.Finding> evaluate(String text) {
        return EmrQcChiefComplaintRules.evaluate(text, new PhisEmrRecord(), masker);
    }

    private EmrQcAnalysisVO.Finding diagnosis(String issue) {
        EmrQcAnalysisVO.Finding finding = new EmrQcAnalysisVO.Finding();
        finding.setRuleCode(EmrQcChiefComplaintRules.DIAGNOSIS); finding.setCategory("主诉");
        finding.setIssue(issue); finding.setDeduction(1); finding.setIgnored(false);
        return finding;
    }
}
