package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.LongNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.security.MessageDigest;

public final class PublicHealthJson {
    private static final BigDecimal MAX_SAFE_NUMBER = new BigDecimal("9007199254740991");
    private PublicHealthJson() {
    }

    public static String sha256(byte[] bytes) {
        try {
            StringBuilder out = new StringBuilder();
            for (byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) {
                out.append(String.format("%02x", b&255));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static JsonNode sorted(JsonNode node, ObjectMapper mapper) {
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            List<String> keys = new ArrayList<String>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            for (String key:keys) {
                out.set(key, sorted(node.get(key), mapper));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode item:node) {
                out.add(sorted(item, mapper));
            }
            return out;
        }
        if (node.isNumber()) {
            double value = node.doubleValue();
            if (!Double.isFinite(value) || node.decimalValue().abs().compareTo(MAX_SAFE_NUMBER) > 0) {
                throw PublicHealthValidator.invalid("数值必须有限且处于跨端安全范围");
            }
            // Integral values and signed zero must survive a JavaScript JSON
            // round trip without changing snapshot identity or integer fields.
            if (value == Math.rint(value)) {
                return LongNode.valueOf((long) value);
            }
            return DecimalNode.valueOf(BigDecimal.valueOf(value).stripTrailingZeros());
        }
        return node;
    }
}
