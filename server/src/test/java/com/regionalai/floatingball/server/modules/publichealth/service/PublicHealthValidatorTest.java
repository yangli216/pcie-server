package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicHealthValidatorTest {
    private final PublicHealthValidator v = new PublicHealthValidator(PublicHealthTestFixture.M);

    @Test void validatesBothCapabilitiesAndCanonicalObjectOrder() throws Exception {
        for (String cap:new String[] {
            "health_prescription","weight_management"
        }
        ) {
            ObjectNode r = PublicHealthTestFixture.request(cap);
            PublicHealthValidator.Parsed first = v.parse(PublicHealthTestFixture.M.writeValueAsBytes(r));
            ObjectNode reverse = PublicHealthTestFixture.M.createObjectNode();
            java.util.List<String> keys = new java.util.ArrayList<>();
            r.fieldNames().forEachRemaining(keys::add);
            java.util.Collections.reverse(keys);
            for (String k:keys)reverse.set(k, r.get(k));
            assertEquals(first.inputHash, v.parse(PublicHealthTestFixture.M.writeValueAsBytes(reverse)).inputHash);
        }
    }

    @Test void rejectsConflictingMeasurementBeforeGeneration() throws Exception {
        ObjectNode r = PublicHealthTestFixture.request("weight_management");
        ((ObjectNode)r.path("facts").path("observations").get(0)).put("value", 169);
        assertCode(r,"INVALID_REQUEST");
    }

    @Test void requiresObservationReferencesAndKnownSex() throws Exception {
        ObjectNode r = PublicHealthTestFixture.request("weight_management");
        ((ObjectNode)r.path("facts").path("weightProfile")).remove("weightObservationId");
        assertCode(r,"MISSING_FACTS");
        r = PublicHealthTestFixture.request("weight_management");
        ((ObjectNode)r.get("patient")).put("sex","unknown");
        assertCode(r,"MISSING_FACTS");
    }

    @Test void rejectsUnknownFieldsBadTypesAndDuplicateJsonKeys() throws Exception {
        ObjectNode r = PublicHealthTestFixture.request("health_prescription");
        r.put("apiKey","synthetic-not-a-secret");
        assertCode(r,"INVALID_REQUEST");
        r = PublicHealthTestFixture.request("health_prescription");
        ((ObjectNode)r.get("patient")).put("ageYears","40");
        assertCode(r,"INVALID_REQUEST");
        String json = PublicHealthTestFixture.request("health_prescription").toString();
        String duplicate = "{\"requestId\":\"duplicate\","+json.substring(1);
        assertThrows(PublicHealthException.class, () -> v.parse(duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void enforcesMappingsAndManualSourceIdentity() throws Exception {
        ObjectNode r = PublicHealthTestFixture.request("health_prescription");
        ((ArrayNode)r.path("writebackTargets").get(0).path("sourceSections")).remove(0);
        assertCode(r,"INVALID_REQUEST");
        r = PublicHealthTestFixture.request("weight_management");
        ((ObjectNode)r.path("facts").path("observations").get(0).get("source")).put("system","PCIE_MANUAL");
        assertCode(r,"INVALID_REQUEST");
    }

    @Test void rejectsOversizedBodyAndTimestampWithoutOffset() throws Exception {
        assertEquals(413, assertThrows(PublicHealthException.class, () -> v.parse(new byte[524289])).getStatus());
        ObjectNode r = PublicHealthTestFixture.request("health_prescription");
        r.put("snapshotAt","2026-09-29T10:00:00");
        assertCode(r,"INVALID_REQUEST");
    }

    @Test void equivalentNumericSnapshotsHaveSameHashWithoutCoercingStrings() throws Exception {
        ObjectNode integer = PublicHealthTestFixture.request("weight_management");
        ArrayNode observations = (ArrayNode) integer.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "ZERO", "synthetic-zero", "synthetic-unit", 0);
        ObjectNode floating = integer.deepCopy();
        ((ObjectNode) floating.path("patient")).put("ageYears", 40.0);
        ((ObjectNode) floating.path("facts").path("weightProfile")).put("heightCm", 170.0);
        ((ObjectNode) floating.path("facts").path("observations").get(0)).put("value", 170.0);
        ((ObjectNode) floating.path("facts").path("observations").get(2)).put("value", -0.0);
        String expected = v.parse(PublicHealthTestFixture.M.writeValueAsBytes(integer)).inputHash;
        assertEquals(expected, v.parse(PublicHealthTestFixture.M.writeValueAsBytes(floating)).inputHash);

        ((ObjectNode) floating.path("facts").path("observations").get(2)).put("value", "0");
        assertNotEquals(expected, v.parse(PublicHealthTestFixture.M.writeValueAsBytes(floating)).inputHash);
        ((ObjectNode) floating.path("patient")).put("ageYears", 40.5);
        assertCode(floating, "INVALID_REQUEST");
    }

    @Test void rejectsNumbersOutsideCrossLanguageSafeRange() throws Exception {
        ObjectNode request = PublicHealthTestFixture.request("weight_management");
        ArrayNode observations = (ArrayNode) request.path("facts").path("observations");
        PublicHealthTestFixture.observation(observations, "BOUND", "synthetic-bound", "synthetic-unit", 1);
        ObjectNode observation = (ObjectNode) observations.get(2);
        observation.put("value", 9007199254740991L);
        assertNotNull(v.parse(PublicHealthTestFixture.M.writeValueAsBytes(request)));
        for (String invalid : new String[] {
                "9007199254740992", "-9007199254740992", "9007199254740991.1", "1e309"
        }) {
            observation.set("value", new DecimalNode(new java.math.BigDecimal(invalid)));
            assertCode(request, "INVALID_REQUEST");
        }
    }
    private void assertCode(ObjectNode r, String code) throws Exception {
        byte[] bytes = PublicHealthTestFixture.M.writeValueAsBytes(r);
        assertEquals(code, assertThrows(PublicHealthException.class, () -> v.parse(bytes)).getCode());
    }
}
