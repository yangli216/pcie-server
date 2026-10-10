package com.regionalai.floatingball.server.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.knowledge.dto.KnowledgeCollectionView;
import com.regionalai.floatingball.server.modules.knowledge.dto.KnowledgeCollectionsResponse;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class KnowledgeCatalogService {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final KnowledgeRetrievalProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public KnowledgeCatalogService(KnowledgeRetrievalProperties properties, ObjectMapper mapper) {
        this(properties, mapper, Clock.systemUTC());
    }

    KnowledgeCatalogService(KnowledgeRetrievalProperties properties, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.clock = clock;
    }

    public KnowledgeCollectionsResponse list(AiDevice device) {
        if (!properties.isEnabled()) return new KnowledgeCollectionsResponse(false, Collections.emptyList());
        String organization = organization(device);
        List<KnowledgeCollectionView> views = new ArrayList<KnowledgeCollectionView>();
        for (Release release : load()) if (release.allowed(organization, clock.instant())) views.add(release.view());
        return new KnowledgeCollectionsResponse(true, Collections.unmodifiableList(views));
    }

    public Release resolve(AiDevice device, String id, String version) {
        if (!properties.isEnabled()) throw notReady();
        String organization = organization(device);
        for (Release release : load()) {
            if (release.id.equals(id) && release.allowed(organization, clock.instant())) {
                if (!release.releaseVersion.equals(version)) {
                    throw new BusinessException("KNOWLEDGE_VERSION_CHANGED", "知识版本已更新，请刷新集合后重新检索");
                }
                return release;
            }
        }
        throw notReady();
    }

    private List<Release> load() {
        try {
            if (properties.getCatalogFile() == null || properties.getCatalogFile().trim().isEmpty()) throw notReady();
            Path configuredPath = Paths.get(properties.getCatalogFile());
            if (!configuredPath.isAbsolute()) throw notReady();
            Path path = configuredPath.toRealPath();
            if (!Files.isRegularFile(path)) throw notReady();
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path)) { bytes = KnowledgeJson.readBounded(input, 8 * 1024 * 1024); }
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !"pcie-knowledge-catalog/v1".equals(root.path("schemaVersion").asText())) throw notReady();
            JsonNode collections = root.path("collections");
            if (!collections.isArray() || collections.size() > 500) throw notReady();
            String manifestHash = KnowledgeJson.sha256(bytes);
            List<Release> releases = new ArrayList<Release>();
            Set<String> ids = new HashSet<String>();
            for (JsonNode item : collections) {
                String id = identifier(item, "id");
                if (!ids.add(id)) throw notReady();
                String status = text(item, "reviewStatus", 32);
                if (!Arrays.asList("DRAFT", "REVOKED", "APPROVED").contains(status)) throw notReady();
                if (!"APPROVED".equals(status)) continue;
                Set<String> orgs = new HashSet<String>();
                if (!item.path("organizationIds").isArray() || item.path("organizationIds").size() == 0) throw notReady();
                for (JsonNode org : item.path("organizationIds")) {
                    if (!org.isTextual() || !ID.matcher(org.asText()).matches() || !orgs.add(org.asText())) throw notReady();
                }
                Instant from = instant(item, "effectiveFrom"), until = instant(item, "effectiveUntil");
                if (from != null && until != null && !from.isBefore(until)) throw notReady();
                JsonNode docs = item.path("documents");
                if (!docs.isArray() || docs.size() == 0 || docs.size() > 2000) throw notReady();
                Map<String, Document> documents = new LinkedHashMap<String, Document>();
                Set<String> chunksSeen = new HashSet<String>();
                for (JsonNode doc : docs) {
                    String knowledgeId = identifier(doc, "knowledgeId");
                    JsonNode chunks = doc.path("chunks");
                    if (!chunks.isArray() || chunks.size() == 0 || chunks.size() > 10000) throw notReady();
                    Map<String, String> hashes = new LinkedHashMap<String, String>();
                    for (JsonNode chunk : chunks) {
                        String chunkId = identifier(chunk, "id"), hash = text(chunk, "contentSha256", 64);
                        if (!hash.matches("[a-f0-9]{64}") || !chunksSeen.add(chunkId)) throw notReady();
                        hashes.put(chunkId, hash);
                    }
                    Document document = new Document(knowledgeId, identifier(doc, "sourceId"), text(doc, "title", 256),
                        text(doc, "sourceVersion", 128), Collections.unmodifiableMap(hashes));
                    if (documents.put(knowledgeId, document) != null) throw notReady();
                }
                releases.add(new Release(id, text(item, "name", 256), identifier(item, "connectionId"),
                    identifier(item, "knowledgeBaseId"), text(item, "releaseVersion", 128), text(item, "retrievalVersion", 128),
                    manifestHash, Collections.unmodifiableSet(orgs), from, until, Collections.unmodifiableMap(documents)));
            }
            return releases;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw notReady();
        }
    }

    private static String organization(AiDevice device) {
        if (device == null || device.getIdOrg() == null || device.getIdOrg().trim().isEmpty()) throw notReady();
        return device.getIdOrg();
    }

    static String identifier(JsonNode node, String field) {
        String value = text(node, field, 128);
        if (!ID.matcher(value).matches()) throw notReady();
        return value;
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().trim().isEmpty() || value.asText().length() > max) throw notReady();
        return value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        return node.has(field) ? Instant.parse(text(node, field, 64)) : null;
    }

    static BusinessException notReady() {
        return new BusinessException("KNOWLEDGE_NOT_READY", "当前机构尚无可用的已批准知识资料，请联系管理员");
    }

    @Getter
    public static final class Release {
        private final String id, name, connectionId, knowledgeBaseId, releaseVersion, retrievalVersion, manifestSha256;
        private final Set<String> organizations;
        private final Instant effectiveFrom, effectiveUntil;
        private final Map<String, Document> documents;
        Release(String id, String name, String connectionId, String knowledgeBaseId, String releaseVersion,
                String retrievalVersion, String manifestSha256, Set<String> organizations, Instant from, Instant until,
                Map<String, Document> documents) {
            this.id = id; this.name = name; this.connectionId = connectionId; this.knowledgeBaseId = knowledgeBaseId;
            this.releaseVersion = releaseVersion; this.retrievalVersion = retrievalVersion; this.manifestSha256 = manifestSha256;
            this.organizations = organizations; this.effectiveFrom = from; this.effectiveUntil = until; this.documents = documents;
        }
        boolean allowed(String organization, Instant now) {
            return organizations.contains(organization) && (effectiveFrom == null || !now.isBefore(effectiveFrom))
                && (effectiveUntil == null || now.isBefore(effectiveUntil));
        }
        public KnowledgeCollectionView view() {
            return new KnowledgeCollectionView(id, name, releaseVersion, retrievalVersion, documents.size());
        }
    }

    @Getter
    public static final class Document {
        private final String knowledgeId, sourceId, title, sourceVersion;
        private final Map<String, String> chunks;
        Document(String id, String source, String title, String version, Map<String, String> chunks) {
            this.knowledgeId = id; this.sourceId = source; this.title = title; this.sourceVersion = version; this.chunks = chunks;
        }
    }
}
