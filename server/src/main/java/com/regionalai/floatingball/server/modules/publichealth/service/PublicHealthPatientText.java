package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps patient prescription text separate from the existing doctor-only warning channel. */
public final class PublicHealthPatientText {
    private static final String ECMA_WHITESPACE = "[\\x09-\\x0D\\x20\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";
    private static final Pattern INTERNAL_LANGUAGE = ecmaPattern(
            "制定依据|待医生(?:核实|审核|核对|评估)|请医生(?:核实|审核|核对)|AI\\s*定性分析"
                    + "|适用性待核实|不构成医疗建议|合成展示"
                    + "|(?:^|[\\s。！？!?;；（(])(?:来源|出处|证据|资料来源|数据来源|证据来源|优先级|资料快照|记录版本)\\s*[:：]"
                    + "|(?:^|[\\r\\n\\u2028\\u2029])\\s*(?:#{1,6}\\s*)?(?:待补充资料|医生提醒|模型说明|机构批准提示|生成依据|待核实事项|待审核事项|需医生核实事项|待医生核实事项)\\s*(?:[:：]|[\\r\\n\\u2028\\u2029]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREPARATION_BLOCK = Pattern.compile("[^\\r\\n\\u2028\\u2029。！？!?]+(?:[。！？!?]+|(?=[\\r\\n\\u2028\\u2029]|$))");
    private static final Pattern INTERNAL_PREPARATION = ecmaPattern("核对|核实|审核|待批准|目录|规则|基线|适用性"
            + "|(?:^|[\\s（(])(?:来源|出处|证据|制定依据|优先级|资料快照|记录版本)\\s*[:：]", 0);
    private static final Pattern PATIENT_PREPARATION_ACTION = Pattern.compile("禁食|空腹|饮水|饮酒|运动|进食|服药|携带|尿液|样本|采样|采血");

    private PublicHealthPatientText() { }

    private static Pattern ecmaPattern(String source, int flags) {
        return Pattern.compile(source.replace("\\s", ECMA_WHITESPACE), flags);
    }

    public static String requirePatientText(String text) {
        if (INTERNAL_LANGUAGE.matcher(text).find()) throw PublicHealthModelService.invalid();
        return text;
    }

    public static boolean isPatientText(String text) {
        return !INTERNAL_LANGUAGE.matcher(text).find();
    }

    public static String patientPreparation(String text, List<String> doctorBlocks) {
        List<String> patient = new ArrayList<String>();
        Matcher matcher = PREPARATION_BLOCK.matcher(text);
        while (matcher.find()) {
            String block = trim(matcher.group());
            if (block.isEmpty()) continue;
            if (INTERNAL_PREPARATION.matcher(block).find() || !isPatientText(block)) {
                if (PATIENT_PREPARATION_ACTION.matcher(block).find()) throw PublicHealthModelService.invalid();
                doctorBlocks.add(block);
            } else patient.add(block);
        }
        return String.join("\n", patient);
    }

    /** Removes only the explicit internal field, retaining strict validation of all other fields. */
    public static JsonNode separateDoctorNotes(JsonNode raw, ArrayNode warnings, String sectionId, String prefix) {
        if (!raw.isObject()) throw PublicHealthModelService.invalid();
        ObjectNode patient = ((ObjectNode) raw).deepCopy();
        if (patient.has("doctorNotes")) {
            JsonNode notes = patient.remove("doctorNotes");
            if (!notes.isArray() || notes.size() > 8) throw PublicHealthModelService.invalid();
            for (JsonNode note : notes) {
                String value = PublicHealthHealthEducation.read(note, 1000);
                warning(warnings, "DOCTOR_NOTE", sectionId, prefix + value);
            }
        }
        return patient;
    }

    /** Retains the complete original doctor explanation while respecting the existing wire limits. */
    public static void warning(ArrayNode warnings, String code, String sectionId, String message) {
        if (message == null || message.trim().isEmpty()) throw PublicHealthModelService.invalid();
        int offset = 0;
        while (offset < message.length()) {
            int count = Math.min(2000, message.codePointCount(offset, message.length()));
            int end = message.offsetByCodePoints(offset, count);
            if (warnings.size() >= 32 || trim(message.substring(offset, end)).isEmpty()) throw PublicHealthModelService.invalid();
            warnings.addObject().put("code", code).put("sectionId", sectionId).put("message", message.substring(offset, end));
            offset = end;
        }
    }

    private static String trim(String text) {
        int start = 0, end = text.length();
        while (start < end && isEcmaWhitespace(text.charAt(start))) start++;
        while (end > start && isEcmaWhitespace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static boolean isEcmaWhitespace(char value) {
        return (value >= '\t' && value <= '\r') || value == ' ' || value == '\u00A0' || value == '\u1680'
                || (value >= '\u2000' && value <= '\u200A') || value == '\u2028' || value == '\u2029'
                || value == '\u202F' || value == '\u205F' || value == '\u3000' || value == '\uFEFF';
    }
}
