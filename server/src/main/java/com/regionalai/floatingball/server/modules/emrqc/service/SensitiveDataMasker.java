package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrSection;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class SensitiveDataMasker {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern LANDLINE = Pattern.compile("(?<!\\d)(?:0\\d{2,3}[- ]?)\\d{7,8}(?!\\d)");
    private static final Pattern ID_NUMBER = Pattern.compile("(?<![0-9A-Za-z])\\d{17}[0-9Xx](?![0-9A-Za-z])");
    private static final Pattern LABELLED_ID = Pattern.compile("(病案号|就诊号|住院号|身份证号|身份证|证件号)\\s*[：:]\\s*[^，,；;。\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LABELLED_NAME = Pattern.compile("(姓名|患者姓名|联系人|家属姓名)\\s*[：:]\\s*[\\u4e00-\\u9fff·]{2,8}");
    private static final Pattern LABELLED_ADDRESS = Pattern.compile("(家庭地址|现住址|住址|地址|户籍|工作单位)\\s*[：:]\\s*[^，,；;。\\n]+", Pattern.CASE_INSENSITIVE);
    private static final String[] ADDRESS_FIELD_HINTS = {"地址", "住址", "户籍", "工作单位", "现住"};
    private static final String[] CONTACT_FIELD_HINTS = {"电话", "手机", "联系方式"};

    public String maskName(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.substring(0, 1) + "*";
    }

    public String maskIdCard(String idCard) {
        if (!StringUtils.hasText(idCard) || idCard.trim().length() < 8) {
            return null;
        }
        String trimmed = idCard.trim();
        int keep = 3;
        int tail = 4;
        StringBuilder builder = new StringBuilder();
        builder.append(trimmed.substring(0, keep));
        for (int i = keep; i < trimmed.length() - tail; i++) {
            builder.append('*');
        }
        builder.append(trimmed.substring(trimmed.length() - tail));
        return builder.toString();
    }

    public List<PhisEmrSection> maskSections(List<PhisEmrSection> sections,
                                             String patientName,
                                             String idCard,
                                             String idArchives) {
        List<PhisEmrSection> masked = new ArrayList<PhisEmrSection>();
        for (PhisEmrSection section : sections) {
            PhisEmrSection copy = new PhisEmrSection();
            copy.setName(section.getName());
            for (PhisEmrSection.Field field : section.getFields()) {
                PhisEmrSection.Field maskedField = new PhisEmrSection.Field();
                maskedField.setName(field.getName());
                maskedField.setValue(maskValue(field.getName(), field.getValue(), patientName, idCard, idArchives));
                copy.getFields().add(maskedField);
            }
            masked.add(copy);
        }
        return masked;
    }

    public String maskText(String text, String patientName, String idCard, String idArchives) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String result = text;
        if (StringUtils.hasText(patientName)) {
            result = result.replace(patientName.trim(), "[姓名]");
        }
        if (StringUtils.hasText(idCard)) {
            result = result.replace(idCard.trim(), "[身份证号]");
        }
        if (StringUtils.hasText(idArchives)) {
            result = result.replace(idArchives.trim(), "[病案号]");
        }
        result = ID_NUMBER.matcher(result).replaceAll("[身份证号]");
        result = LABELLED_ID.matcher(result).replaceAll("$1：[号码]");
        result = LABELLED_NAME.matcher(result).replaceAll("$1：[姓名]");
        result = LABELLED_ADDRESS.matcher(result).replaceAll("$1：[地址]");
        result = LANDLINE.matcher(result).replaceAll("[联系方式]");
        return PHONE.matcher(result).replaceAll("[联系方式]");
    }

    public String maskValue(String fieldName,
                            String value,
                            String patientName,
                            String idCard,
                            String idArchives) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (containsHint(fieldName, ADDRESS_FIELD_HINTS)) {
            return "[地址]";
        }
        if (containsHint(fieldName, CONTACT_FIELD_HINTS)) {
            return "[联系方式]";
        }
        return maskText(value, patientName, idCard, idArchives);
    }

    private boolean containsHint(String fieldName, String[] hints) {
        if (!StringUtils.hasText(fieldName)) {
            return false;
        }
        for (String hint : hints) {
            if (fieldName.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
