package com.regionalai.floatingball.server.modules.publichealth.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PublicHealthEmbeddingClientTest {
    private static final String URL = "http://127.0.0.1:18080/embeddings";
    private final ResolvedAiConfig config = new ResolvedAiConfig();
    private final OutboundSecurityProperties properties = new OutboundSecurityProperties();
    private MockRestServiceServer server;
    private PublicHealthEmbeddingClient client;

    @BeforeEach void setup() {
        config.setBaseUrl("http://127.0.0.1:18080/");
        config.setApiKey("synthetic-key");
        RestTemplate http = new RestTemplate();
        server = MockRestServiceServer.createServer(http);
        client = new PublicHealthEmbeddingClient(new OutboundSecurityService(properties), http, PublicHealthTestFixture.M);
    }

    @Test void mapsUnorderedBatchToInputIndexesAndPreservesText() {
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer synthetic-key"))
                .andExpect(jsonPath("$.input[0]").value(" first synthetic text\n"))
                .andExpect(jsonPath("$.input[1]").value("second synthetic text"))
                .andExpect(jsonPath("$.model").value("synthetic-embedding"))
                .andRespond(withSuccess(response("[{\"index\":1,\"embedding\":[0,2]},{\"index\":0,\"embedding\":[3,0]}]"), MediaType.APPLICATION_JSON));
        double[][] result = client.embed(config, "synthetic-embedding", Arrays.asList(" first synthetic text\n", "second synthetic text"), null);
        assertArrayEquals(new double[]{3, 0}, result[0]);
        assertArrayEquals(new double[]{0, 2}, result[1]);
        server.verify();
    }

    @Test void validatesSingleInputIndexAndAlwaysUsesArrayPayload() {
        server.expect(requestTo(URL)).andExpect(jsonPath("$.input").isArray())
                .andExpect(jsonPath("$.input.length()").value(1))
                .andRespond(withSuccess(response("[{\"index\":0,\"embedding\":[1,0]}]"), MediaType.APPLICATION_JSON));
        assertArrayEquals(new double[]{1, 0}, client.embed(config, "synthetic-embedding", Collections.singletonList("synthetic"), 2)[0]);
        server.verify();
    }

    @ParameterizedTest @MethodSource("invalidSingleResponses")
    void rejectsMalformedSingleResponsesWithoutReturningVectors(String response) {
        server.expect(requestTo(URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertUnavailable(() -> client.embed(config, "synthetic-embedding", Collections.singletonList("synthetic"), 2));
        server.verify();
    }

    static Stream<String> invalidSingleResponses() {
        return Stream.of(
                "{\"model\":\"wrong-model\",\"data\":[{\"index\":0,\"embedding\":[1,0]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}",
                response("[]"),
                response("{}"),
                response("[{\"embedding\":[1,0]}]"),
                response("[{\"index\":1,\"embedding\":[1,0]}]"),
                response("[{\"index\":-1,\"embedding\":[1,0]}]"),
                response("[{\"index\":0.0,\"embedding\":[1,0]}]"),
                response("[{\"index\":\"0\",\"embedding\":[1,0]}]"),
                response("[{\"index\":2147483648,\"embedding\":[1,0]}]"),
                response("[{\"index\":0,\"embedding\":[1,0]},{\"index\":1,\"embedding\":[1,0]}]"),
                response("[{\"index\":0,\"embedding\":[1,0,0]}]"),
                response("[{\"index\":0,\"embedding\":[]}]"),
                response("[{\"index\":0,\"embedding\":[0,0]}]"),
                response("[{\"index\":0,\"embedding\":[\"1\",0]}]"),
                response("[{\"index\":0,\"embedding\":[1e309,0]}]"),
                "[]", "not JSON");
    }

    @ParameterizedTest @MethodSource("invalidBatchResponses")
    void rejectsDuplicateMissingOrInconsistentBatchIndexes(String response) {
        server.expect(requestTo(URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertUnavailable(() -> client.embed(config, "synthetic-embedding", Arrays.asList("first", "second"), null));
        server.verify();
    }

    static Stream<String> invalidBatchResponses() {
        return Stream.of(
                response("[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[0,1]}]"),
                response("[{\"index\":0,\"embedding\":[1,0]}]"),
                response("[{\"index\":0,\"embedding\":[1,0]},{\"index\":2,\"embedding\":[0,1]}]"),
                response("[{\"index\":0,\"embedding\":[1,0]},{\"embedding\":[0,1]}]"),
                response("[{\"index\":1,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[1,0,0]}]"));
    }

    @Test void rejectsExcessDimensionsBeforeReturningAnyVector() {
        ObjectNode response = PublicHealthTestFixture.M.createObjectNode().put("model", "synthetic-embedding");
        ArrayNode vector = response.putArray("data").addObject().put("index", 0).putArray("embedding");
        for (int i = 0; i < 8193; i++) vector.add(1);
        server.expect(requestTo(URL)).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        assertUnavailable(() -> client.embed(config, "synthetic-embedding", Collections.singletonList("synthetic"), null));
        server.verify();
    }

    @Test void rejectsInvalidInputsAndDimensionsBeforeNetworkRequest() {
        assertUnavailable(() -> client.embed(config, "synthetic-embedding", Collections.singletonList("synthetic"), 0));
        assertUnavailable(() -> client.embed(config, "synthetic-embedding", Collections.singletonList("synthetic"), 8193));
        assertUnavailable(() -> client.embed(config, "synthetic-embedding", Collections.emptyList(), null));
        assertUnavailable(() -> client.embed(config, "synthetic-embedding", Arrays.asList("synthetic", null), null));
        assertUnavailable(() -> client.embed(config, "", Collections.singletonList("synthetic"), null));
        assertUnavailable(() -> client.embed(config, "synthetic-embedding", Collections.singletonList("  "), null));
        server.verify();
    }

    @Test void appliesExistingOutboundRestrictions() {
        properties.setAllowPrivateNetwork(false);
        assertUnavailable(() -> client.embed(config, "synthetic-embedding", Collections.singletonList("synthetic"), 2));
        server.verify();
    }

    @Test void masksUpstreamResponseInFailureAndCircuitLog() {
        properties.setCircuitFailureThreshold(1);
        Logger logger = (Logger) LoggerFactory.getLogger(OutboundSecurityService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                    .body("sensitive-source-text synthetic-key").contentType(MediaType.TEXT_PLAIN));
            PublicHealthException exception = assertUnavailable(() -> client.embed(config, "synthetic-embedding", Collections.singletonList("synthetic"), 2));
            assertNull(exception.getCause());
            assertFalse(exception.getMessage().contains("synthetic-key"));
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("circuit opened")));
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("sensitive-source-text")
                    || event.getFormattedMessage().contains("synthetic-key")));
            server.verify();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static String response(String data) {
        return "{\"model\":\"synthetic-embedding\",\"data\":" + data + "}";
    }

    private static PublicHealthException assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        PublicHealthException exception = assertThrows(PublicHealthException.class, action);
        assertEquals("KNOWLEDGE_NOT_READY", exception.getCode());
        return exception;
    }
}
