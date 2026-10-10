package com.regionalai.floatingball.server.modules.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.knowledge.dto.KnowledgeSearchRequest;
import java.nio.file.*;
import java.time.*;

final class KnowledgeTestFixture {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String CONTENT = "合成工程资料，仅用于验证检索契约。\n保持正文及空白原样。";
    static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    final KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
    final Path file;
    final KnowledgeCatalogService catalog;

    KnowledgeTestFixture(Path directory) throws Exception {
        file = directory.resolve("catalog.json");
        properties.setEnabled(true);
        properties.setCatalogFile(file.toAbsolutePath().toString());
        KnowledgeRetrievalProperties.Connection connection = new KnowledgeRetrievalProperties.Connection();
        connection.setBaseUrl("http://127.0.0.1:8787");
        connection.setApiKey("synthetic-retrieval-key");
        properties.getConnections().put("default", connection);
        catalog = new KnowledgeCatalogService(properties, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        write(root());
    }

    static ObjectNode root() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "pcie-knowledge-catalog/v1");
        root.putArray("collections").add(collection("synthetic", "ORG-SYNTHETIC"));
        return root;
    }

    static ObjectNode collection(String id, String organization) {
        ObjectNode collection = MAPPER.createObjectNode();
        collection.put("id", id).put("name", "合成资料集").put("connectionId", "default");
        collection.put("knowledgeBaseId", "KB-SYNTHETIC").put("releaseVersion", "synthetic-v1");
        collection.put("retrievalVersion", "weknora-v0.8.2-synthetic").put("reviewStatus", "APPROVED");
        collection.putArray("organizationIds").add(organization);
        ObjectNode document = collection.putArray("documents").addObject();
        document.put("knowledgeId", "DOC-SYNTHETIC").put("sourceId", "SOURCE-SYNTHETIC");
        document.put("title", "合成工程资料").put("sourceVersion", "source-v1");
        document.putArray("chunks").addObject().put("id", "CHUNK-SYNTHETIC")
            .put("contentSha256", KnowledgeJson.sha256(CONTENT));
        return collection;
    }

    static ObjectNode first(ObjectNode root) { return (ObjectNode) root.path("collections").get(0); }
    static AiDevice device(String org) { AiDevice device = new AiDevice(); device.setIdOrg(org); device.setIdDevice("DEVICE-SYNTHETIC"); return device; }
    static AiDevice device() { return device("ORG-SYNTHETIC"); }
    static KnowledgeSearchRequest request() {
        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setCollectionId("synthetic"); request.setReleaseVersion("synthetic-v1"); request.setQuery("  合成资料  ");
        return request;
    }
    static ObjectNode upstream(String content) {
        ObjectNode root = MAPPER.createObjectNode(); root.put("success", true);
        root.putArray("data").addObject().put("id", "CHUNK-SYNTHETIC").put("knowledge_id", "DOC-SYNTHETIC")
            .put("knowledge_base_id", "KB-SYNTHETIC").put("knowledge_title", "不可信上游标题")
            .put("content", content).put("score", 0.9).put("match_type", 0);
        return root;
    }
    void write(ObjectNode root) throws Exception { Files.write(file, MAPPER.writeValueAsBytes(root)); }
    KnowledgeCatalogService.Release release() { return catalog.resolve(device(), "synthetic", "synthetic-v1"); }
}
