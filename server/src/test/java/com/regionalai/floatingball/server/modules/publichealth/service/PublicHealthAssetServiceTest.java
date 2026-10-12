package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicHealthAssetServiceTest {

    @TempDir Path dir;

    @Test void defaultHasNoProductionFallback() {
        PublicHealthAssetService service = new PublicHealthAssetService(new PublicHealthProperties(), PublicHealthTestFixture.M);
        assertEquals("KNOWLEDGE_NOT_READY", assertThrows(PublicHealthException.class, () -> service.load(PublicHealthTestFixture.device(),"weight_management")).getCode());
    }

    @Test void validatesOrgApprovalHashAndAssetContent() throws Exception {
        PublicHealthProperties p = new PublicHealthProperties();
        p.setAssetDirectory(dir.toString());
        p.getOrgAssets().put("ORG-SYNTHETIC","org");
        Path org = Files.createDirectory(dir.resolve("org"));
        byte[] policy = PublicHealthTestFixture.M.writeValueAsBytes(PublicHealthTestFixture.policy());
        Files.write(org.resolve("policy.json"), policy);
        ObjectNode manifest = PublicHealthTestFixture.M.createObjectNode();
        manifest.put("organizationId","ORG-SYNTHETIC");
        manifest.put("reviewStatus","APPROVED");
        manifest.putArray("capabilities").add("health_prescription");
        manifest.putObject("policy").put("assetId","synthetic-policy").put("version","synthetic-v1").put("file","policy.json").put("sha256",
        PublicHealthJson.sha256(policy));
        Files.write(org.resolve("manifest.json"), PublicHealthTestFixture.M.writeValueAsBytes(manifest));
        PublicHealthAssetService service = new PublicHealthAssetService(p, PublicHealthTestFixture.M);
        assertEquals(1, service.load(PublicHealthTestFixture.device(),"health_prescription").versions.size());
        ObjectNode policyEntry = (ObjectNode) manifest.path("policy");
        for (String field : new String[] {"assetId", "version"}) {
            String original = policyEntry.path(field).asText();
            policyEntry.put(field, String.join("", java.util.Collections.nCopies(129, "x")));
            Files.write(org.resolve("manifest.json"), PublicHealthTestFixture.M.writeValueAsBytes(manifest));
            assertEquals("POLICY_NOT_READY", assertThrows(PublicHealthException.class,
                    () -> service.load(PublicHealthTestFixture.device(), "health_prescription")).getCode());
            policyEntry.put(field, original);
        }
        Files.write(org.resolve("manifest.json"), PublicHealthTestFixture.M.writeValueAsBytes(manifest));
        Files.write(org.resolve("policy.json"),"changed".getBytes());
        assertEquals("POLICY_NOT_READY", assertThrows(PublicHealthException.class, () -> service.load(PublicHealthTestFixture.device(),"health_prescription")).getCode());
        Files.write(org.resolve("policy.json"), policy);
        manifest.put("organizationId","OTHER-ORG");
        Files.write(org.resolve("manifest.json"), PublicHealthTestFixture.M.writeValueAsBytes(manifest));
        assertThrows(PublicHealthException.class, () -> service.load(PublicHealthTestFixture.device(),"health_prescription"));
    }

    @Test void rejectsZeroOrMismatchedVectors() {
        assertThrows(PublicHealthException.class, () -> PublicHealthAssetService.vector(PublicHealthTestFixture.M.createArrayNode().add(0).add(0),
        2));
        assertThrows(PublicHealthException.class, () -> PublicHealthAssetService.vector(PublicHealthTestFixture.M.createArrayNode().add(1), 2));
    }

    @Test void validatesEligibilityInsideApprovedHashBoundPolicy() throws Exception {
        PublicHealthProperties properties = new PublicHealthProperties();
        properties.setAssetDirectory(dir.toString()); properties.getOrgAssets().put("ORG-SYNTHETIC", "org");
        Path org = Files.createDirectory(dir.resolve("org"));
        ObjectNode manifest = PublicHealthTestFixture.M.createObjectNode().put("organizationId", "ORG-SYNTHETIC").put("reviewStatus", "APPROVED");
        manifest.putArray("capabilities").add("health_prescription");
        ObjectNode entry = manifest.putObject("policy").put("assetId", "synthetic-policy").put("version", "synthetic-v1").put("file", "policy.json");
        ObjectNode policy = PublicHealthTestFixture.policy();
        ObjectNode eligibility = ((ObjectNode) policy.path("checkWhitelist").get(0)).putObject("eligibility").put("minAgeYears", 18).put("maxAgeYears", 130);
        eligibility.putArray("sexes").add("male").add("female");
        eligibility.putArray("conditionCodes").add("SYNTHETIC-HTN");
        eligibility.putArray("riskLevels").add("high");
        PublicHealthAssetService service = new PublicHealthAssetService(properties, PublicHealthTestFixture.M);
        writePolicy(org, manifest, entry, policy);
        assertEquals(eligibility, service.load(PublicHealthTestFixture.device(), "health_prescription").policy.path("checkWhitelist").get(0).path("eligibility"));
        eligibility.put("maxAgeYears", 131);
        writePolicy(org, manifest, entry, policy);
        assertEquals("POLICY_NOT_READY", assertThrows(PublicHealthException.class,
                () -> service.load(PublicHealthTestFixture.device(), "health_prescription")).getCode());
        eligibility.put("maxAgeYears", 130).put("modelRiskScore", true);
        writePolicy(org, manifest, entry, policy);
        assertEquals("POLICY_NOT_READY", assertThrows(PublicHealthException.class,
                () -> service.load(PublicHealthTestFixture.device(), "health_prescription")).getCode());
    }

    private void writePolicy(Path org, ObjectNode manifest, ObjectNode entry, ObjectNode policy) throws Exception {
        byte[] bytes = PublicHealthTestFixture.M.writeValueAsBytes(policy);
        entry.put("sha256", PublicHealthJson.sha256(bytes));
        Files.write(org.resolve("policy.json"), bytes);
        Files.write(org.resolve("manifest.json"), PublicHealthTestFixture.M.writeValueAsBytes(manifest));
    }
}
