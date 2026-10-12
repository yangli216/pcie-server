package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PublicHealthRecipeRetrieverTest {
    private final PublicHealthProperties properties = new PublicHealthProperties();
    private final ConfigService configs = mock(ConfigService.class);
    private final ResolvedAiConfig config = new ResolvedAiConfig();
    private MockRestServiceServer server;
    private PublicHealthRecipeRetriever retriever;

    @BeforeEach void setup() {
        properties.setRetrievalEnabled(true);
        config.setBaseUrl("http://127.0.0.1:18080");
        config.setApiKey("synthetic-key");
        when(configs.resolveByDevice(any())).thenReturn(config);
        RestTemplate http = new RestTemplate();
        server = MockRestServiceServer.createServer(http);
        retriever = new PublicHealthRecipeRetriever(properties, configs,
                new OutboundSecurityService(new OutboundSecurityProperties()), http, PublicHealthTestFixture.M);
    }

    @Test void truncatesVectorCandidatesThenMapsRerankIndexesToThoseCandidates() {
        properties.setRetrievalCandidates(4);
        ObjectNode recipes = distinctRecipes();
        expectEmbedding();
        server.expect(requestTo("http://127.0.0.1:18080/rerank"))
                .andExpect(jsonPath("$.model").value("bge-reranker-large"))
                .andExpect(jsonPath("$.top_n").value(4))
                .andExpect(jsonPath("$.documents.length()").value(4))
                .andExpect(jsonPath("$.documents[0]").value("Synthetic distinct recipe 2"))
                .andExpect(jsonPath("$.documents[1]").value("Synthetic distinct recipe 4"))
                .andExpect(jsonPath("$.documents[2]").value("Synthetic distinct recipe 3"))
                .andExpect(jsonPath("$.documents[3]").value("Synthetic distinct recipe 1"))
                .andRespond(withSuccess("{\"results\":[{\"index\":3,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8},{\"index\":2,\"relevance_score\":0.7},{\"index\":1,\"relevance_score\":0.6}]}", MediaType.APPLICATION_JSON));
        ArrayNode result = retriever.retrieve(PublicHealthTestFixture.device(), recipes, "合成查询");
        assertEquals("CHUNK-1", result.get(0).path("id").asText());
        assertEquals("CHUNK-2", result.get(1).path("id").asText());
        assertEquals("CHUNK-3", result.get(2).path("id").asText());
        assertEquals("CHUNK-4", result.get(3).path("id").asText());
        for (int i = 0; i < result.size(); i++) assertFalse(result.get(i).has("vector"));
        assertTrue(recipes.path("documents").get(0).has("vector"));
        verify(configs).resolveByDevice(any());
        server.verify();
    }

    @Test void explicitConfigAllowsOfflineVerificationWithoutChangingDeviceGate() {
        properties.setRetrievalEnabled(false);
        expectEmbedding();
        server.expect(requestTo("http://127.0.0.1:18080/rerank"))
                .andRespond(withSuccess(validRanking(), MediaType.APPLICATION_JSON));
        assertEquals(4, retriever.retrieve(config, PublicHealthTestFixture.recipes(), "合成查询", () -> {}).size());
        verifyNoInteractions(configs);
        assertThrows(PublicHealthException.class, () -> retriever.retrieve(PublicHealthTestFixture.device(), PublicHealthTestFixture.recipes(), "合成查询"));
        server.verify();
    }

    @ParameterizedTest @MethodSource("invalidRankings")
    void rejectsInvalidRerankResponsesWithoutKeywordFallback(String ranking) {
        expectEmbedding();
        server.expect(requestTo("http://127.0.0.1:18080/rerank"))
                .andRespond(withSuccess(ranking, MediaType.APPLICATION_JSON));
        assertEquals("KNOWLEDGE_NOT_READY", assertThrows(PublicHealthException.class,
                () -> retriever.retrieve(PublicHealthTestFixture.device(), distinctRecipes(), "合成查询")).getCode());
        server.verify();
    }

    static Stream<String> invalidRankings() {
        String last = ",{\"index\":2,\"relevance_score\":0.7},{\"index\":3,\"relevance_score\":0.6}]}";
        return Stream.of(
                "{}", "{\"results\":[]}",
                "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}" + last,
                "{\"results\":[{\"index\":6,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}" + last,
                "{\"results\":[{\"index\":-1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}" + last,
                "{\"results\":[{\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}" + last,
                "{\"results\":[{\"index\":1.0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}" + last,
                "{\"results\":[{\"index\":1,\"relevance_score\":\"0.9\"},{\"index\":0,\"relevance_score\":0.8}" + last,
                "{\"results\":[{\"index\":1,\"relevance_score\":1e309},{\"index\":0,\"relevance_score\":0.8}" + last,
                "{\"results\":[{\"index\":1,\"relevance_score\":0.1},{\"index\":0,\"relevance_score\":0.8}" + last);
    }

    @Test void invalidEmbeddingStopsBeforeRerank() {
        server.expect(requestTo("http://127.0.0.1:18080/embeddings"))
                .andRespond(withSuccess("{\"model\":\"synthetic-embedding\",\"data\":[{\"embedding\":[1,0]}]}", MediaType.APPLICATION_JSON));
        assertThrows(PublicHealthException.class, () -> retriever.retrieve(PublicHealthTestFixture.device(), distinctRecipes(), "合成查询"));
        server.verify();
    }

    @Test void checkpointStopsBeforeRerank() {
        expectEmbedding();
        Runnable checkpoint = mock(Runnable.class);
        doNothing().doThrow(new IllegalStateException("deadline")).when(checkpoint).run();
        assertEquals("deadline", assertThrows(IllegalStateException.class,
                () -> retriever.retrieve(config, distinctRecipes(), "合成查询", checkpoint)).getMessage());
        server.verify();
    }

    @Test void defaultDisabledRetrievalDoesNotResolveConfigOrCallGateway() {
        PublicHealthRecipeRetriever disabled = new PublicHealthRecipeRetriever(new PublicHealthProperties(), configs,
                mock(OutboundSecurityService.class), new RestTemplate(), PublicHealthTestFixture.M);
        assertThrows(PublicHealthException.class, () -> disabled.retrieve(PublicHealthTestFixture.device(), PublicHealthTestFixture.recipes(), "synthetic"));
        verifyNoInteractions(configs);
        server.verify();
    }

    private void expectEmbedding() {
        server.expect(requestTo("http://127.0.0.1:18080/embeddings"))
                .andExpect(header("Authorization", "Bearer synthetic-key"))
                .andExpect(jsonPath("$.model").value("synthetic-embedding"))
                .andExpect(jsonPath("$.input").isArray())
                .andExpect(jsonPath("$.input[0]").value("合成查询"))
                .andRespond(withSuccess("{\"model\":\"synthetic-embedding\",\"data\":[{\"index\":0,\"embedding\":[1,0]}]}", MediaType.APPLICATION_JSON));
    }

    private static ObjectNode distinctRecipes() {
        ObjectNode recipes = PublicHealthTestFixture.recipes();
        ArrayNode documents = recipes.putArray("documents");
        double[][] vectors = {{0, 1}, {1, 3}, {1, 0}, {1, 1}, {1, 0.1}, {-1, 0}};
        for (int i = 0; i < vectors.length; i++) {
            ObjectNode document = documents.addObject();
            document.put("id", "CHUNK-" + i).put("documentId", "SYNTHETIC-DOC")
                    .put("source", "synthetic-source").put("title", "Synthetic " + i)
                    .put("text", "Synthetic distinct recipe " + i);
            document.putArray("vector").add(vectors[i][0]).add(vectors[i][1]);
        }
        return recipes;
    }

    private static String validRanking() {
        return "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.8},{\"index\":2,\"relevance_score\":0.7},{\"index\":3,\"relevance_score\":0.6}]}";
    }
}
