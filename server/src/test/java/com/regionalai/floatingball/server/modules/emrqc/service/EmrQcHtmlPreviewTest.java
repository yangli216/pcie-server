package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrSection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class EmrQcHtmlPreviewTest {
    private PhisEmrRecord record() {
        PhisEmrRecord record = new PhisEmrRecord();
        record.setPatientName("测试患者");
        record.setIdCard("330102198001011234");
        record.setIdArchives("MR-9988");
        return record;
    }

    @Test
    void preservesDocumentStructureAndSafeFormatting() {
        String result = EmrQcHtmlPreview.render("<h2 style='text-align:center;font-size:18px'>门诊病历</h2>"
            + "<table><tr><td colspan='2' style='font-weight:bold'>主诉</td></tr>"
            + "<tr><td>病情</td><td>咳嗽&lt;3天</td></tr></table>", record(), Collections.emptyList());
        Document document = Jsoup.parse(result);
        assertThat(document.select("h2").attr("style")).contains("text-align:center", "font-size:18px");
        assertThat(document.select("table tr")).hasSize(2);
        assertThat(document.select("td[colspan=2]").text()).isEqualTo("主诉");
        assertThat(document.body().text()).contains("咳嗽<3天");
        assertThat(result).contains("default-src 'none'", "form-action 'none'", "base-uri 'none'");
    }

    @Test
    void removesActiveContentNetworkUrlsSignaturesAndUnsafeStyles() {
        String result = EmrQcHtmlPreview.render("<meta http-equiv='refresh' content='0;url=https://evil.invalid'>"
            + "<style>@import 'https://evil.invalid/style';</style><script>alert(1)</script>"
            + "<img src='data:image/png;base64,signature-secret' onerror='alert(1)'>"
            + "<svg><a href='https://evil.invalid'>secret-svg</a></svg>"
            + "<iframe src='https://evil.invalid'></iframe><form action='https://evil.invalid'><input value='secret'></form>"
            + "<a href='javascript:alert(1)'>病历</a><p onclick='alert(1)' data-secret='private' "
            + "style='background-image:url(https://evil.invalid);position:fixed;height:9999px;display:none;color:#123'>不应显示</p>"
            + "<p style='color:#123'>可见正文</p>",
            record(), Collections.emptyList());
        Document document = Jsoup.parse(result);
        assertThat(document.body().select("script,img,svg,iframe,form,input,a,[onclick],[data-secret],[src],[href]")).isEmpty();
        assertThat(result).doesNotContain("evil.invalid", "signature-secret", "secret-svg", "position:fixed", "height:9999", "display:none", "不应显示");
        assertThat(document.select("p").attr("style")).isEqualTo("color:#123;");
        assertThat(document.body().text()).contains("病历", "可见正文");
    }

    @Test
    void reusesSafePhisPrintSemanticsBeforeCleaning() {
        String fields = "[{\"ID\":\"article-1\",\"eles\":[{\"ID\":\"field-1\","
            + "\"PRINTVALUE\":true,\"TEXT\":\"实际值<script>alert(1)</script>\"}]}]";
        String html = "<p class='tag-article' data-id='article-1'><strong class='tag-article-title'>*主诉：</strong>"
            + "<span class='tag-value' data-id='field-1'>旧值</span><span class='tag-marker'>字段标记</span></p>"
            + "<p data-printhidden='true'>打印隐藏</p><p hidden>HTML隐藏</p>"
            + "<p style='visibility:hidden'>样式隐藏</p><p class='noprint'>非打印内容</p>"
            + "<p><span class='placeholder'>占位内容</span>保留正文</p>";
        Document document = Jsoup.parse(EmrQcHtmlPreview.render(html, record(), Collections.emptyList(), fields));
        assertThat(document.body().text()).contains("主诉：实际值", "保留正文")
            .doesNotContain("*", "旧值", "字段标记", "打印隐藏", "HTML隐藏", "样式隐藏", "非打印内容", "占位内容", "alert");
        assertThat(document.select(".tag-article,.tag-article-title,.tag-value")).hasSize(3);
        assertThat(document.select(".tag-article").attr("data-emr-section")).isEqualTo("主诉");
        assertThat(document.select("[data-id],[data-printhidden],[hidden],[aria-hidden],script")).isEmpty();
    }

    @Test
    void keepsOnlyThePhisHeaderDividerAndProvidesLinkedSectionStyling() {
        String html = "<header><p>门诊病历</p><hr></header>"
            + "<p class='tag-article'><strong class='tag-article-title'>主诉：</strong></p>";
        String result = EmrQcHtmlPreview.render(html, record(), Collections.emptyList());
        Document document = Jsoup.parse(result);

        assertThat(document.select("header hr")).hasSize(1);
        assertThat(document.select("[data-emr-section=主诉]")).hasSize(1);
        assertThat(document.selectFirst("style").data()).contains("header{text-align:center;margin:0 0 8px;padding-bottom:0}")
            .contains("[data-emr-linked=true]")
            .doesNotContain("header{text-align:center;margin:0 0 8px;padding-bottom:8px;border-bottom");
    }

    @Test
    void masksDecodedAndSplitIdentityTokensWithoutDestroyingClinicalText() {
        String result = EmrQcHtmlPreview.render("<p>测试<span>患者</span>，330102<span>19800101</span>1234，"
            + "MR-<b>9988</b>，138<span>0013</span>8000</p><p>咳嗽3天，体温37.2℃</p>", record(), Collections.emptyList());
        assertThat(Jsoup.parse(result).body().text()).doesNotContain("测试患者", "330102", "19800101", "1234", "9988", "8000")
            .contains("[已脱敏]", "咳嗽3天", "37.2℃");
    }

    @Test
    void masksKnownAddressAcrossMarkupAndDiscardsAttributeData() {
        PhisEmrSection section = new PhisEmrSection();
        PhisEmrSection.Field field = new PhisEmrSection.Field();
        field.setName("家庭地址"); field.setValue("测试市示例路88号");
        section.getFields().add(field);
        String result = EmrQcHtmlPreview.render("<p title='330102198001011234'>地址：测试市<span>示例路88号</span></p>",
            record(), Collections.singletonList(section));
        assertThat(result).doesNotContain("测试市", "示例路", "330102198001011234").contains("[已脱敏]");
    }

    @Test
    void fallsBackForMissingEmptyOrOversizeHtml() {
        assertThat(EmrQcHtmlPreview.render(null, record(), Collections.emptyList())).isEmpty();
        assertThat(EmrQcHtmlPreview.render("<script>alert(1)</script><img src=x>", record(), Collections.emptyList())).isEmpty();
        assertThat(EmrQcHtmlPreview.render(new String(new char[1024 * 1024 + 1]), record(), Collections.emptyList())).isEmpty();
    }

    @Test
    void removesOnlyKnownEmptyPlaceholdersAndKeepsValuesAcrossMarkup() {
        String fields = "[{\"eles\":[{\"NAME\":\"主诉文本\",\"TEXT\":\"\"},"
            + "{\"NAME\":\"月经史\",\"TEXT\":\"\"},{\"NAME\":\"既往史\",\"TEXT\":\"否认\"},"
            + "{\"NAME\":\"同名字段\",\"TEXT\":\"\"},{\"NAME\":\"其他\",\"TEXT\":\"同名字段\"}]}]";
        PhisEmrRecord patient = record();
        patient.setGenderCode("1");
        String html = "<p>主诉：{<b>主诉</b>文本}</p><p>月经史：{月经史}。</p>"
            + "<p>既往史：{<b>否</b>认}，{130}/{90}；{同名字段}</p>";
        String result = EmrQcHtmlPreview.render(html, patient, Collections.emptyList(), fields);
        assertThat(Jsoup.parse(result).body().text()).contains("主诉：", "既往史：否认", "130/90", "同名字段")
            .doesNotContain("主诉文本", "月经史", "{", "}");
    }

    @Test
    void neverRemovesRecordedMenstrualHistoryOrUnknownGender() {
        String fields = "[{\"eles\":[{\"NAME\":\"月经史\",\"TEXT\":\"有记录\"}]}]";
        String html = "<p>月经史：{有记录}。</p>";
        PhisEmrRecord male = record(); male.setGenderCode("1");
        assertThat(Jsoup.parse(EmrQcHtmlPreview.render(html, male, Collections.emptyList(), fields)).body().text())
            .contains("月经史：有记录");
        PhisEmrRecord unknown = record();
        assertThat(Jsoup.parse(EmrQcHtmlPreview.render("<p>月经史：{月经史}。</p>", unknown,
            Collections.emptyList(), "[{\"eles\":[{\"NAME\":\"月经史\",\"TEXT\":\"\"}]}]")).body().text())
            .contains("月经史：");
    }
}
