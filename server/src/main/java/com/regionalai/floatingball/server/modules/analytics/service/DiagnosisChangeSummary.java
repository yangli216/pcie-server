package com.regionalai.floatingball.server.modules.analytics.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.math.BigDecimal;

/** Reads the complete text without database JSON casts or CLOB truncation. */
final class DiagnosisChangeSummary {
    private static final JsonFactory JSON = new JsonFactory()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private DiagnosisChangeSummary() { }

    static boolean hasUnchangedDiagnosis(String summary) {
        if (summary == null) return false;
        try (JsonParser parser = JSON.createParser(summary)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return false;
            BigDecimal changes = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.getCurrentToken() != JsonToken.FIELD_NAME) return false;
                String field = parser.getCurrentName();
                JsonToken value = parser.nextToken();
                if ("diagnosisChanges".equals(field)) {
                    if (value == JsonToken.VALUE_NUMBER_INT || value == JsonToken.VALUE_NUMBER_FLOAT) {
                        changes = parser.getDecimalValue();
                    } else if (value == JsonToken.VALUE_STRING) {
                        changes = new BigDecimal(parser.getText().trim());
                    }
                }
                parser.skipChildren();
            }
            return parser.nextToken() == null && changes != null && changes.signum() == 0;
        } catch (IOException | NumberFormatException invalidSummary) {
            return false;
        }
    }
}
