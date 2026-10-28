package com.regionalai.floatingball.server.modules.emrqc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrSection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A display-only projection. Never use this document as AI input or a record hash source. */
public final class EmrQcHtmlPreview {
    private static final Safelist ALLOWED = new Safelist()
        .addTags("article", "section", "header", "footer", "div", "span", "p", "br", "hr",
            "h1", "h2", "h3", "h4", "h5", "h6", "b", "strong", "i", "em", "u", "s",
            "sub", "sup", "table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th",
            "col", "colgroup", "ul", "ol", "li", "dl", "dt", "dd", "pre", "blockquote")
        .addAttributes(":all", "style", "class")
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan")
        .addAttributes("col", "span");
    private static final String CSP = "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; "
        + "img-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'; frame-src 'none'";
    private static final Set<String> SAFE_CLASSES = new LinkedHashSet<String>(Arrays.asList(
        "page", "tag-article", "tag-article-title", "tag-article-content", "tag-filed", "tag-value", "sign"));
    private static final Pattern HIDDEN_STYLE = Pattern.compile(
        "(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)(?:\\s*!important)?\\s*(?:;|$)",
        Pattern.CASE_INSENSITIVE);
    private static final String CSS = "*{box-sizing:border-box;max-width:100%}html{color-scheme:light}"
        + "body{margin:0;padding:12px 18px;color:#263238;font:13px/1.55 'Microsoft YaHei','PingFang SC',Arial,sans-serif;overflow-wrap:anywhere}"
        + "header{text-align:center;margin:0 0 8px;padding-bottom:0}"
        + "p{margin:2px 0}.tag-article{margin:3px 0}.tag-article-title{font-weight:700}.tag-article-content{display:inline}"
        + "h1,h2,h3,h4{margin:8px 0 4px;line-height:1.35}h1{font-size:18px}h2{font-size:16px}h3,h4{font-size:14px}"
        + "table{border-collapse:collapse;width:100%;table-layout:fixed;margin:4px 0}td,th{padding:3px 5px;vertical-align:top;overflow-wrap:anywhere}"
        + "pre{white-space:pre-wrap}ul,ol{padding-left:20px}hr{margin:8px 0 0;border:0;border-top:1px solid #dfe6e3}"
        + "[data-emr-linked=true]{margin-left:-8px;padding-left:5px;border-left:3px solid #d49a35;border-radius:3px;background:#fff5df;box-shadow:0 0 0 1px #f0d59f}"
        + "[data-emr-linked=true] .tag-article-title{color:#9a5c08}";

    private EmrQcHtmlPreview() { }

    public static String render(String source, PhisEmrRecord record, List<PhisEmrSection> sections) {
        return render(source, record, sections, null);
    }

    public static String render(String source, PhisEmrRecord record, List<PhisEmrSection> sections, String structuredContent) {
        if (!StringUtils.hasText(source) || source.length() > 1024 * 1024) return "";
        Document input = Jsoup.parse(source);
        preprocessPhisPreview(input, structuredContent);
        input.select("script,style,iframe,frame,frameset,object,embed,svg,math,form,input,button,select,textarea,"
            + "img,canvas,video,audio,source,link,meta,base,template,noscript").remove();
        Document safe = new Cleaner(ALLOWED).clean(input);
        for (Element element : safe.body().getAllElements()) {
            String style = cleanStyle(element.attr("style"));
            element.removeAttr("style");
            if (!style.isEmpty()) element.attr("style", style);
            List<String> safeClasses = new ArrayList<String>();
            for (String className : element.classNames()) if (SAFE_CLASSES.contains(className)) safeClasses.add(className);
            if (safeClasses.isEmpty()) element.removeAttr("class");
            else element.attr("class", String.join(" ", safeClasses));
            for (String attribute : Arrays.asList("colspan", "rowspan", "span")) {
                if (element.hasAttr(attribute) && !element.attr(attribute).matches("[1-9][0-9]?(?:0)?")) {
                    element.removeAttr(attribute);
                }
            }
        }
        removeTemplateMarkers(safe.body(), structuredContent);
        markSections(safe.body());
        if ("1".equals(record.getGenderCode()) || "男".equals(record.getGenderCode())) {
            for (Element element : safe.body().select("p,li,section,div")) {
                if (element.text().matches("\\s*月经史\\s*[：:]?\\s*[。.;；]*\\s*")) element.remove();
            }
        }
        redactText(safe.body(), record, sections);
        if (!StringUtils.hasText(safe.body().text())) return "";
        safe.outputSettings().prettyPrint(false);
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
            + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">"
            + "<meta name=\"referrer\" content=\"no-referrer\"><style>" + CSS
            + "</style></head><body>" + safe.body().html() + "</body></html>";
    }

