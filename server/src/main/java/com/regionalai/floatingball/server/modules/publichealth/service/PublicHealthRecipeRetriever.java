package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.springframework.stereotype.Service;
import org.springframework.beans.BeanUtils;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Service
public class PublicHealthRecipeRetriever {
    private final PublicHealthProperties properties;
    private final ConfigService configs;
    private final PublicHealthEmbeddingClient embeddings;
    private final ObjectMapper mapper;
    public PublicHealthRecipeRetriever(PublicHealthProperties properties, ConfigService configs, OutboundSecurityService outbound, RestTemplate http,
    ObjectMapper mapper) {
        this.properties = properties;
        this.configs = configs;
        this.embeddings = new PublicHealthEmbeddingClient(outbound, http, mapper);
        this.mapper = mapper;
    }

    public ArrayNode retrieve(AiDevice device, JsonNode recipes, String query) {
        return retrieve(device, recipes, query, () -> {});
    }

    public ArrayNode retrieve(AiDevice device, JsonNode recipes, String query, Runnable checkpoint) {
        checkpoint.run();
        if (!properties.isRetrievalEnabled()) {
            throw PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY");
        }
        ResolvedAiConfig config = configs.resolveByDevice(device);
        String baseUrl = properties.getEmbeddingBaseUrl();
        if (config != null && baseUrl != null && !baseUrl.trim().isEmpty()) {
            // The resolved device config may be shared with chat calls; never mutate it.
            ResolvedAiConfig retrievalConfig = new ResolvedAiConfig();
            BeanUtils.copyProperties(config, retrievalConfig);
            retrievalConfig.setBaseUrl(baseUrl.trim());
            config = retrievalConfig;
        }
        return retrieve(config, recipes, query, checkpoint);
    }

    /** Explicit configuration entry point for offline technical verification; device calls retain their feature gate. */
    public ArrayNode retrieve(ResolvedAiConfig config, JsonNode recipes, String query, Runnable checkpoint) {
        checkpoint.run();
        if (recipes == null || !recipes.path("embeddingModel").isTextual()
                || !recipes.path("dimensions").isIntegralNumber() || !recipes.path("dimensions").canConvertToInt()
                || !recipes.path("documents").isArray() || recipes.path("documents").size() == 0) {
            throw PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY");
        }
        double[] queryVector = embeddings.embed(config, recipes.path("embeddingModel").asText(),
                Collections.singletonList(query), recipes.path("dimensions").asInt())[0];
        List<Scored> candidates = new ArrayList<Scored>();
        for (JsonNode doc:recipes.path("documents")) {
            candidates.add(new Scored(doc, cosine(queryVector, PublicHealthAssetService.vector(doc.path("vector"), queryVector.length))));
        }
        candidates.sort((a, b) -> {
            int order = Double.compare(b.score, a.score);
            return order == 0
                    ? a.doc.path("id").asText().compareTo(b.doc.path("id").asText()) : order;
        });
        int count = Math.min(candidates.size(), Math.max(4, Math.min(100, properties.getRetrievalCandidates())));
        ObjectNode rerank = mapper.createObjectNode();
        rerank.put("model","bge-reranker-large");
        rerank.put("query", query);
        rerank.put("top_n", Math.min(4, count));
        ArrayNode texts = rerank.putArray("documents");
        for (int i = 0; i<count; i++) {
            texts.add(candidates.get(i).doc.path("text").asText());
        }
        checkpoint.run();
        JsonNode ranked = embeddings.post(config,"/rerank", rerank).path("results");
        if (!ranked.isArray() || ranked.size() != Math.min(4, count)) {
            throw PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY");
        }
        Set<Integer> seen = new HashSet<Integer>();
        ArrayNode result = mapper.createArrayNode();
        double previous = Double.POSITIVE_INFINITY;
        for (JsonNode item:ranked) {
            if (!item.path("index").isIntegralNumber() || !item.path("index").canConvertToInt() || !item.path("relevance_score").isNumber()) {
                throw PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY");
            }
            int index = item.path("index").asInt();
            double score = item.path("relevance_score").asDouble();
            if (index<0 || index >= count || !seen.add(index) || !Double.isFinite(score) || score>previous) {
                throw PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY");
            }
            previous = score;
            ObjectNode doc = ((ObjectNode)candidates.get(index).doc).deepCopy();
            doc.remove("vector");
            result.add(doc);
        }
        return result;
    }

    static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i<a.length; i++) {
            dot += a[i]*b[i];
            na += a[i]*a[i];
            nb += b[i]*b[i];
        }
        double score = dot/(Math.sqrt(na)*Math.sqrt(nb));
        if (!Double.isFinite(score)) {
            throw PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY");
        }
        return score;
    }

    private static final class Scored {
        final JsonNode doc;
        final double score;
        Scored(JsonNode doc, double score) {
            this.doc = doc;
            this.score = score;
        }
    }
}
