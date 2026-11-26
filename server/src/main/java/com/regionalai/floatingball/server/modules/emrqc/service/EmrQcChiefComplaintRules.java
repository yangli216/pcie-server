package com.regionalai.floatingball.server.modules.emrqc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only the two explicitly approved mechanical checks; clinical meaning remains an AI judgment. */
final class EmrQcChiefComplaintRules {
    static final String PLUS = "R-CHIEF-PLUS";
    static final String LENGTH = "R-CHIEF-LENGTH";
    static final String DIAGNOSIS = "R-CHIEF-DIAGNOSIS";
    // Legacy/model responses may omit ruleCode. Recognize explicit plus complaints in the
    // issue only, never a '+' occurring in clinical evidence or a suggested replacement.
    private static final Pattern PLUS_ISSUE = Pattern.compile(
        "^(?:主诉(?:中|内|的)?)?(?:(?:包含|含有|含|出现|存在|使用|带有|有)(?:了)?"
            + "(?:(?:非规范|不规范|特殊)?符号)?(?:半角|全角)?(?:加号|[+＋])"
            + "|(?:加号|[+＋])(?:号|符号)?[，,：:]?(?:不规范|不符合|不应|不能|禁止|不允许|多余))");
    // NUL denotes layout boundaries, distinct from a user-entered line break.
    private static final Pattern LABEL = Pattern.compile(
        "(?:^|[\\u0000；;])\\s*(主诉|现病史|既往史|个人史|家族史|婚育史|月经史|体格检查|查体|辅助检查|诊断|处理|治疗|诊疗措施|注意事项)\\s*[：:]");

    private EmrQcChiefComplaintRules() { }

    static boolean isRule(String code) {
        return PLUS.equals(code) || LENGTH.equals(code) || DIAGNOSIS.equals(code);
    }

    static String previewField(Element body, String name) {
        Set<String> values = new LinkedHashSet<String>();
        for (Element article : body.select("[data-emr-section]")) {
            if (!name.equals(article.attr("data-emr-section"))) continue;
            Element content = article.clone();
            content.select(".tag-article-title").remove();
            values.add(visibleText(content).replace("\u0000", ""));
        }
        if (!values.isEmpty()) return select(values, name);
        // Limit a plain labelled field to its smallest containing block. Otherwise a following
        // signature/unlabelled paragraph could accidentally become part of the complaint.
        Set<Element> labelledBlocks = new LinkedHashSet<Element>();
        List<Element> blocks = new ArrayList<Element>(body.select("p,td,th,li,div,section,article"));
        blocks.add(body);
        for (Element block : blocks) {
            Matcher labels = LABEL.matcher(visibleText(block));
            while (labels.find()) {
                if (name.equals(labels.group(1))) { labelledBlocks.add(block); break; }
            }
        }
        Set<Element> ancestors = new LinkedHashSet<Element>();
        for (Element block : labelledBlocks) ancestors.addAll(block.parents());
        for (Element block : labelledBlocks) {
            if (!ancestors.contains(block)) addLabelledValues(visibleText(block), name, values);
        }
        return values.isEmpty() ? null : select(values, name);
    }

    private static void addLabelledValues(String text, String name, Set<String> values) {
        Matcher labels = LABEL.matcher(text);
        int start = -1;
        while (labels.find()) {
            if (start >= 0) {
                values.add(text.substring(start, labels.start()).replace("\u0000", ""));
                start = -1;
            }
            if (name.equals(labels.group(1))) start = labels.end();
        }
        if (start >= 0) values.add(text.substring(start).replace("\u0000", ""));
    }

    /** Read actual TEXT without normalizer trim/collapse so redaction or whitespace cannot change the count. */
    static String structuredField(ObjectMapper mapper, String source, String name) {
        try {
            JsonNode pages = mapper.readTree(source);
            if (pages.isTextual()) pages = mapper.readTree(pages.asText());
            Set<String> sections = new LinkedHashSet<String>();
            Set<String> fields = new LinkedHashSet<String>();
            for (JsonNode page : pages) {
                if ("header".equalsIgnoreCase(page.path("ID").asText()) || "页眉".equals(page.path("ARTICLE").asText())) continue;
                String article = page.path("ARTICLE").asText(page.path("NAME").asText());
                boolean namedSection = name.equals(label(article));
                StringBuilder section = new StringBuilder();
                for (JsonNode field : page.path("eles")) {
                    JsonNode hidden = field.path("HIDDEN");
                    if (hidden.asBoolean(false) || "1".equals(hidden.asText())) continue;
                    String value = field.path("TEXT").asText("");
                    Element body = Jsoup.parseBodyFragment(value).body();
                    body.select("script,style,iframe,object,embed,svg,math,template,noscript,[hidden],[aria-hidden=true],[data-printhidden=true]").remove();
                    for (Element element : body.select("[style]")) {
                        if (element.attr("style").matches("(?is).*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden).*")) element.remove();
                    }
                    value = visibleText(body).replace("\u0000", "");
                    if (namedSection) section.append(value);
                    else if (name.equals(label(field.path("DEFAULTVALUE").asText(field.path("NAME").asText())))) fields.add(value);
                }
                if (namedSection) sections.add(section.toString());
            }
            return !sections.isEmpty() ? select(sections, name) : fields.isEmpty() ? null : select(fields, name);
        } catch (ServiceUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 主诉规则输入无法读取");
        }
    }

