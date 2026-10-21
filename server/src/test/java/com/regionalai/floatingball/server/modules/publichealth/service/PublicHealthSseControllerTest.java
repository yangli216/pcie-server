package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.regionalai.floatingball.server.modules.publichealth.controller.PublicHealthController;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import com.regionalai.floatingball.server.modules.publichealth.entity.PublicHealthRun;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicHealthSseControllerTest {
    PublicHealthRunStore store;
    PublicHealthOrchestrator orchestrator;
    PublicHealthGenerationService service;
    MockMvc mvc;
    PublicHealthProperties properties;

    @BeforeEach void setup() {
        properties = new PublicHealthProperties();
        properties.setEnabled(true);
        store = mock(PublicHealthRunStore.class);
        orchestrator = mock(PublicHealthOrchestrator.class);
        when(orchestrator.identity(any())).thenAnswer(i -> {
            PublicHealthRequest r = i.getArgument(0); ObjectNode n = PublicHealthTestFixture.M.valueToTree(r); n.remove(Arrays.asList("schemaVersion",
            "requestId","patient","facts","writebackTargets")); return n;
        }
        );
        service = new PublicHealthGenerationService(properties, store, orchestrator, PublicHealthTestFixture.M, Runnable::run);
        mvc = MockMvcBuilders.standaloneSetup(new PublicHealthController(new PublicHealthValidator(PublicHealthTestFixture.M), service, PublicHealthTestFixture.M)).build();
        DeviceContextHolder.set(PublicHealthTestFixture.device());
    }

    @AfterEach void close() {
        DeviceContextHolder.clear();
        service.close();
    }

    @Test void replayUsesNewStreamWithSequenceOneTwoAndCompleteResult() throws Exception {
        PublicHealthRun run = PublicHealthRunStoreTest.run("COMPLETED");
        run.setGenerationId("10000000-0000-4000-8000-000000000001");
        ObjectNode result = result();
        run.setResultJson(result.toString());
        when(store.find(any(), any(), anyString())).thenReturn(run);
        List<JsonNode> one = data(call()), two = data(call());
        assertEquals(2, one.size());
        assertEquals(1, one.get(0).path("sequence").asInt());
        assertEquals(2, one.get(1).path("sequence").asInt());
        assertEquals(result, one.get(1).path("result"));
        assertNotEquals(one.get(0).path("streamId").asText(), two.get(0).path("streamId").asText());
        assertEquals(one.get(0).path("generationId"), two.get(0).path("generationId"));
        assertEquals(one.get(0).path("originRequestId"), two.get(0).path("originRequestId"));
        verify(orchestrator, never()).prepare(any(), any());
        verify(store, never()).reserve(any(), any(), anyString());
    }

    @Test void missingMedicationCoverageIsReportedInTheExistingStructuredErrorWithoutReserving() throws Exception {
        when(orchestrator.prepare(any(), any())).thenThrow(new PublicHealthException(503, "POLICY_NOT_READY", "机构规则或知识资产尚未就绪",
                Collections.singletonList("medicationClass:MISSING-CLASS")));
        mvc.perform(post("/v1/public-health/generations").contentType(MediaType.APPLICATION_JSON)
                .content(PublicHealthTestFixture.request("health_prescription").toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("POLICY_NOT_READY"))
                .andExpect(jsonPath("$.missingFields[0]").value("medicationClass:MISSING-CLASS"));
        verify(store, never()).reserve(any(), any(), anyString());
        verify(orchestrator, never()).generate(any(), any(), anyString(), any(), any());
    }

    @Test void missingAssetsReturnsStructured503WithoutReservingOrModelWork() throws Exception {
        when(orchestrator.prepare(any(), any())).thenThrow(new PublicHealthException(503,"KNOWLEDGE_NOT_READY","机构资料尚未就绪"));
        mvc.perform(post("/v1/public-health/generations").contentType(MediaType.APPLICATION_JSON).content(PublicHealthTestFixture.request("health_prescription").toString())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("KNOWLEDGE_NOT_READY")).andExpect(jsonPath("$.schemaVersion").value("public-health.v1"));
        verify(store, never()).reserve(any(), any(), anyString());
    }

    @Test void disabledDoesNotTouchStoreOrAssets() throws Exception {
        properties.setEnabled(false);
        mvc.perform(post("/v1/public-health/generations").contentType(MediaType.APPLICATION_JSON).content(PublicHealthTestFixture.request("health_prescription").toString())).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(store);
        verify(orchestrator, never()).prepare(any(), any());
    }

    @Test void namedSectionAndCompletedPersistOnce() throws Exception {
        PublicHealthRun run = PublicHealthRunStoreTest.run("RUNNING");
        run.setGenerationId("10000000-0000-4000-8000-000000000001");
        when(store.reserve(any(), any(), anyString())).thenReturn(new PublicHealthRunStore.Reservation(run, true));
        when(store.complete(any(), any())).thenReturn(true);
        when(orchestrator.generate(any(), any(), anyString(), any(), any())).thenAnswer(i -> {
            PublicHealthOrchestrator.Listener l = i.getArgument(4); l.progress("generating_sections"); ObjectNode r = result(); for (JsonNode s:r.path("sections"))l.section((ObjectNode)s); return r;
        }
        );
        String wire = call();
        assertTrue(wire.contains("event:section"));
        assertTrue(wire.contains("event:completed"));
        List<JsonNode> frames = data(wire);
        for (int i = 0; i<frames.size(); i++) {
            assertEquals(i+1, frames.get(i).path("sequence").asInt());
            assertEquals(frames.get(0).path("streamId"), frames.get(i).path("streamId"));
        }
        verify(store, times(1)).complete(eq(run), any());
    }

    @Test void closingLocalEmitterDoesNotCancelServerPersistence() throws Exception {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        PublicHealthGenerationService deferred = new PublicHealthGenerationService(properties, store, orchestrator, PublicHealthTestFixture.M, queued::add);
        try {
            PublicHealthRun run = PublicHealthRunStoreTest.run("RUNNING");
            when(store.reserve(any(), any(), anyString())).thenReturn(new PublicHealthRunStore.Reservation(run, true));
            when(store.complete(any(), any())).thenReturn(true);
            when(orchestrator.generate(any(), any(), anyString(), any(), any())).thenReturn(result());
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = deferred.generate(PublicHealthTestFixture.device(), PublicHealthTestFixture.parsed("health_prescription"));
            emitter.complete();
            queued.get(0).run();
            verify(store).complete(eq(run), any());
        } finally { deferred.close(); }
    }
    private String call() throws Exception {
        MvcResult r = mvc.perform(post("/v1/public-health/generations").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).content(PublicHealthTestFixture.request("health_prescription").toString())).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(r)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
    private List<JsonNode> data(String wire) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (String line:wire.split("\n"))if (line.startsWith("data:"))result.add(PublicHealthTestFixture.M.readTree(line.substring(5)));
        return result;
    }
    private ObjectNode result() {
        ObjectNode r = PublicHealthTestFixture.request("health_prescription");
        r.remove(Arrays.asList("patient","facts","writebackTargets"));
        r.put("generationId","10000000-0000-4000-8000-000000000001");
        ArrayNode sections = r.putArray("sections");
        for (String id:PublicHealthValidator.HEALTH)sections.addObject().put("id", id).put("title", PublicHealthTestFixture.NOTICE).put("content",
        PublicHealthTestFixture.NOTICE);
        r.putArray("promptVersions").addObject().put("code","synthetic").put("version","v1");
        r.set("knowledgeVersions", PublicHealthTestFixture.versions());
        r.putArray("warnings");
        r.put("generatedAt","2026-09-29T10:00:10+08:00");
        return r;
    }
}
