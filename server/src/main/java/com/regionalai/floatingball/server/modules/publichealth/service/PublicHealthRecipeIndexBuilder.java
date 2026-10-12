package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;

import java.util.ArrayList;
import java.util.List;

/** Builds an in-memory technical index. Publication and medical approval are separate operations. */
public class PublicHealthRecipeIndexBuilder {
    private final PublicHealthEmbeddingClient embeddings;
    private final ObjectMapper mapper;

    public PublicHealthRecipeIndexBuilder(PublicHealthEmbeddingClient embeddings, ObjectMapper mapper) {
        this.embeddings = embeddings;
        this.mapper = mapper;
    }

    public ObjectNode build(JsonNode candidate, ResolvedAiConfig config, int batchSize) {
        if (batchSize < 1 || batchSize > 64) {
            throw new IllegalArgumentException("batchSize must be between 1 and 64");
        }
        PublicHealthAssetService.validateRecipeMetadata(candidate);
        JsonNode inputDocuments = candidate.path("documents");
        PublicHealthAssetService.validateRecipeDocuments(inputDocuments);
        for (JsonNode document : inputDocuments) {
            if (document.has("vector")) {
                throw new IllegalArgumentException("candidate must not contain vectors");
            }
        }

        String model = candidate.path("embeddingModel").asText();
        ArrayNode documents = mapper.createArrayNode();
        Integer dimensions = null;
        for (int start = 0; start < inputDocuments.size(); start += batchSize) {
            int end = Math.min(inputDocuments.size(), start + batchSize);
            List<String> texts = new ArrayList<String>(end - start);
            for (int i = start; i < end; i++) {
                texts.add(inputDocuments.get(i).path("text").asText());
            }
            double[][] vectors = embeddings.embed(config, model, texts, dimensions);
            if (vectors == null || vectors.length != texts.size()) {
                throw PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY");
            }
            for (int i = 0; i < vectors.length; i++) {
                double[] vector = vectors[i];
                if (vector == null || vector.length < 1 || vector.length > 8192) {
                    throw PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY");
                }
                if (dimensions == null) {
                    dimensions = vector.length;
                }
                ArrayNode values = mapper.createArrayNode();
                for (double coordinate : vector) {
                    values.add(coordinate);
                }
                // Recheck at the build boundary so alternate/fake clients cannot publish invalid vectors.
                PublicHealthAssetService.vector(values, dimensions);
                ObjectNode document = ((ObjectNode) inputDocuments.get(start + i)).deepCopy();
                document.set("vector", values);
                documents.add(document);
            }
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("embeddingModel", model);
        result.put("dimensions", dimensions);
        result.put("chunkingVersion", candidate.path("chunkingVersion").asText());
        result.put("reranker", candidate.path("reranker").asText());
        result.set("documents", documents);
        ObjectNode provenance = result.putObject("provenance");
        provenance.put("status", "INDEX_BUILT_PENDING_REVIEW");
        provenance.put("reviewStatus", "NOT_ASSERTED");
        provenance.put("documentCount", documents.size());
        provenance.put("batchSize", batchSize);
        provenance.put("contentPreserved", true);
        provenance.put("embeddingModelWeightsVerified", false);
        if (candidate.has("provenance")) {
            provenance.set("source", candidate.path("provenance").deepCopy());
        }
        try {
            provenance.put("inputDocumentsSha256", PublicHealthJson.sha256(mapper.writeValueAsBytes(inputDocuments)));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("cannot serialize candidate documents");
        }
        PublicHealthAssetService.validateRecipes(result);
        return result;
    }
}