    private static void preprocessPhisPreview(Document input, String structuredContent) {
        input.select("[data-printhidden=true],[hidden],[aria-hidden=true],.tag-marker,.placeholder,.noprint").remove();
        for (Element element : new ArrayList<Element>(input.select("[style]"))) {
            if (HIDDEN_STYLE.matcher(element.attr("style")).find()) element.remove();
        }
        for (Element title : input.select(".tag-article-title")) {
            for (TextNode node : title.textNodes()) node.text(node.getWholeText().replace("*", ""));
        }
        JsonNode pages = readStructuredPages(structuredContent);
        if (pages == null) return;
        for (JsonNode page : pages) {
            String articleId = page.path("ID").asText("");
            if (!StringUtils.hasText(articleId)) continue;
            for (JsonNode field : page.path("eles")) {
                String fieldId = field.path("ID").asText("");
                if (!StringUtils.hasText(fieldId) || !isPrintValueEnabled(field.get("PRINTVALUE"))) continue;
                for (Element article : input.select(".tag-article[data-id]")) {
                    if (!articleId.equals(article.attr("data-id"))) continue;
                    for (Element value : article.select("span[data-id]")) {
                        if (fieldId.equals(value.attr("data-id"))) value.html(field.path("TEXT").asText(""));
                    }
                }
            }
        }
    }

    private static boolean isPrintValueEnabled(JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return value.asInt() != 0;
        String text = value.asText("").trim();
        return StringUtils.hasText(text) && !"false".equalsIgnoreCase(text) && !"0".equals(text);
    }

