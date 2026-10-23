package com.regionalai.floatingball.server.modules.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import java.io.IOException;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Stores optional speech settings in the existing JSON column without exposing them as feature flags. */
final class SpeechTranscriptionSettings {
    static final String KEY = "speechTranscription";
    static final int DEFAULT_DURATION_MS = 4000;
    private final String mode;
    private final int durationMs;

    private SpeechTranscriptionSettings(String mode, int durationMs) {
        if (!"batch".equals(mode) && !"segmented".equals(mode)) {
            throw new BusinessException("语音转写模式必须是 batch 或 segmented");
        }
        if (durationMs < 2000 || durationMs > 15000) {
            throw new BusinessException("语音分段时长必须是 2000–15000 毫秒的整数");
        }
        this.mode = mode;
        this.durationMs = durationMs;
    }

    String getMode() { return mode; }
    int getDurationMs() { return durationMs; }

    static SpeechTranscriptionSettings read(ObjectMapper mapper, String json) {
        return fromNode(root(mapper, json));
    }

    static String merge(ObjectMapper mapper, String json, String mode, Integer durationMs) {
        return merge(mapper, json, mode, durationMs, null);
    }

    static String merge(ObjectMapper mapper, String json, String mode, Integer durationMs, String existingJson) {
        ObjectNode root = root(mapper, json);
        ObjectNode existing = root(mapper, existingJson);
        SpeechTranscriptionSettings previous = fromNode(root.has(KEY) ? root : existing);
        fromNode(root);
        features(mapper, root);
        if (mode == null && durationMs == null && !root.has(KEY) && !existing.has(KEY)) return json;
        SpeechTranscriptionSettings settings = new SpeechTranscriptionSettings(
            mode == null ? previous.mode : mode,
            durationMs == null ? previous.durationMs : durationMs);
        ObjectNode node = mapper.createObjectNode();
        node.put("mode", settings.mode);
        node.put("segmentDurationMs", settings.durationMs);
        root.set(KEY, node);
        return root.toString();
    }

    static Map<String, Boolean> features(ObjectMapper mapper, String json) {
        ObjectNode root = root(mapper, json);
        fromNode(root);
        return features(mapper, root);
    }

    private static Map<String, Boolean> features(ObjectMapper mapper, ObjectNode root) {
        ObjectNode flags = root.deepCopy();
        flags.remove(KEY);
        try {
            return mapper.readValue(flags.toString(), new TypeReference<Map<String, Boolean>>() {});
        } catch (IOException ex) {
            throw new BusinessException("featuresJson 必须是合法 JSON");
        }
    }

    private static ObjectNode root(ObjectMapper mapper, String json) {
        if (!StringUtils.hasText(json)) return mapper.createObjectNode();
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) throw new IOException("Expected object");
            return (ObjectNode) root;
        } catch (IOException ex) {
            throw new BusinessException("featuresJson 必须是合法 JSON");
        }
    }

    private static SpeechTranscriptionSettings fromNode(ObjectNode root) {
        JsonNode node = root.get(KEY);
        if (node == null) return new SpeechTranscriptionSettings("batch", DEFAULT_DURATION_MS);
        if (!node.isObject()) throw new BusinessException("speechTranscription 必须是对象");
        JsonNode mode = node.get("mode");
        JsonNode duration = node.get("segmentDurationMs");
        if (mode != null && !mode.isTextual()) throw new BusinessException("语音转写模式必须是 batch 或 segmented");
        if (duration != null && (!duration.isIntegralNumber() || !duration.canConvertToInt())) {
            throw new BusinessException("语音分段时长必须是 2000–15000 毫秒的整数");
        }
        return new SpeechTranscriptionSettings(mode == null ? "batch" : mode.asText(),
            duration == null ? DEFAULT_DURATION_MS : duration.asInt());
    }
}
