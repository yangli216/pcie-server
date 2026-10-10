package com.regionalai.floatingball.server.modules.knowledge.service;

import com.fasterxml.jackson.databind.node.*;
import com.regionalai.floatingball.server.common.exception.*;
import com.regionalai.floatingball.server.common.outbound.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WeKnoraKnowledgeProviderTest {
    @TempDir Path directory;
    KnowledgeTestFixture fixture;
    MockRestServiceServer server;
    WeKnoraKnowledgeProvider provider;
    RestTemplate http;
    @BeforeEach void setup() throws Exception {
        fixture = new KnowledgeTestFixture(directory);
        http = WeKnoraKnowledgeProvider.createHttpClient(fixture.properties);
        server = MockRestServiceServer.bindTo(http).build();
        provider = new WeKnoraKnowledgeProvider(fixture.properties, outbound(), KnowledgeTestFixture.MAPPER, http);
    }

    @Test void requestUsesApprovedDocumentScopeAndRetrievalOnlyWithoutContextExpansion() throws Exception {
        server.expect(requestTo("http://127.0.0.1:8787/api/v1/knowledge-bases/KB-SYNTHETIC/hybrid-search"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("X-API-Key", "synthetic-retrieval-key"))
            .andExpect(headerDoesNotExist("Authorization")).andExpect(headerDoesNotExist("X-Org-Code"))
            .andExpect(content().json("{\"query_text\":\"合成\",\"match_count\":5,\"knowledge_ids\":[\"DOC-SYNTHETIC\"],\"skip_context_enrichment\":true,\"disable_keywords_match\":false,\"disable_vector_match\":false}", true))
            .andRespond(withSuccess(KnowledgeTestFixture.upstream(KnowledgeTestFixture.CONTENT).toString(), MediaType.APPLICATION_JSON));
        assertEquals(KnowledgeTestFixture.CONTENT, provider.search(fixture.release(), "合成", 5).get(0).getContent()); server.verify();
    }

    @Test void genuineEmptySearchSucceeds() {
        server.expect(anything()).andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(provider.search(fixture.release(), "合成", 5).isEmpty()); server.verify();
    }

    @Test void numericMatchTypeUsesTheOfficialWeKnoraEnumAndPreservesRawEvidence() {
        String[] expected = {"embedding", "keywords", "nearby_chunk", "history", "parent_chunk", "relation_chunk",
            "graph", "web_search", "direct_load", "data_analysis"};
        for (int ordinal = 0; ordinal < expected.length; ordinal++) {
            server.reset();
            ObjectNode response = KnowledgeTestFixture.upstream(KnowledgeTestFixture.CONTENT);
            ((ObjectNode) response.path("data").get(0)).put("match_type", ordinal);
            server.expect(anything()).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
            KnowledgeProviderHit hit = provider.search(fixture.release(), "合成", 5).get(0);
            assertEquals(expected[ordinal], hit.getMatchType());
            assertEquals(KnowledgeTestFixture.CONTENT, hit.getContent());
            assertEquals(0.9, hit.getScore());
            server.verify();
        }
    }

    @Test void legacyTextMatchTypeIsPreserved() {
        ObjectNode response = KnowledgeTestFixture.upstream(KnowledgeTestFixture.CONTENT);
        ((ObjectNode) response.path("data").get(0)).put("match_type", "vector");
        server.expect(anything()).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        assertEquals("vector", provider.search(fixture.release(), "合成", 5).get(0).getMatchType());
        server.verify();
    }

    @Test void unknownNumericAndMalformedMatchTypesAreRejectedWithRedactedErrors() throws Exception {
        for (String value : new String[]{"-1", "10", "2147483648", "0.5", "0.0", "true", "null", "{}", "[]", "\"\"", "\"  \""}) {
            server.reset();
            provider = new WeKnoraKnowledgeProvider(fixture.properties, outbound(), KnowledgeTestFixture.MAPPER, http);
            ObjectNode response = KnowledgeTestFixture.upstream(KnowledgeTestFixture.CONTENT);
            ((ObjectNode) response.path("data").get(0)).set("match_type", KnowledgeTestFixture.MAPPER.readTree(value));
            server.expect(anything()).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
            ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class, () -> provider.search(fixture.release(), "合成", 5));
            assertEquals("KNOWLEDGE_UPSTREAM_UNAVAILABLE", error.getCode());
            assertFalse(error.getMessage().contains("synthetic-retrieval-key"));
            assertFalse(error.getMessage().contains("127.0.0.1"));
            server.verify();
        }
        server.reset();
        ObjectNode response = KnowledgeTestFixture.upstream(KnowledgeTestFixture.CONTENT);
        ((ObjectNode) response.path("data").get(0)).remove("match_type");
        server.expect(anything()).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        assertThrows(ServiceUnavailableException.class, () -> provider.search(fixture.release(), "合成", 5));
        server.verify();
    }

    @Test void errorResponseCannotLeakProviderMessageUrlOrApiKeyAndIsNotRetried() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("synthetic-retrieval-key http://private-secret.invalid"));
        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class, () -> provider.search(fixture.release(), "合成", 5));
        assertEquals("KNOWLEDGE_UPSTREAM_UNAVAILABLE", error.getCode());
        assertFalse(error.getMessage().contains("synthetic-retrieval-key")); assertFalse(error.getMessage().contains("private-secret")); server.verify();
    }

    @Test void invalidEnvelopeAndMissingScoreAreErrorsRatherThanEmptyResults() {
        for (String json : new String[]{"null", "{\"success\":false,\"data\":[]}", "{\"success\":true}", "{\"success\":true,\"data\":[{}]}"}) {
            server.reset(); server.expect(anything()).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertThrows(ServiceUnavailableException.class, () -> provider.search(fixture.release(), "合成", 5)); server.verify();
        }
    }

    @Test void maximumResultCountBodyBytesAndContentBytesAreEnforced() {
        ObjectNode root = KnowledgeTestFixture.upstream(KnowledgeTestFixture.CONTENT);
        ((ArrayNode) root.path("data")).add(root.path("data").get(0).deepCopy());
        server.expect(anything()).andRespond(withSuccess(root.toString(), MediaType.APPLICATION_JSON));
        assertThrows(ServiceUnavailableException.class, () -> provider.search(fixture.release(), "合成", 1)); server.verify();
        server.reset(); String largeContent = repeat("中", 70000);
        server.expect(anything()).andRespond(withSuccess(KnowledgeTestFixture.upstream(largeContent).toString(), MediaType.APPLICATION_JSON));
        assertThrows(ServiceUnavailableException.class, () -> provider.search(fixture.release(), "合成", 5)); server.verify();
        server.reset(); server.expect(anything()).andRespond(withSuccess(repeat(" ", 2 * 1024 * 1024 + 1), MediaType.APPLICATION_JSON));
        assertThrows(ServiceUnavailableException.class, () -> provider.search(fixture.release(), "合成", 5)); server.verify();
    }

    @Test void noCredentialsMeansNoUpstreamRequestAndPropertiesRedactSecrets() {
        assertFalse(fixture.properties.toString().contains("synthetic-retrieval-key"));
        assertFalse(fixture.properties.getConnections().get("default").toString().contains("synthetic-retrieval-key"));
        fixture.properties.getConnections().get("default").setApiKey("bad\nkey");
        assertEquals("KNOWLEDGE_NOT_READY", assertThrows(BusinessException.class, () -> provider.search(fixture.release(), "合成", 5)).getCode()); server.verify();
    }

    @Test void outboundPolicyBlocksBeforeHttpAndTimeoutConfigurationMustBeBounded() {
        OutboundSecurityProperties policy = new OutboundSecurityProperties(); policy.setAllowInsecureHttp(false);
        RestTemplate http = WeKnoraKnowledgeProvider.createHttpClient(fixture.properties);
        MockRestServiceServer empty = MockRestServiceServer.bindTo(http).build();
        WeKnoraKnowledgeProvider secure = new WeKnoraKnowledgeProvider(fixture.properties, new OutboundSecurityService(policy), KnowledgeTestFixture.MAPPER, http);
        assertThrows(ServiceUnavailableException.class, () -> secure.search(fixture.release(), "合成", 5)); empty.verify();
        fixture.properties.setReadTimeoutMs(0); assertThrows(IllegalArgumentException.class, () -> WeKnoraKnowledgeProvider.createHttpClient(fixture.properties));
    }

    @Test void redirectsNeverForwardKeyToAnotherEndpoint() throws Exception {
        AtomicInteger destinationCalls = new AtomicInteger();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/api/v1/knowledge-bases/KB-SYNTHETIC/hybrid-search", exchange -> {
            exchange.getRequestBody().close(); exchange.getResponseHeaders().add("Location", "/secret-destination");
            exchange.sendResponseHeaders(307, -1); exchange.close();
        });
        fake.createContext("/secret-destination", exchange -> { destinationCalls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        fake.start();
        try {
            fixture.properties.getConnections().get("default").setBaseUrl("http://127.0.0.1:" + fake.getAddress().getPort());
            WeKnoraKnowledgeProvider actual = new WeKnoraKnowledgeProvider(fixture.properties, outbound(), KnowledgeTestFixture.MAPPER);
            assertThrows(ServiceUnavailableException.class, () -> actual.search(fixture.release(), "合成", 5));
            assertEquals(0, destinationCalls.get());
        } finally { fake.stop(0); }
    }

    static OutboundSecurityService outbound() { return new OutboundSecurityService(new OutboundSecurityProperties()); }
    private static String repeat(String value, int count) { StringBuilder result = new StringBuilder(); for (int i = 0; i < count; i++) result.append(value); return result.toString(); }
}