    private static String label(String value) {
        return value == null ? "" : value.replaceAll("[\\s：:*]", "");
    }

    private static String select(Set<String> values, String name) {
        // Present illness may legitimately span several paragraphs/pages. Only its availability
        // is used here; no clinical judgment or length check is made by this helper.
        if (!"主诉".equals(name)) return String.join("\n", values);
        if (values.size() != 1) {
            throw new ServiceUnavailableException("EMR-QC-UNAVAILABLE", "病历存在多个不同的同名章节，无法确定主诉规则输入");
        }
        return values.iterator().next();
    }

    private static String visibleText(Element root) {
        StringBuilder text = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            public void head(Node node, int depth) {
                if (node instanceof TextNode) text.append(((TextNode) node).getWholeText());
                if (node instanceof Element && "br".equals(((Element) node).tagName())) text.append('\n');
                else if (node instanceof Element && ((Element) node).isBlock()) text.append('\u0000');
            }
            public void tail(Node node, int depth) {
                if (node instanceof Element && ((Element) node).isBlock()) text.append('\u0000');
            }
        }, root);
        return text.toString();
    }

    static List<EmrQcAnalysisVO.Finding> evaluate(String complaint, PhisEmrRecord record, SensitiveDataMasker masker) {
        List<EmrQcAnalysisVO.Finding> findings = new ArrayList<EmrQcAnalysisVO.Finding>();
        if (complaint == null || complaint.isEmpty()) return findings;
        String excerpt = masker.maskText(complaint, record.getPatientName(), record.getIdCard(), record.getIdArchives());
        if (excerpt.length() > 500) excerpt = excerpt.substring(0, 500);
        if (complaint.indexOf('+') >= 0 || complaint.indexOf('＋') >= 0) {
            String suggestion = "去除主诉中的加号，症状之间使用规范连接表述。";
            Matcher duration = Pattern.compile("([0-9]+)[+＋](天|日|周|月|年|小时|分钟)").matcher(complaint);
            if (duration.find()) suggestion = "将“" + duration.group() + "”改为“" + duration.group(1) + duration.group(2) + "”等规范时长表述。";
            suggestion = masker.maskText(suggestion, record.getPatientName(), record.getIdCard(), record.getIdArchives());
            findings.add(finding(PLUS, excerpt, "主诉含有加号，书写不规范。", suggestion));
        }
        int length = complaint.codePointCount(0, complaint.length());
        if (length > 20) findings.add(finding(LENGTH, excerpt, "主诉共" + length + "字，超过20字。", "请将主诉精简至20字以内，标点、数字和英文字母均计入字数。"));
        return findings;
    }

    private static EmrQcAnalysisVO.Finding finding(String code, String excerpt, String issue, String suggestion) {
        EmrQcAnalysisVO.Finding finding = new EmrQcAnalysisVO.Finding();
        finding.setRuleCode(code);
        finding.setCategory("主诉");
        finding.setSectionName("主诉");
        finding.setSeverity("low");
        finding.setExcerpt(excerpt);
        finding.setIssue(issue);
        finding.setSuggestion(suggestion);
        finding.setDeduction(1);
        finding.setIgnored(Boolean.FALSE);
        return finding;
    }

    static List<EmrQcAnalysisVO.Finding> merge(List<EmrQcAnalysisVO.Finding> ai,
                                              List<EmrQcAnalysisVO.Finding> fixed, boolean hasPresentIllness) {
        Map<String, EmrQcAnalysisVO.Finding> findings = new LinkedHashMap<String, EmrQcAnalysisVO.Finding>();
        for (EmrQcAnalysisVO.Finding finding : ai) {
            String code = finding.getRuleCode();
            // The model never owns the two mechanical checks, even if it returns an unexpected copy.
            if (PLUS.equals(code) || LENGTH.equals(code)) continue;
            if (!StringUtils.hasText(code) && "主诉".equals(finding.getCategory())
                    && StringUtils.hasText(finding.getIssue())
                    && PLUS_ISSUE.matcher(finding.getIssue().replaceAll("[\\s“”‘’\"'「」『』]", "")).find()) continue;
            if (DIAGNOSIS.equals(code) && !hasPresentIllness) continue;
            String key = DIAGNOSIS.equals(code) ? code : finding.getCategory() + "\u0000" + finding.getIssue().trim();
            findings.putIfAbsent(key, finding);
        }
        for (EmrQcAnalysisVO.Finding finding : fixed) findings.put(finding.getRuleCode(), finding);
        return new ArrayList<EmrQcAnalysisVO.Finding>(findings.values());
    }
}
