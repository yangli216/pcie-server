package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.time.Clock;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HealthPrescriptionClientTest {
    private static final String SK = "0123456789abcdeffedcba9876543210";
    private static final String ID = "110101200001010011";
    private static final String BASE = "http://localhost:19000";
    private final ObjectMapper mapper = new ObjectMapper();
    private ChronicExaminationProperties properties;
    private HealthPrescriptionClient client;
    private MockRestServiceServer http;
    private Clock clock;
    @BeforeEach void setup() {
        properties = new ChronicExaminationProperties();
        properties.setEnabled(true); properties.setBaseUrl(BASE); properties.setAk("synthetic-ak"); properties.setSk(SK);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-21T06:00:00Z"));
        when(clock.millis()).thenReturn(1000L);
        client = new HealthPrescriptionClient(properties, new OutboundSecurityService(new OutboundSecurityProperties()), mapper, clock);
        http = MockRestServiceServer.createServer((RestTemplate) ReflectionTestUtils.getField(client, "http"));
    }
    private void login(String token) {
        http.expect(requestTo(BASE + "/api/login")).andExpect(headerDoesNotExist("Authorization"))
            .andExpect(content().json("{\"type\":\"simplejson\",\"hdservicecode\":\"tokenservice\",\"date\":\"20261021T140000+0800\",\"data\":{\"ak\":\"" + properties.getAk() + "\",\"sign\":\"d2a76f4daed0d2d8d0c030cd98c185629e972a25\"}}"))
            .andRespond(withSuccess("{\"isError\":false,\"data\":{\"token\":\"" + token + "\"}}", MediaType.APPLICATION_JSON));
    }
    private String response(String plain) throws Exception {
        return "{\"isError\":false,\"data\":\"" + HealthPrescriptionProtocol.encrypt(plain, properties.getSk()) + "\"}";
    }
    private void query(String token, String plain) throws Exception {
        http.expect(requestTo(BASE + "/api/wjjbgs/cy/getUserInfo")).andExpect(header("token", token)).andExpect(header("encrypt", "1"))
            .andExpect(headerDoesNotExist("Authorization")).andExpect(request -> {
                String raw = ((MockClientHttpRequest) request).getBodyAsString();
                assertFalse(raw.contains(ID)); assertFalse(raw.contains(SK));
                JsonNode body = mapper.readTree(raw);
                assertEquals(token, body.path("token").asText());
                JsonNode decoded;
                try { decoded = mapper.readTree(HealthPrescriptionProtocol.decrypt(body.path("content").asText(), SK)); }
                catch (Exception ex) { throw new java.io.IOException("Synthetic crypto assertion failed"); }
                assertEquals(ID, decoded.path("idNo").asText());
                assertEquals("01", decoded.path("idTypeCode").asText());
            }).andRespond(withSuccess(response(plain), MediaType.APPLICATION_JSON));
    }
    @Test void wireFixturesMatchIndependentOpenSslAndPythonImplementations() throws Exception {
        String plain = "{\"idTypeCode\":\"01\",\"idNo\":\"" + ID + "\"}";
        String fixture = "897481a3885953ca4e801e8db3c056a0fbd2599479115169f55e2eb5a86801132b38bc0d88756ce85842b44ddbf6f2bb";
        assertEquals(fixture, HealthPrescriptionProtocol.encrypt(plain, SK));
        assertEquals(plain, HealthPrescriptionProtocol.decrypt(fixture, SK));
        assertEquals("d2a76f4daed0d2d8d0c030cd98c185629e972a25", HealthPrescriptionProtocol.sign("synthetic-ak", SK, "20261021T140000+0800"));
        assertEquals("20261021T140000+0800", HealthPrescriptionProtocol.date(clock.instant()));
    }
    @Test void encryptedQueryReusesTokenAndDistinguishesValidEmptyArray() throws Exception {
        login("token-one"); query("token-one", "{\"inspectionItems\":[{\"inspectResult\":\"血脂\"}]}");
        query("token-one", "{\"inspectionItems\":[]}");
        assertEquals("血脂", client.query(ID).path("inspectionItems").get(0).path("inspectResult").asText());
        assertTrue(client.query(ID).path("inspectionItems").isEmpty()); http.verify();
    }
    @Test void expiresTokenBeforeLegacySixHourLimit() throws Exception {
        login("token-one"); query("token-one", "{\"inspectionItems\":[]}");
        login("token-two"); query("token-two", "{\"inspectionItems\":[]}");
        client.query(ID); when(clock.millis()).thenReturn(3601000L); client.query(ID); http.verify();
    }
    @Test void explicitUnauthorizedRefreshesAtMostOnce() throws Exception {
        login("token-one");
        http.expect(requestTo(BASE + "/api/wjjbgs/cy/getUserInfo")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        login("token-two"); query("token-two", "{\"inspectionItems\":[]}");
        assertTrue(client.query(ID).path("inspectionItems").isEmpty()); http.verify();
    }
    @Test void repeatedUnauthorizedAndBusinessFailureDoNotLoop() throws Exception {
        login("token-one"); http.expect(requestTo(BASE + "/api/wjjbgs/cy/getUserInfo")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        login("token-two"); http.expect(requestTo(BASE + "/api/wjjbgs/cy/getUserInfo")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThrows(Exception.class, () -> client.query(ID)); http.verify();
        http.reset(); login("token-three");
        http.expect(requestTo(BASE + "/api/wjjbgs/cy/getUserInfo")).andRespond(withSuccess("{\"isError\":true,\"message\":\"private-data\"}", MediaType.APPLICATION_JSON));
        Exception error = assertThrows(Exception.class, () -> client.query(ID));
        assertFalse(error.getMessage().contains("private-data")); http.verify();
    }
    @Test void configurationRotationInvalidatesCachedToken() throws Exception {
        login("token-one"); query("token-one", "{\"inspectionItems\":[]}"); client.query(ID); http.verify(); http.reset();
        properties.setAk("rotated-ak");
        http.expect(requestTo(BASE + "/api/login")).andExpect(jsonPath("$.data.ak").value("rotated-ak"))
            .andRespond(withSuccess("{\"isError\":false,\"data\":{\"token\":\"new-token\"}}", MediaType.APPLICATION_JSON));
        query("new-token", "{\"inspectionItems\":[]}"); client.query(ID); http.verify();
    }
    @Test void invalidConfigurationAndIdentityMakeNoNetworkRequests() {
        properties.setEnabled(false); assertThrows(Exception.class, () -> client.query(ID));
        properties.setEnabled(true); properties.setBaseUrl(BASE + "/wrong-path"); assertThrows(Exception.class, () -> client.query(ID));
        properties.setBaseUrl(BASE); properties.setSk("bad"); assertThrows(Exception.class, () -> client.query(ID));
        properties.setSk(SK); assertThrows(Exception.class, () -> client.query("encrypted-id")); http.verify();
    }
    @Test void invalidCiphertextAndPaddingFailClosed() throws Exception {
        for (String value : new String[]{"", "plaintext", "aa", "00000000000000000000000000000000"}) {
            assertThrows(Exception.class, () -> HealthPrescriptionProtocol.decrypt(value, SK));
        }
        assertThrows(Exception.class, () -> HealthPrescriptionProtocol.encrypt("patient", "bad-key"));
        login("token-one");
        http.expect(requestTo(BASE + "/api/wjjbgs/cy/getUserInfo")).andRespond(withSuccess("{\"isError\":false,\"data\":\"plaintext\"}", MediaType.APPLICATION_JSON));
        assertThrows(Exception.class, () -> client.query(ID)); http.verify();
    }
    @Test void traceLoggingDoesNotExposeLoginOrBusinessSecrets() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RestTemplate.class);
        ch.qos.logback.classic.Level previous = logger.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events = new ch.qos.logback.core.read.ListAppender<>();
        events.start(); logger.addAppender(events); logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            login("private-test-token"); query("private-test-token", "{\"inspectionItems\":[]}"); client.query(ID);
            String logged = events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
            for (String secret : new String[]{ID, SK, "synthetic-ak", "private-test-token", "d2a76f4daed0d2d8d0c030cd98c185629e972a25"}) {
                assertFalse(logged.contains(secret));
            }
            http.verify();
        } finally { logger.detachAppender(events); logger.setLevel(previous); events.stop(); }
    }

    @Test void simultaneousQueriesUseSingleTokenLogin() throws Exception {
        login("shared-token");
        http.expect(org.springframework.test.web.client.ExpectedCount.times(8), requestTo(BASE + "/api/wjjbgs/cy/getUserInfo"))
            .andExpect(header("token", "shared-token"))
            .andRespond(withSuccess(response("{\"inspectionItems\":[]}"), MediaType.APPLICATION_JSON));
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            java.util.List<java.util.concurrent.Callable<JsonNode>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(() -> client.query(ID));
            for (java.util.concurrent.Future<JsonNode> result : executor.invokeAll(tasks)) assertTrue(result.get().path("inspectionItems").isEmpty());
            http.verify();
        } finally { executor.shutdownNow(); }
    }

}
