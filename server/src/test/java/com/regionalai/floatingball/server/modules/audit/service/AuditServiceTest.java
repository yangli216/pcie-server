package com.regionalai.floatingball.server.modules.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.audit.dto.AuditBatchRequest;
import com.regionalai.floatingball.server.modules.audit.entity.AiOpLog;
import com.regionalai.floatingball.server.modules.audit.mapper.AiOpLogMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private AiOpLogMapper aiOpLogMapper;

    @Mock
    private AiOpLogService aiOpLogService;

    @TempDir
    Path tempDir;

    private AuditService auditService;
    private AudioLogStorageService audioLogStorageService;

    @BeforeEach
    void setUp() {
        audioLogStorageService = new AudioLogStorageService(tempDir.resolve("speech-audit").toString());
        auditService = new AuditService(
            aiOpLogMapper,
            aiOpLogService,
            new ObjectMapper(),
            audioLogStorageService
        );
    }

    @Test
    void listShouldRejectInvalidDateFormat() {
        BusinessException ex = assertThrows(
            BusinessException.class,
            () -> auditService.list(1, 10, null, null, null, null, null, null, null, null, null, null, "bad-date", null)
        );

        assertEquals("日志查询时间格式非法", ex.getMessage());
    }

    @Test
    void saveBatchShouldInsertSerializedPayload() throws Exception {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");

        AuditBatchRequest request = new AuditBatchRequest();
        AuditBatchRequest.AuditEvent event = new AuditBatchRequest.AuditEvent();
        event.setEventType("operation");
        event.setHisOrgId("HIS-ORG-001");
        event.setHisOrgName("市第一医院");
        event.setTimestamp(1770000000000L);
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("module", "consultation");
        payload.put("action", "finish");
        payload.put("title", "完成问诊");
        payload.put("sourceModule", "consultation_page");
        payload.put("scene", "consultation");
        payload.put("success", true);
        event.setPayload(payload);
        request.setEvents(Collections.singletonList(event));

        int accepted = auditService.saveBatch(device, request);

        assertEquals(1, accepted);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiOpLog>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiOpLogService, times(1)).saveBatch(batchCaptor.capture());
        AiOpLog log = batchCaptor.getValue().get(0);
        assertEquals("DEV001", log.getIdDevice());
        assertEquals("ORG001", log.getIdOrg());
        assertEquals("HIS-ORG-001", log.getHisOrgId());
        assertEquals("市第一医院", log.getHisOrgName());
        assertEquals("operation", log.getSdLogType());
        assertEquals("consultation", log.getNaModule());
        assertEquals("finish", log.getOpAction());
        assertEquals("完成问诊", log.getOpTitle());
        assertEquals("consultation_page", log.getSourceModule());
        assertEquals("consultation", log.getSceneCode());
        assertEquals("完成问诊", log.getDesOp());
        assertEquals("1", log.getOpResult());
        assertNotNull(log.getOperationTime());
        assertEquals(
            OBJECT_MAPPER.readTree("{\"module\":\"consultation\",\"action\":\"finish\",\"title\":\"完成问诊\",\"sourceModule\":\"consultation_page\",\"scene\":\"consultation\",\"success\":true}"),
            OBJECT_MAPPER.readTree(log.getPayloadJson())
        );
    }

    @Test
    void saveBatchShouldFallbackToLegacyOperationFields() throws Exception {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV002");
        device.setIdOrg("ORG002");

        AuditBatchRequest request = new AuditBatchRequest();
        AuditBatchRequest.AuditEvent event = new AuditBatchRequest.AuditEvent();
        event.setEventType("operation");
        event.setTimestamp(1770000000000L);
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("operationType", "api_call");
        payload.put("operationName", "reference_feedback:diagnosis");
        payload.put("success", false);
        Map<String, Object> details = new HashMap<String, Object>();
        details.put("consultationId", "CONSULT-001");
        details.put("traceId", "TRACE-001");
        payload.put("details", details);
        event.setPayload(payload);
        request.setEvents(Collections.singletonList(event));

        int accepted = auditService.saveBatch(device, request);

        assertEquals(1, accepted);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiOpLog>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiOpLogService, times(1)).saveBatch(batchCaptor.capture());
        AiOpLog log = batchCaptor.getValue().get(0);
        assertEquals("api_call", log.getNaModule());
        assertEquals("reference_feedback:diagnosis", log.getOpAction());
        assertEquals("reference_feedback:diagnosis", log.getOpTitle());
        assertEquals("TRACE-001", log.getTraceId());
        assertEquals("CONSULT-001", log.getConsultationId());
        assertEquals("reference_feedback:diagnosis", log.getDesOp());
        assertEquals("0", log.getOpResult());
        assertEquals(
            OBJECT_MAPPER.readTree("{\"operationType\":\"api_call\",\"operationName\":\"reference_feedback:diagnosis\",\"success\":false,\"details\":{\"consultationId\":\"CONSULT-001\",\"traceId\":\"TRACE-001\"}}"),
            OBJECT_MAPPER.readTree(log.getPayloadJson())
        );
    }

    @Test
    void saveSystemLogWithAudioFileShouldPersistAudioPath() throws Exception {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV003");
        device.setIdOrg("ORG003");

        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("scene", "chat-input");
        payload.put("requestBody", Collections.singletonMap("fileName", "speech.wav"));

        auditService.saveSystemLogWithAudioFile(
            device,
            "speech_proxy",
            "speech",
            "transcribe",
            payload,
            true,
            "hello-audio".getBytes(StandardCharsets.UTF_8),
            "speech.wav"
        );

        ArgumentCaptor<AiOpLog> captor = ArgumentCaptor.forClass(AiOpLog.class);
        verify(aiOpLogMapper, times(1)).insert(captor.capture());
        AiOpLog log = captor.getValue();
        assertEquals("speech_proxy", log.getSdLogType());
        assertEquals("transcribe", log.getOpAction());
        assertEquals("transcribe", log.getOpTitle());
        assertEquals("chat-input", log.getSceneCode());
        assertNotNull(log.getAudioFilePath());
        assertEquals(
            "hello-audio",
            new String(Files.readAllBytes(audioLogStorageService.resolveExistingPath(log.getAudioFilePath())), StandardCharsets.UTF_8)
        );
        assertEquals(
            OBJECT_MAPPER.readTree("{\"scene\":\"chat-input\",\"requestBody\":{\"fileName\":\"speech.wav\"}}"),
            OBJECT_MAPPER.readTree(log.getPayloadJson())
        );
    }

    @Test
    void saveSystemLogWithAudioFileShouldFailWhenSuccessfulOperationAuditCannotPersist() throws Exception {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV004");
        device.setIdOrg("ORG004");
        when(aiOpLogMapper.insert(any(AiOpLog.class))).thenThrow(new RuntimeException("database unavailable"));

        BusinessException ex = assertThrows(BusinessException.class, () -> auditService.saveSystemLogWithAudioFile(
            device,
            "speech_proxy",
            "speech",
            "transcribe",
            Collections.singletonMap("scene", "chat-input"),
            true,
            "hello-audio".getBytes(StandardCharsets.UTF_8),
            "speech.wav"
        ));

        assertEquals("AUDIT-PERSIST-FAILED", ex.getCode());
        assertEquals(0L, countRegularFiles(tempDir.resolve("speech-audit")));
    }

    private long countRegularFiles(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0L;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
