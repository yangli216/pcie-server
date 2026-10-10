package com.regionalai.floatingball.server.modules.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.knowledge.controller.KnowledgeRetrievalController;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import com.regionalai.floatingball.server.modules.security.service.SecurityRejectionLogService;
import com.regionalai.floatingball.server.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeRetrievalIntegrationTest {
    @TempDir Path directory;
    KnowledgeTestFixture fixture;
    MockMvc mvc;
    MockRestServiceServer upstream;
    KeyPair pair;
    AiDevice device;
    static final String PATH = "/v1/knowledge/search";
    static final String BODY = "{\"collectionId\":\"synthetic\",\"query\":\"合成资料\",\"releaseVersion\":\"synthetic-v1\"}";

    @BeforeEach void setup() throws Exception {
        fixture = new KnowledgeTestFixture(directory);
        RestTemplate http = WeKnoraKnowledgeProvider.createHttpClient(fixture.properties);
        upstream = MockRestServiceServer.bindTo(http).build();
        WeKnoraKnowledgeProvider provider = new WeKnoraKnowledgeProvider(fixture.properties, WeKnoraKnowledgeProviderTest.outbound(), KnowledgeTestFixture.MAPPER, http);
        KnowledgeRetrievalController controller = new KnowledgeRetrievalController(fixture.catalog, new KnowledgeRetrievalService(fixture.catalog, provider));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(256); pair = generator.generateKeyPair();
        device = KnowledgeTestFixture.device(); device.setClientVersion("1.0.0"); device.setDevicePublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        DeviceService devices = mock(DeviceService.class); when(devices.findActiveByToken("synthetic-token")).thenReturn(device);
        ReleaseService releases = mock(ReleaseService.class); when(releases.isUpdateRequired(anyString(), anyString())).thenReturn(false);
        DeviceAuthFilter filter = new DeviceAuthFilter(devices, releases, new RequestSignatureVerifier(), mock(SecurityRejectionLogService.class), KnowledgeTestFixture.MAPPER);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).addFilters(filter).build();
    }

    @Test void signedRequestProducesSourceVersionAndReplayableSyntheticEvidence() throws Exception {
        final JsonNode[] outgoing = new JsonNode[1];
        ObjectNode fake = KnowledgeTestFixture.upstream(KnowledgeTestFixture.CONTENT);
        upstream.expect(requestTo("http://127.0.0.1:8787/api/v1/knowledge-bases/KB-SYNTHETIC/hybrid-search"))
            .andExpect(request -> outgoing[0] = KnowledgeTestFixture.MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsBytes()))
            .andRespond(withSuccess(fake.toString(), MediaType.APPLICATION_JSON));
        MvcResult result = mvc.perform(signed("POST", PATH, BODY).header("X-Request-Id", "SYNTHETIC-KNOWLEDGE-REQUEST"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("SYNTHETIC-KNOWLEDGE-REQUEST"))
            .andExpect(jsonPath("$.data.results[0].content").value(KnowledgeTestFixture.CONTENT))
            .andExpect(jsonPath("$.data.results[0].title").value("合成工程资料"))
            .andExpect(jsonPath("$.data.results[0].sourceVersion").value("source-v1"))
            .andExpect(jsonPath("$.data.results[0].matchType").value("embedding"))
            .andExpect(jsonPath("$.data.collection.releaseVersion").value("synthetic-v1")).andReturn();
        upstream.verify(); assertNull(DeviceContextHolder.get());
        assertEquals(5, outgoing[0].path("match_count").asInt());
        assertTrue(outgoing[0].path("skip_context_enrichment").asBoolean());
        assertEquals("DOC-SYNTHETIC", outgoing[0].path("knowledge_ids").get(0).asText());
        ObjectNode evidence = KnowledgeTestFixture.MAPPER.createObjectNode();
        evidence.put("verification", "synthetic-signed-mvc-and-fake-weknora").put("method", "POST").put("path", PATH);
        evidence.set("request", KnowledgeTestFixture.MAPPER.readTree(BODY)); evidence.set("upstreamRequest", outgoing[0]); evidence.set("upstreamResponse", fake);
        evidence.set("response", KnowledgeTestFixture.MAPPER.readTree(result.getResponse().getContentAsByteArray()));
        Path artifact = Paths.get(System.getProperty("basedir", "."), "target", "knowledge-retrieval-evidence.json");
        Files.createDirectories(artifact.getParent()); Files.write(artifact, KnowledgeTestFixture.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
    }

    @Test void collectionListIsSignedAndNeverExposesPhysicalScopeOrSecrets() throws Exception {
        MvcResult result = mvc.perform(signed("GET", "/v1/knowledge/collections", ""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.collections[0].documentCount").value(1)).andReturn();
        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String secret : new String[]{"apiKey", "knowledgeBaseId", "connectionId", "organizationIds", "synthetic-retrieval-key"}) assertFalse(json.contains(secret));
        mvc.perform(get("/v1/knowledge/collections").header("Authorization", "Bearer synthetic-token")).andExpect(status().isUnauthorized()); upstream.verify();
    }

    @Test void missingSignatureWrongPathAndActualBodyMutationAreRejectedBeforeRetrieval() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY).header("Authorization", "Bearer synthetic-token")).andExpect(status().isUnauthorized());
        MockHttpServletRequestBuilder wrongPath = signed("POST", "/v1/ai/chat", BODY); wrongPath.with(request -> { request.setRequestURI(PATH); return request; });
        mvc.perform(wrongPath).andExpect(status().isUnauthorized());
        mvc.perform(signed("POST", PATH, BODY).content(BODY.replace("合成资料", "篡改查询"))).andExpect(status().isUnauthorized()); upstream.verify();
    }

    @Test void nonceReplayIsRejectedEvenForReadOnlyCollections() throws Exception {
        MockHttpServletRequestBuilder request = signed("GET", "/v1/knowledge/collections", "");
        mvc.perform(request).andExpect(status().isOk()); mvc.perform(request).andExpect(status().isUnauthorized()); upstream.verify();
    }

    @Test void organizationAlwaysComesFromRegisteredDeviceRegardlessOfHeaders() throws Exception {
        device.setIdOrg("OTHER-ORG");
        mvc.perform(signed("POST", PATH, BODY).header("X-Org-Code", "ORG-SYNTHETIC").header("X-Tenant-Id", "ORG-SYNTHETIC"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("KNOWLEDGE_NOT_READY"));
        mvc.perform(signed("GET", "/v1/knowledge/collections", "")).andExpect(status().isOk()).andExpect(jsonPath("$.data.collections").isEmpty()); upstream.verify();
    }

    @Test void staleVersionAndRevokedPublicationDoNotReachUpstream() throws Exception {
        mvc.perform(signed("POST", PATH, BODY.replace("synthetic-v1", "stale"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("KNOWLEDGE_VERSION_CHANGED"));
        ObjectNode root = KnowledgeTestFixture.root(); KnowledgeTestFixture.first(root).put("reviewStatus", "REVOKED"); fixture.write(root);
        mvc.perform(signed("POST", PATH, BODY)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("KNOWLEDGE_NOT_READY")); upstream.verify();
    }

    @Test void clientCannotSupplyCredentialsTenantOrPhysicalKnowledgeIds() throws Exception {
        for (String field : new String[]{"organizationId", "knowledgeIds", "apiKey", "baseUrl"}) {
            ObjectNode body = (ObjectNode) KnowledgeTestFixture.MAPPER.readTree(BODY); body.put(field, "synthetic-extra-field");
            mvc.perform(signed("POST", PATH, body.toString())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION-002"));
        }
        upstream.verify();
    }

    @Test void blankOversizedQueryAndNullOrOutOfRangeLimitAreRejected() throws Exception {
        for (Integer limit : new Integer[]{null, 0, 21}) {
            ObjectNode body = (ObjectNode) KnowledgeTestFixture.MAPPER.readTree(BODY); if (limit == null) body.putNull("limit"); else body.put("limit", limit);
            mvc.perform(signed("POST", PATH, body.toString())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION-001"));
        }
        for (String query : new String[]{" ", String.join("", Collections.nCopies(2001, "字"))}) {
            ObjectNode body = (ObjectNode) KnowledgeTestFixture.MAPPER.readTree(BODY); body.put("query", query);
            mvc.perform(signed("POST", PATH, body.toString())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION-001"));
        }
        upstream.verify();
    }

    @Test void changedContentAndProviderFailureHaveDistinctErrorsFromZeroHits() throws Exception {
        upstream.expect(anything()).andRespond(withSuccess(KnowledgeTestFixture.upstream(KnowledgeTestFixture.CONTENT + " ").toString(), MediaType.APPLICATION_JSON));
        mvc.perform(signed("POST", PATH, BODY)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("KNOWLEDGE_CONTENT_CHANGED")); upstream.verify();
        upstream.reset(); upstream.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("secret upstream failure"));
        mvc.perform(signed("POST", PATH, BODY)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("KNOWLEDGE_UPSTREAM_UNAVAILABLE")); upstream.verify();
        upstream.reset(); upstream.expect(anything()).andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));
        mvc.perform(signed("POST", PATH, BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.data.results").isEmpty()); upstream.verify();
    }

    private MockHttpServletRequestBuilder signed(String method, String path, String body) throws Exception {
        String timestamp = Long.toString(System.currentTimeMillis()), nonce = UUID.randomUUID().toString();
        String hash = KnowledgeJson.sha256(body);
        Signature signature = Signature.getInstance("SHA256withECDSA"); signature.initSign(pair.getPrivate());
        signature.update((method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + hash + "\n").getBytes(StandardCharsets.UTF_8));
        return request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8))
            .header("Authorization", "Bearer synthetic-token").header("X-Timestamp", timestamp).header("X-Nonce", nonce)
            .header("X-Body-SHA256", hash).header("X-Signature", Base64.getEncoder().encodeToString(signature.sign()))
            .header("X-Client-Version", "1.0.0").header("X-Update-Channel", "production");
    }
}
