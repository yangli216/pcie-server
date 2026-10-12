package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService.OutboundCall;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/** Shared protocol validation for offline index construction and online query embeddings. */
public class PublicHealthEmbeddingClient {
    private final OutboundSecurityService outbound;
    private final RestTemplate http;
    private final ObjectMapper mapper;

    public PublicHealthEmbeddingClient(OutboundSecurityService outbound, RestTemplate http, ObjectMapper mapper) {
        this.outbound = outbound;
        this.http = http;
        this.mapper = mapper;
    }

    public double[][] embed(ResolvedAiConfig config, String model, List<String> inputs, Integer expectedDimensions) {
        if (model == null || model.trim().isEmpty() || inputs == null || inputs.isEmpty()
                || (expectedDimensions != null && (expectedDimensions < 1 || expectedDimensions > 8192))) {
            throw unavailable();
        }
        ObjectNode payload = mapper.createObjectNode().put("model", model);
        ArrayNode texts = payload.putArray("input");
        for (String input : inputs) {
            if (input == null || input.trim().isEmpty() || input.length() > 16000) {
                throw unavailable();
            }
            texts.add(input);
        }
        JsonNode response = post(config, "/embeddings", payload);
        JsonNode data = response.path("data");
        if (!response.path("model").isTextual() || !model.equals(response.path("model").asText())
                || !data.isArray() || data.size() != inputs.size()) {
            throw unavailable();
        }
        double[][] result = new double[inputs.size()][];
        Integer dimensions = expectedDimensions;
        for (JsonNode item : data) {
            JsonNode indexValue = item.path("index");
            if (!indexValue.isIntegralNumber() || !indexValue.canConvertToInt()) {
                throw unavailable();
            }
            int index = indexValue.asInt();
            if (index < 0 || index >= result.length || result[index] != null) {
                throw unavailable();
            }
            JsonNode vector = item.path("embedding");
            if (!vector.isArray() || vector.size() < 1 || vector.size() > 8192) {
                throw unavailable();
            }
            if (dimensions == null) {
                dimensions = vector.size();
            }
            result[index] = PublicHealthAssetService.vector(vector, dimensions);
        }
        for (double[] vector : result) {
            if (vector == null) {
                throw unavailable();
            }
        }
        return result;
    }

    JsonNode post(ResolvedAiConfig config, String path, ObjectNode body) {
        OutboundCall call = null;
        try {
            if (config == null || config.getBaseUrl() == null || config.getBaseUrl().trim().isEmpty()
                    || config.getApiKey() == null || config.getApiKey().trim().isEmpty()
                    || !("/embeddings".equals(path) || "/rerank".equals(path))) {
                throw unavailable();
            }
            call = outbound.acquireHttp(config.getBaseUrl().replaceAll("/+$", "") + path,
                    "public-health-retrieval");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(config.getApiKey());
            JsonNode result = http.postForObject(call.getUrl(), new HttpEntity<JsonNode>(body, headers), JsonNode.class);
            if (result == null || !result.isObject()) {
                throw unavailable();
            }
            call.success();
            return result;
        } catch (Exception ignored) {
            if (call != null) {
                // Outbound circuit logging must not receive response bodies, credentials or source text.
                call.failure(new IllegalStateException("public-health upstream request failed"));
            }
            throw unavailable();
        }
    }

    private static PublicHealthException unavailable() {
        return PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY");
    }
}
