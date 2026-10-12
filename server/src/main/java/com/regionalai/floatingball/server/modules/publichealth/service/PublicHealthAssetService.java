package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

@Service
public class PublicHealthAssetService {
    private final PublicHealthProperties properties;
    private final ObjectMapper mapper;
    public PublicHealthAssetService(PublicHealthProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public Pack load(AiDevice device, String capability) {
        String failure = "health_prescription".equals(capability)?"POLICY_NOT_READY":"KNOWLEDGE_NOT_READY";
        try {
            if (properties.getAssetDirectory() == null || properties.getAssetDirectory().trim().isEmpty()) {
                throw unavailable(failure);
            }
            String relative = properties.getOrgAssets().get(device.getIdOrg());
            if (relative == null || relative.trim().isEmpty()) {
                throw unavailable(failure);
            }
            Path root = Paths.get(properties.getAssetDirectory()).toRealPath();
            Path directory = root.resolve(relative).normalize().toRealPath();
            if (!directory.startsWith(root)) {
                throw unavailable(failure);
            }
            JsonNode manifest = mapper.readTree(read(directory,"manifest.json", 1024*1024));
            if (!device.getIdOrg().equals(manifest.path("organizationId").asText()) || !"APPROVED".equals(manifest.path("reviewStatus").asText()) || !contains(manifest.path("capabilities"),
            capability)) {
                throw unavailable(failure);
            }
            ArrayNode versions = mapper.createArrayNode();
            JsonNode policy = null, recipes = null;
            if ("health_prescription".equals(capability)) {
                policy = asset(directory, manifest.path("policy"), versions, 4*1024*1024);
                validatePolicy(policy);
            } else {
                recipes = asset(directory, manifest.path("recipes"), versions, 64*1024*1024);
                validateRecipes(recipes);
            }
            return new Pack(policy, recipes, versions);
        } catch (PublicHealthException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(failure);
        }
    }

    private JsonNode asset(Path directory, JsonNode entry, ArrayNode versions, int max) throws Exception {
        required(entry,"assetId", 128);
        required(entry,"version", 128);
        required(entry,"file");
        required(entry,"sha256");
        byte[] bytes = read(directory, entry.path("file").asText(), max);
        if (!PublicHealthJson.sha256(bytes).equals(entry.path("sha256").asText())) {
            throw new IllegalArgumentException("asset hash");
        }
        ObjectNode version = mapper.createObjectNode();
        version.put("assetId", entry.path("assetId").asText());
        version.put("version", entry.path("version").asText());
        version.put("sha256", entry.path("sha256").asText());
        versions.add(version);
        return mapper.readTree(bytes);
    }

    private byte[] read(Path directory, String name, int max) throws Exception {
        Path file = directory.resolve(name).normalize().toRealPath();
        if (!file.startsWith(directory) || !Files.isRegularFile(file) || Files.size(file)>max) {
            throw new IllegalArgumentException("asset path/size");
        }
        return Files.readAllBytes(file);
    }

    private static void required(JsonNode value, String name) {
        required(value, name, 256);
    }

    private static void required(JsonNode value, String name, int maxLength) {
        JsonNode field = value.path(name);
        if (!field.isTextual()) {
            throw new IllegalArgumentException("asset field");
        }
        String text = field.asText();
        if (text.trim().isEmpty() || text.codePointCount(0, text.length()) > maxLength) {
            throw new IllegalArgumentException("asset field");
        }
    }

    private boolean contains(JsonNode values, String expected) {
        if (values.isArray()) {
            for (JsonNode v:values) {
                if (expected.equals(v.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void validatePolicy(JsonNode policy) {
        JsonNode checks = policy.path("checkWhitelist");
        if (!checks.isArray() || checks.size() == 0 || checks.size()>1000) {
            throw unavailable("POLICY_NOT_READY");
        }
        Set<String> ids = new HashSet<String>();
        for (JsonNode check:checks) {
            required(check,"code");
            required(check,"name");
            required(check,"prep");
            if (!ids.add(check.path("code").asText()) || !nonnegativeInteger(check.path("minIntervalDays")) || !nonnegativeInteger(check.path("abnormalRetestIntervalDays"))) {
                throw unavailable("POLICY_NOT_READY");
            }
            PublicHealthCheckEligibility.validate(check.path("eligibility"));
        }
        for (JsonNode check:checks) {
            JsonNode criteria = check.path("abnormalCriteria");
            if (criteria.isMissingNode()) {
                continue;
            }
            if (!criteria.isArray() || criteria.size() == 0 || criteria.size()>32) {
                throw unavailable("POLICY_NOT_READY");
            }
            for (JsonNode criterion:criteria) {
                required(criterion,"observationCode");
                required(criterion,"unit");
                if (!java.util.Arrays.asList("gt","gte","lt","lte","eq").contains(criterion.path("operator").asText()) || !criterion.path("threshold").isNumber() || !nonnegativeInteger(criterion.path("maxEvidenceAgeDays"))) {
                    throw unavailable("POLICY_NOT_READY");
                }
            }
        }
        if (!policy.path("medicationReminders").isObject() || policy.path("medicationReminders").size() == 0 || !policy.path("lifestyleRules").isArray() || policy.path("lifestyleRules").size() == 0) {
            throw unavailable("POLICY_NOT_READY");
        }
        for (JsonNode v:policy.path("medicationReminders")) {
            if (!v.isTextual() || v.asText().trim().isEmpty()) {
                throw unavailable("POLICY_NOT_READY");
            }
        }
        for (JsonNode v:policy.path("lifestyleRules")) {
            if (!v.isTextual() || v.asText().trim().isEmpty()) {
                throw unavailable("POLICY_NOT_READY");
            }
        }
    }

    private boolean nonnegativeInteger(JsonNode v) {
        return v.isIntegralNumber() && v.canConvertToInt() && v.asInt() >= 0 && v.asInt() <= 36500;
    }

    static void validateRecipeMetadata(JsonNode recipes) {
        if (recipes == null || !recipes.isObject()) {
            throw unavailable("KNOWLEDGE_NOT_READY");
        }
        required(recipes,"embeddingModel");
        required(recipes,"chunkingVersion");
        if (!"bge-reranker-large".equals(recipes.path("reranker").asText())) {
            throw unavailable("KNOWLEDGE_NOT_READY");
        }
    }

    /** Validates index structure only; load still enforces organization, approval and file hashes. */
    public static void validateRecipes(JsonNode recipes) {
        validateRecipeMetadata(recipes);
        int dim = recipes.path("dimensions").asInt();
        if (!recipes.path("dimensions").isIntegralNumber() || !recipes.path("dimensions").canConvertToInt() || dim<1 || dim>8192) {
            throw unavailable("KNOWLEDGE_NOT_READY");
        }
        JsonNode docs = recipes.path("documents");
        validateRecipeDocuments(docs);
        for (JsonNode doc:docs) {
            vector(doc.path("vector"), dim);
        }
    }

    static void validateRecipeDocuments(JsonNode docs) {
        if (docs == null || !docs.isArray() || docs.size() == 0 || docs.size()>50000) {
            throw unavailable("KNOWLEDGE_NOT_READY");
        }
        Set<String> ids = new HashSet<String>();
        for (JsonNode doc:docs) {
            required(doc,"id");
            required(doc,"documentId");
            required(doc,"source");
            required(doc,"title");
            if (!ids.add(doc.path("id").asText()) || !doc.path("text").isTextual() || doc.path("text").asText().trim().isEmpty() || doc.path("text").asText().length()>16000) {
                throw unavailable("KNOWLEDGE_NOT_READY");
            }
        }
    }

    static double[] vector(JsonNode array, int dimensions) {
        if (!array.isArray() || array.size() != dimensions) {
            throw unavailable("KNOWLEDGE_NOT_READY");
        }
        double[] result = new double[dimensions];
        double norm = 0;
        for (int i = 0; i<dimensions; i++) {
            if (!array.get(i).isNumber()) {
                throw unavailable("KNOWLEDGE_NOT_READY");
            }
            result[i] = array.get(i).asDouble();
            if (!Double.isFinite(result[i])) {
                throw unavailable("KNOWLEDGE_NOT_READY");
            }
            norm += result[i]*result[i];
        }
        if (!Double.isFinite(norm) || norm <= 0) {
            throw unavailable("KNOWLEDGE_NOT_READY");
        }
        return result;
    }

    static PublicHealthException unavailable(String code) {
        return new PublicHealthException(503, code,"机构规则或知识资产尚未就绪");
    }

    public static final class Pack {
        public final JsonNode policy, recipes;
        public final ArrayNode versions;
        public Pack(JsonNode policy, JsonNode recipes, ArrayNode versions) {
            this.policy = policy;
            this.recipes = recipes;
            this.versions = versions;
        }
    }
}
