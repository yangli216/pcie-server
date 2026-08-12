package com.regionalai.floatingball.server.modules.featureevent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.featureevent.dto.FeatureEventBatchRequest;
import com.regionalai.floatingball.server.modules.featureevent.dto.FeatureEventBatchResponse;
import com.regionalai.floatingball.server.modules.featureevent.entity.AiFeatureEvent;
import com.regionalai.floatingball.server.modules.featureevent.mapper.AiFeatureEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureEventServiceTest {

    @Mock
    private AiFeatureEventMapper featureEventMapper;

    private FeatureEventService service;

    @BeforeEach
    void setUp() {
        service = new FeatureEventService(featureEventMapper);
    }

    @Test
    void saveBatchShouldInsertStrictlyMinimizedKnownFeatureEvent() {
        when(featureEventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");
        device.setIdRegion("REG001");
        device.setClientVersion("1.3.8");

        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "550E8400-E29B-41D4-A716-446655440000",
            FeatureEventCatalog.VOICE_CONSULTATION
        );
        event.setEventAction("open_voice_consultation");
        event.setIdempotencyKey("voice:VISIT-SENTINEL:PATIENT-SENTINEL");
        event.setTraceId("TRACE-001");
        event.setConsultationId("CONSULT-001");
        event.setSessionId("SESSION-001");
        event.setSourceModule("voice_consultation");
        event.setScene("voice-consultation");
        event.setDoctorId("DOC001");
        event.setDoctorWorkNo("0123");
        event.setDoctorName("张医生");
        event.setDeptId("DEPT001");
        event.setDeptName("全科");
        event.setHisOrgId("HIS-ORG-001");
        event.setHisOrgName("市第一医院");
        event.setClientVersion("1.3.9");
        event.setTimestamp(1770000000000L);
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("patientName", "PATIENT-SENTINEL");
        payload.put("query", "QUERY-SENTINEL");
        event.setPayload(payload);

        FeatureEventBatchResponse response = service.saveBatch(device, request(event));

        assertEquals(1, response.getAccepted());
        assertEquals(0, response.getSkipped());
        assertEquals(0, response.getRejected());
        ArgumentCaptor<AiFeatureEvent> captor = ArgumentCaptor.forClass(AiFeatureEvent.class);
        verify(featureEventMapper).insert(captor.capture());
        AiFeatureEvent saved = captor.getValue();
        assertEquals("550e8400e29b41d4a716446655440000", saved.getIdEvent());
        assertEquals("DEV001", saved.getIdDevice());
        assertEquals("ORG001", saved.getIdOrg());
        assertEquals("REG001", saved.getIdRegion());
        assertEquals(FeatureEventCatalog.VOICE_CONSULTATION, saved.getFeatureCode());
        assertEquals("语音问诊", saved.getFeatureName());
        assertEquals("open_voice_consultation", saved.getEventAction());
        assertEquals("voice_consultation", saved.getSourceModule());
        assertEquals("voice-consultation", saved.getSceneCode());
        assertEquals(
            "voice_consultation:minimized:v1:event:550e8400e29b41d4a716446655440000",
            saved.getIdempotencyKey()
        );
        assertNull(saved.getTraceId());
        assertNull(saved.getConsultationId());
        assertNull(saved.getSessionId());
        assertEquals("0123", saved.getDoctorWorkNo());
        assertEquals("HIS-ORG-001", saved.getHisOrgId());
        assertEquals("1.3.9", saved.getClientVersion());
        assertEquals("{}", saved.getPayloadJson());
        assertEquals("success", saved.getEventStatus());
        assertEquals("1", saved.getFgActive());
        assertNotNull(saved.getEventTime());
        assertTrue(!saved.getIdempotencyKey().contains("SENTINEL"));
    }

    @Test
    void saveBatchShouldDiscardFreeTextTelemetryCodesAndNormalizeStatus() {
        when(featureEventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "550e8400-e29b-41d4-a716-446655440010",
            FeatureEventCatalog.CHAT
        );
        event.setEventAction("患者甲的原始问题");
        event.setSourceModule("source module with spaces");
        event.setScene("PATIENT-SENTINEL/visit");
        event.setStatus("QUERY-SENTINEL");

        FeatureEventBatchResponse response = service.saveBatch(device, request(event));

        assertEquals(1, response.getAccepted());
        ArgumentCaptor<AiFeatureEvent> captor = ArgumentCaptor.forClass(AiFeatureEvent.class);
        verify(featureEventMapper).insert(captor.capture());
        AiFeatureEvent saved = captor.getValue();
        assertNull(saved.getEventAction());
        assertNull(saved.getSourceModule());
        assertNull(saved.getSceneCode());
        assertEquals("success", saved.getEventStatus());
    }

    @Test
    void saveBatchShouldAcceptAndDiscardLegacyNestedPayload() {
        when(featureEventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "550e8400-e29b-41d4-a716-446655440001",
            FeatureEventCatalog.CHAT
        );
        Map<String, Object> nested = new HashMap<String, Object>();
        nested.put("PaTiEnT_Id", "PATIENT-SENTINEL");
        nested.put("access-token", "TOKEN-SENTINEL");
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("metrics", Collections.singletonList(nested));
        event.setPayload(payload);

        FeatureEventBatchResponse response = service.saveBatch(device, request(event));

        assertEquals(1, response.getAccepted());
        assertEquals(0, response.getRejected());
        ArgumentCaptor<AiFeatureEvent> captor = ArgumentCaptor.forClass(AiFeatureEvent.class);
        verify(featureEventMapper).insert(captor.capture());
        assertEquals("{}", captor.getValue().getPayloadJson());
    }

    @Test
    void saveBatchShouldDiscardCyclicLegacyPayloadWithoutSerializingIt() {
        when(featureEventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "550e8400-e29b-41d4-a716-446655440002",
            FeatureEventCatalog.CHAT
        );
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("self", payload);
        event.setPayload(payload);

        FeatureEventBatchResponse response = service.saveBatch(device, request(event));

        assertEquals(1, response.getAccepted());
        ArgumentCaptor<AiFeatureEvent> captor = ArgumentCaptor.forClass(AiFeatureEvent.class);
        verify(featureEventMapper).insert(captor.capture());
        assertEquals("{}", captor.getValue().getPayloadJson());
    }

    @Test
    void saveBatchShouldRejectAnyFeatureEventWithoutUuidEventId() {
        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "PATIENT-VISIT-001",
            FeatureEventCatalog.REPORT_INTERPRETATION
        );

        FeatureEventBatchResponse response = service.saveBatch(null, request(event));

        assertEquals(0, response.getAccepted());
        assertEquals(0, response.getSkipped());
        assertEquals(1, response.getRejected());
        assertEquals("eventId 必须为 UUID", response.getRejections().get(0).getReason());
        verify(featureEventMapper, never()).selectCount(any(Wrapper.class));
        verify(featureEventMapper, never()).insert(any(AiFeatureEvent.class));
    }

    @Test
    void saveBatchShouldFallBackToAuthenticatedDeviceVersionForLegacyEvent() {
        when(featureEventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setClientVersion("1.3.7");
        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "550e8400-e29b-41d4-a716-446655440003",
            FeatureEventCatalog.CHAT
        );

        FeatureEventBatchResponse response = service.saveBatch(device, request(event));

        assertEquals(1, response.getAccepted());
        ArgumentCaptor<AiFeatureEvent> captor = ArgumentCaptor.forClass(AiFeatureEvent.class);
        verify(featureEventMapper).insert(captor.capture());
        assertEquals("1.3.7", captor.getValue().getClientVersion());
    }

    @Test
    void saveBatchShouldIgnoreMissingLegacyIdempotencyKey() {
        when(featureEventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "550e8400-e29b-41d4-a716-446655440004",
            FeatureEventCatalog.CHAT
        );
        event.setIdempotencyKey(null);

        FeatureEventBatchResponse response = service.saveBatch(device, request(event));

        assertEquals(1, response.getAccepted());
        ArgumentCaptor<AiFeatureEvent> captor = ArgumentCaptor.forClass(AiFeatureEvent.class);
        verify(featureEventMapper).insert(captor.capture());
        assertEquals(
            "chat:minimized:v1:event:550e8400e29b41d4a716446655440004",
            captor.getValue().getIdempotencyKey()
        );
    }

    @Test
    void saveBatchShouldSkipDuplicateDerivedKey() {
        when(featureEventMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "550e8400-e29b-41d4-a716-446655440005",
            FeatureEventCatalog.CHAT
        );

        FeatureEventBatchResponse response = service.saveBatch(device, request(event));

        assertEquals(0, response.getAccepted());
        assertEquals(1, response.getSkipped());
        assertEquals(0, response.getRejected());
        verify(featureEventMapper, never()).insert(any(AiFeatureEvent.class));
    }

    @Test
    void saveBatchShouldRejectUnsupportedFeatureCode() {
        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "550e8400-e29b-41d4-a716-446655440006",
            "raw_ai_operation"
        );

        FeatureEventBatchResponse response = service.saveBatch(null, request(event));

        assertEquals(1, response.getRejected());
        assertEquals(0, response.getRejections().get(0).getIndex());
        assertEquals("raw_ai_operation", response.getRejections().get(0).getFeatureCode());
        assertEquals("featureCode 不支持", response.getRejections().get(0).getReason());
        verify(featureEventMapper, never()).insert(any(AiFeatureEvent.class));
    }

    @Test
    void saveBatchShouldAcceptTreatmentPlanRecommendationWithDerivedKey() {
        when(featureEventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "550e8400-e29b-41d4-a716-446655440007",
            FeatureEventCatalog.TREATMENT_PLAN_RECOMMENDATION
        );
        event.setEventAction("open_treatment_plan_assist");
        event.setConsultationId("CONSULT-001");

        FeatureEventBatchResponse response = service.saveBatch(device, request(event));

        assertEquals(1, response.getAccepted());
        ArgumentCaptor<AiFeatureEvent> captor = ArgumentCaptor.forClass(AiFeatureEvent.class);
        verify(featureEventMapper).insert(captor.capture());
        AiFeatureEvent saved = captor.getValue();
        assertEquals("AI推荐治疗方案", saved.getFeatureName());
        assertNull(saved.getConsultationId());
        assertEquals(
            "treatment_plan_recommendation:minimized:v1:event:550e8400e29b41d4a716446655440007",
            saved.getIdempotencyKey()
        );
    }

    @Test
    void saveBatchShouldTreatUniqueConstraintAsSkipped() {
        when(featureEventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(featureEventMapper.insert(any(AiFeatureEvent.class)))
            .thenThrow(new DuplicateKeyException("duplicate"));
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        FeatureEventBatchRequest.FeatureEventRequest event = event(
            "550e8400-e29b-41d4-a716-446655440008",
            FeatureEventCatalog.SMART_CONSULTATION
        );

        FeatureEventBatchResponse response = service.saveBatch(device, request(event));

        assertEquals(0, response.getAccepted());
        assertEquals(1, response.getSkipped());
        assertEquals(0, response.getRejected());
    }

    private FeatureEventBatchRequest.FeatureEventRequest event(String eventId, String featureCode) {
        FeatureEventBatchRequest.FeatureEventRequest event = new FeatureEventBatchRequest.FeatureEventRequest();
        event.setEventId(eventId);
        event.setFeatureCode(featureCode);
        event.setIdempotencyKey("legacy-input-is-ignored");
        return event;
    }

    private FeatureEventBatchRequest request(FeatureEventBatchRequest.FeatureEventRequest event) {
        FeatureEventBatchRequest request = new FeatureEventBatchRequest();
        request.setEvents(Collections.singletonList(event));
        return request;
    }
}