    private static JsonNode readStructuredPages(String structuredContent) {
        if (!StringUtils.hasText(structuredContent)) return null;
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode pages = mapper.readTree(structuredContent);
            if (pages != null && pages.isTextual()) pages = mapper.readTree(pages.asText());
            return pages != null && pages.isArray() ? pages : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void removeTemplateMarkers(Element body, String structuredContent) {
        Set<String> emptyLabels = new LinkedHashSet<String>();
        Set<String> filledValues = new LinkedHashSet<String>();
        JsonNode pages = readStructuredPages(structuredContent);
        if (pages != null) {
            for (JsonNode page : pages) {
                for (JsonNode field : page.path("eles")) {
                    String value = Jsoup.parse(field.path("TEXT").asText("")).text().trim();
                    if (StringUtils.hasText(value)) {
                        filledValues.add(value);
                    } else {
                        for (String key : Arrays.asList("NAME", "DEFAULTVALUE", "DESCNAME")) {
                            String label = field.path(key).asText("").trim();
                            if (StringUtils.hasText(label)) emptyLabels.add(label);
                        }
                    }
                }
            }
        }
        List<TextNode> nodes = new ArrayList<TextNode>();
        StringBuilder combined = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            public void head(Node node, int depth) {
                if (node instanceof TextNode) { nodes.add((TextNode) node); combined.append(((TextNode) node).getWholeText()); }
            }
            public void tail(Node node, int depth) { }
        }, body);
        List<int[]> removals = new ArrayList<int[]>();
        Matcher marker = Pattern.compile("\\{([^{}\\r\\n]{1,128})\\}").matcher(combined);
        while (marker.find()) {
            String value = marker.group(1).trim();
            boolean usedInFilledText = filledValues.stream().anyMatch(text -> text.contains(value));
            if (emptyLabels.contains(value) && !usedInFilledText) {
                removals.add(new int[]{marker.start(), marker.end()});
            } else {
                removals.add(new int[]{marker.start(), marker.start() + 1});
                removals.add(new int[]{marker.end() - 1, marker.end()});
            }
        }
        int offset = 0;
        for (TextNode node : nodes) {
            String value = node.getWholeText();
            StringBuilder result = new StringBuilder();
            int cursor = 0;
            for (int[] range : removals) {
                int from = Math.max(0, range[0] - offset), to = Math.min(value.length(), range[1] - offset);
                if (from >= to) continue;
                result.append(value, cursor, from);
                cursor = to;
            }
            result.append(value.substring(cursor));
            node.text(result.toString());
            offset += value.length();
        }
    }

    private static void markSections(Element body) {
        for (Element article : body.select(".tag-article")) {
            Element title = article.selectFirst(".tag-article-title");
            if (title == null) continue;
            String section = title.text().replaceFirst("[\\s：:]+$", "").trim();
            if (StringUtils.hasText(section) && section.length() <= 64) {
                article.attr("data-emr-section", section);
            }
        }
    }

    private static String cleanStyle(String raw) {
        StringBuilder result = new StringBuilder();
        for (String declaration : raw.toLowerCase(Locale.ROOT).split(";")) {
            String[] pair = declaration.split(":", 2);
            if (pair.length != 2) continue;
            String name = pair[0].trim(), value = pair[1].trim();
            boolean valid = ("text-align".equals(name) && value.matches("left|right|center|justify"))
                || ("vertical-align".equals(name) && value.matches("top|middle|bottom|baseline"))
                || ("font-weight".equals(name) && value.matches("normal|bold|[1-9]00"))
                || ("font-style".equals(name) && value.matches("normal|italic"))
                || ("text-decoration".equals(name) && value.matches("none|underline|line-through"))
                || ("font-size".equals(name) && value.matches("(?:[89]|1[0-9]|2[0-4])(?:px|pt)"))
                || (("color".equals(name) || "background-color".equals(name)) && value.matches("#[0-9a-f]{3}(?:[0-9a-f]{3})?|black|white|gray"))
                || ("border-collapse".equals(name) && value.matches("collapse|separate"))
                || ("border".equals(name) && value.matches("[0-2]px solid #[0-9a-f]{3}(?:[0-9a-f]{3})?"))
                || ("width".equals(name) && value.matches("(?:[1-9][0-9]?|100)%"));
            if (valid) result.append(name).append(':').append(value).append(';');
        }
        return result.toString();
    }

    private static void redactText(Element body, PhisEmrRecord record, List<PhisEmrSection> sections) {
        Set<String> identities = new LinkedHashSet<String>();
        for (String value : Arrays.asList(record.getPatientName(), record.getIdCard(), record.getIdArchives())) {
            if (StringUtils.hasText(value)) identities.add(value.trim());
        }
        for (PhisEmrSection section : sections) {
            for (PhisEmrSection.Field field : section.getFields()) {
                if (field.getName() != null && field.getName().matches(".*(?:姓名|身份证|证件|病案|地址|住址|户籍|工作单位|电话|手机|联系).*")) {
                    if (StringUtils.hasText(field.getValue()) && field.getValue().trim().length() > 1) identities.add(field.getValue().trim());
                }
            }
        }
        List<String> tokens = new ArrayList<String>(identities);
        tokens.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder expression = new StringBuilder("(?<!\\d)(?:\\d{17}[\\dXx]|1[3-9]\\d{9}|0\\d{2,3}[- ]?\\d{7,8})(?!\\d)");
        for (String token : tokens) expression.append('|').append(Pattern.quote(token));
        List<TextNode> nodes = new ArrayList<TextNode>();
        StringBuilder text = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            public void head(Node node, int depth) {
                if (node instanceof TextNode) {
                    nodes.add((TextNode) node);
                    text.append(((TextNode) node).getWholeText());
                }
            }
            public void tail(Node node, int depth) { }
        }, body);
        List<int[]> matches = new ArrayList<int[]>();
        Matcher matcher = Pattern.compile(expression.toString()).matcher(text);
        while (matcher.find()) matches.add(new int[] {matcher.start(), matcher.end()});
        int offset = 0;
        int matchIndex = 0;
        for (TextNode node : nodes) {
            String value = node.getWholeText();
            StringBuilder masked = new StringBuilder();
            int cursor = 0;
            while (matchIndex < matches.size() && matches.get(matchIndex)[1] <= offset) matchIndex++;
            for (int i = matchIndex; i < matches.size() && matches.get(i)[0] < offset + value.length(); i++) {
                int[] range = matches.get(i);
                int from = Math.max(0, range[0] - offset), to = Math.min(value.length(), range[1] - offset);
                if (from >= to) continue;
                masked.append(value, cursor, from);
                if (range[0] >= offset) masked.append("[已脱敏]");
                cursor = to;
            }
            masked.append(value.substring(cursor));
            node.text(masked.toString());
            offset += value.length();
        }
    }
}
