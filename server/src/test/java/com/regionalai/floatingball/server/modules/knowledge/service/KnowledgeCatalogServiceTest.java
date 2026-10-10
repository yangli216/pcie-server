package com.regionalai.floatingball.server.modules.knowledge.service;

import com.fasterxml.jackson.databind.node.*;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeCatalogServiceTest {
    @TempDir Path directory;
    KnowledgeTestFixture fixture;
    @BeforeEach void setup() throws Exception { fixture = new KnowledgeTestFixture(directory); }

    @Test void disabledWorksWithoutAFileOrOrganization() throws Exception {
        fixture.properties.setEnabled(false); Files.delete(fixture.file);
        assertFalse(fixture.catalog.list(null).isEnabled());
        assertTrue(fixture.catalog.list(null).getCollections().isEmpty());
        assertCode("KNOWLEDGE_NOT_READY", () -> fixture.catalog.resolve(KnowledgeTestFixture.device(), "synthetic", "synthetic-v1"));
    }

    @Test void onlyApprovedInScopeEffectiveCollectionsAreListed() throws Exception {
        ObjectNode root = KnowledgeTestFixture.root(); ArrayNode collections = (ArrayNode) root.path("collections");
        collections.add(KnowledgeTestFixture.collection("foreign", "OTHER-ORG"));
        collections.add(KnowledgeTestFixture.collection("draft", "ORG-SYNTHETIC").put("reviewStatus", "DRAFT"));
        collections.add(KnowledgeTestFixture.collection("revoked", "ORG-SYNTHETIC").put("reviewStatus", "REVOKED"));
        collections.add(KnowledgeTestFixture.collection("future", "ORG-SYNTHETIC").put("effectiveFrom", "2026-10-01T00:00:00Z"));
        collections.add(KnowledgeTestFixture.collection("expired", "ORG-SYNTHETIC").put("effectiveUntil", KnowledgeTestFixture.NOW.toString()));
        fixture.write(root);
        assertEquals(1, fixture.catalog.list(KnowledgeTestFixture.device()).getCollections().size());
        assertEquals("synthetic", fixture.catalog.list(KnowledgeTestFixture.device()).getCollections().get(0).getId());
    }

    @Test void effectiveFromIsInclusiveAndScopeCannotBeInferred() throws Exception {
        ObjectNode root = KnowledgeTestFixture.root(); KnowledgeTestFixture.first(root).put("effectiveFrom", KnowledgeTestFixture.NOW.toString());
        fixture.write(root); assertNotNull(fixture.release());
        assertTrue(fixture.catalog.list(KnowledgeTestFixture.device("UNKNOWN")).getCollections().isEmpty());
        assertCode("KNOWLEDGE_NOT_READY", () -> fixture.catalog.resolve(KnowledgeTestFixture.device("UNKNOWN"), "synthetic", "synthetic-v1"));
        assertCode("KNOWLEDGE_NOT_READY", () -> fixture.catalog.list(KnowledgeTestFixture.device(null)));
    }

    @Test void versionMustMatchAndCatalogIsReloadedOnEveryRequest() throws Exception {
        String original = fixture.release().getManifestSha256();
        assertEquals(KnowledgeJson.sha256(Files.readAllBytes(fixture.file)), original);
        assertCode("KNOWLEDGE_VERSION_CHANGED", () -> fixture.catalog.resolve(KnowledgeTestFixture.device(), "synthetic", "old"));
        ObjectNode changed = KnowledgeTestFixture.root(); KnowledgeTestFixture.first(changed).put("releaseVersion", "synthetic-v2"); fixture.write(changed);
        assertCode("KNOWLEDGE_VERSION_CHANGED", fixture::release);
        KnowledgeTestFixture.first(changed).put("reviewStatus", "REVOKED"); fixture.write(changed);
        assertCode("KNOWLEDGE_NOT_READY", () -> fixture.catalog.resolve(KnowledgeTestFixture.device(), "synthetic", "synthetic-v2"));
    }

    @Test void duplicateCollectionsAndChunksFailClosed() throws Exception {
        ObjectNode duplicate = KnowledgeTestFixture.root(); ((ArrayNode) duplicate.path("collections")).add(KnowledgeTestFixture.first(duplicate).deepCopy());
        fixture.write(duplicate); assertCode("KNOWLEDGE_NOT_READY", fixture::release);
        ObjectNode root = KnowledgeTestFixture.root(); ArrayNode chunks = (ArrayNode) KnowledgeTestFixture.first(root).path("documents").get(0).path("chunks");
        chunks.add(chunks.get(0).deepCopy()); fixture.write(root); assertCode("KNOWLEDGE_NOT_READY", fixture::release);
    }

    @Test void approvedReleaseRequiresDocumentsHashesAndExplicitOrganizations() throws Exception {
        for (String field : new String[]{"organizationIds", "documents", "connectionId", "knowledgeBaseId", "retrievalVersion"}) {
            ObjectNode root = KnowledgeTestFixture.root(); KnowledgeTestFixture.first(root).remove(field); fixture.write(root);
            assertCode("KNOWLEDGE_NOT_READY", fixture::release);
        }
        ObjectNode root = KnowledgeTestFixture.root(); ((ObjectNode) KnowledgeTestFixture.first(root).path("documents").get(0).path("chunks").get(0)).put("contentSha256", "unreviewed");
        fixture.write(root); assertCode("KNOWLEDGE_NOT_READY", fixture::release);
    }

    @Test void invalidDatesIdentifiersSchemaAndMalformedJsonFailClosed() throws Exception {
        ObjectNode root = KnowledgeTestFixture.root(); KnowledgeTestFixture.first(root).put("knowledgeBaseId", "../foreign");
        fixture.write(root); assertCode("KNOWLEDGE_NOT_READY", fixture::release);
        root = KnowledgeTestFixture.root(); KnowledgeTestFixture.first(root).put("effectiveFrom", "2026-10-01T00:00:00Z").put("effectiveUntil", "2026-09-30T00:00:00Z");
        fixture.write(root); assertCode("KNOWLEDGE_NOT_READY", fixture::release);
        root = KnowledgeTestFixture.root(); root.put("schemaVersion", "unknown"); fixture.write(root); assertCode("KNOWLEDGE_NOT_READY", fixture::release);
        Files.write(fixture.file, "{\"schemaVersion\":\"a\",\"schemaVersion\":\"b\"}".getBytes(StandardCharsets.UTF_8)); assertCode("KNOWLEDGE_NOT_READY", fixture::release);
    }

    @Test void missingRelativeAndOversizedCatalogFailClosed() throws Exception {
        fixture.properties.setCatalogFile("catalog.json"); assertCode("KNOWLEDGE_NOT_READY", fixture::release);
        fixture.properties.setCatalogFile(fixture.file.toAbsolutePath().toString()); Files.delete(fixture.file); assertCode("KNOWLEDGE_NOT_READY", fixture::release);
        Files.write(fixture.file, new byte[8 * 1024 * 1024 + 1]); assertCode("KNOWLEDGE_NOT_READY", fixture::release);
    }

    @Test void hashesPreserveUnicodeNewlinesAndWhitespaceAndReadersEnforceBounds() throws Exception {
        assertNotEquals(KnowledgeJson.sha256(KnowledgeTestFixture.CONTENT), KnowledgeJson.sha256(KnowledgeTestFixture.CONTENT + " "));
        assertNotEquals(KnowledgeJson.sha256(KnowledgeTestFixture.CONTENT), KnowledgeJson.sha256(KnowledgeTestFixture.CONTENT.replace("\n", "\r\n")));
        assertArrayEquals(new byte[4], KnowledgeJson.readBounded(new ByteArrayInputStream(new byte[4]), 4));
        assertThrows(java.io.IOException.class, () -> KnowledgeJson.readBounded(new ByteArrayInputStream(new byte[5]), 4));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).getCode());
    }
}
