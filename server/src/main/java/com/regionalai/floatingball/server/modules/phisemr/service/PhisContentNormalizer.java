package com.regionalai.floatingball.server.modules.phisemr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrSection;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class PhisContentNormalizer {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper objectMapper;

    public PhisContentNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<PhisEmrSection> toSections(String xmlContent) {
        if (!StringUtils.hasText(xmlContent)) {
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 病历内容为空，无法读取");
        }
        JsonNode pages;
        try {
            pages = objectMapper.readTree(xmlContent);
        } catch (Exception ex) {
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 病历内容结构异常，无法解析");
        }
        JsonNode pageArray = pages;
        if (pageArray != null && pageArray.isTextual()) {
            try {
                pageArray = objectMapper.readTree(pageArray.asText());
            } catch (Exception ex) {
                throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 病历内容结构异常，无法解析");
            }
        }
        if (pageArray == null || !pageArray.isArray()) {
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 病历内容结构异常，无法解析");
        }

        List<PhisEmrSection> sections = new ArrayList<PhisEmrSection>();
        for (JsonNode page : pageArray) {
            String pageId = text(page, "ID");
            String article = text(page, "ARTICLE");
            if ("header".equalsIgnoreCase(pageId) || "页眉".equals(article)) {
                continue;
            }
            String sectionName = StringUtils.hasText(article) ? article : text(page, "NAME");
            JsonNode elements = page.get("eles");
            if (elements == null || !elements.isArray()) {
                continue;
            }
            PhisEmrSection section = new PhisEmrSection();
            section.setName(cleanText(sectionName));
            for (JsonNode element : elements) {
                if (element.path("HIDDEN").asBoolean(false)) {
                    continue;
                }
                String value = cleanText(text(element, "TEXT"));
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                String fieldName = text(element, "DEFAULTVALUE");
                if (!StringUtils.hasText(fieldName)) {
                    fieldName = text(element, "NAME");
                }
                PhisEmrSection.Field field = new PhisEmrSection.Field();
                field.setName(cleanText(fieldName));
                field.setValue(value);
                section.getFields().add(field);
            }
            if (!section.getFields().isEmpty()) {
                sections.add(section);
            }
        }
        if (sections.isEmpty()) {
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 病历没有可展示的章节内容");
        }
        return sections;
    }

    public String canonicalText(List<PhisEmrSection> sections) {
        StringBuilder builder = new StringBuilder();
        for (PhisEmrSection section : sections) {
            builder.append(section.getName()).append('\n');
            for (PhisEmrSection.Field field : section.getFields()) {
                builder.append(field.getName()).append('：').append(field.getValue()).append('\n');
            }
        }
        return builder.toString();
    }

    public String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value & 0xff));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 计算失败", ex);
        }
    }

    public LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.length() >= 19) {
                return LocalDateTime.parse(text.substring(0, 19), DATE_TIME);
            }
            if (text.length() >= 16) {
                return LocalDateTime.parse(text.substring(0, 16), DATE_TIME_SHORT);
            }
            return LocalDate.parse(text.substring(0, 10)).atStartOfDay();
        } catch (Exception ex) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private String cleanText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace('\u00a0', ' ');
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }
}
