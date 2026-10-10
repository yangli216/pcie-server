package com.regionalai.floatingball.server.modules.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService.OutboundCall;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class WeKnoraKnowledgeProvider implements KnowledgeRetrievalProvider {
    private static final String[] MATCH_TYPES = {
        "embedding", "keywords", "nearby_chunk", "history", "parent_chunk", "relation_chunk",
        "graph", "web_search", "direct_load", "data_analysis"
    };
    private final KnowledgeRetrievalProperties properties;
    private final OutboundSecurityService outbound;
    private final ObjectMapper mapper;
    private final RestTemplate http;

    @Autowired
    public WeKnoraKnowledgeProvider(KnowledgeRetrievalProperties properties, OutboundSecurityService outbound, ObjectMapper mapper) {
        this(properties, outbound, mapper, createHttpClient(properties));
    }

    WeKnoraKnowledgeProvider(KnowledgeRetrievalProperties properties, OutboundSecurityService outbound, ObjectMapper mapper, RestTemplate http) {
        this.properties = properties; this.outbound = outbound; this.mapper = mapper; this.http = http;
        http.setErrorHandler(new ResponseErrorHandler() {
            public boolean hasError(ClientHttpResponse response) { return false; }
            public void handleError(ClientHttpResponse response) {}
        });
    }

    @Override public String name() { return "weknora"; }

    @Override public List<KnowledgeProviderHit> search(KnowledgeCatalogService.Release release, String query, int limit) {
        KnowledgeRetrievalProperties.Connection connection = properties.getConnections().get(release.getConnectionId());
        if (connection == null || connection.getBaseUrl() == null || connection.getApiKey() == null
                || connection.getBaseUrl().trim().isEmpty() || connection.getApiKey().trim().isEmpty()
                || connection.getApiKey().length() > 4096 || connection.getApiKey().contains("\r") || connection.getApiKey().contains("\n")) {
            throw KnowledgeCatalogService.notReady();
        }
        OutboundCall call = null;
        try {
            URI base = new URI(connection.getBaseUrl().trim());
            if (base.getRawQuery() != null || base.getRawFragment() != null || base.getRawUserInfo() != null) throw new IOException("invalid knowledge root");
            String url = base.toString().replaceAll("/+$", "") + "/api/v1/knowledge-bases/" + release.getKnowledgeBaseId() + "/hybrid-search";
            call = outbound.acquireHttp(url, "weknora-knowledge-search");
            ObjectNode body = mapper.createObjectNode();
            body.put("query_text", query); body.put("match_count", limit);
            body.put("skip_context_enrichment", true);
            body.put("disable_keywords_match", false); body.put("disable_vector_match", false);
            com.fasterxml.jackson.databind.node.ArrayNode knowledgeIds = body.putArray("knowledge_ids");
            for (String knowledgeId : release.getDocuments().keySet()) knowledgeIds.add(knowledgeId);
            byte[] request = mapper.writeValueAsBytes(body);
            JsonNode response = http.execute(call.getUri(), HttpMethod.POST, outgoing -> {
                outgoing.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                outgoing.getHeaders().setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
                outgoing.getHeaders().set("X-API-Key", connection.getApiKey());
                outgoing.getBody().write(request);
            }, incoming -> {
                if (!incoming.getStatusCode().is2xxSuccessful()) throw new IOException("knowledge upstream status");
                return mapper.readTree(KnowledgeJson.readBounded(incoming.getBody(), 2 * 1024 * 1024));
            });
            if (response == null || !response.path("success").isBoolean() || !response.path("success").asBoolean()
                    || !response.path("data").isArray() || response.path("data").size() > limit) throw new IOException("invalid knowledge envelope");
            List<KnowledgeProviderHit> hits = new ArrayList<KnowledgeProviderHit>();
            for (JsonNode hit : response.path("data")) {
                String content = required(hit, "content", 200000);
                if (content.getBytes(StandardCharsets.UTF_8).length > 200000 || !hit.path("score").isNumber()
                        || !Double.isFinite(hit.path("score").asDouble())) throw new IOException("invalid knowledge hit");
                hits.add(new KnowledgeProviderHit(required(hit, "id", 128), required(hit, "knowledge_id", 128),
                    hit.has("knowledge_base_id") ? required(hit, "knowledge_base_id", 128) : null,
                    content, hit.path("score").asDouble(), matchType(hit)));
            }
            call.success();
            return hits;
        } catch (Exception error) {
            if (call != null) call.failure(new IllegalStateException("WeKnora retrieval failed"));
            throw new ServiceUnavailableException("KNOWLEDGE_UPSTREAM_UNAVAILABLE", "知识检索服务暂时不可用，请稍后重试");
        }
    }

    private static String required(JsonNode node, String field, int max) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().trim().isEmpty() || value.asText().length() > max) throw new IOException("invalid knowledge field");
        return value.asText();
    }

    private static String matchType(JsonNode hit) throws IOException {
        JsonNode value = hit.path("match_type");
        if (value.isTextual()) return required(hit, "match_type", 64);
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            int ordinal = value.intValue();
            if (ordinal >= 0 && ordinal < MATCH_TYPES.length) return MATCH_TYPES[ordinal];
        }
        throw new IOException("invalid knowledge match type");
    }

    static RestTemplate createHttpClient(KnowledgeRetrievalProperties properties) {
        if (properties.getConnectTimeoutMs() < 1 || properties.getConnectTimeoutMs() > 60000
                || properties.getReadTimeoutMs() < 1 || properties.getReadTimeoutMs() > 60000) {
            throw new IllegalArgumentException("Knowledge HTTP timeouts must be between 1 and 60000 milliseconds");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        return new RestTemplate(factory);
    }
}
