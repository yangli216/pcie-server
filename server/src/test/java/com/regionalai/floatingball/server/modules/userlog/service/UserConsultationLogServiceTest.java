package com.regionalai.floatingball.server.modules.userlog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.audit.entity.AiOpLog;
import com.regionalai.floatingball.server.modules.audit.service.AudioLogStorageService;
import com.regionalai.floatingball.server.modules.audit.mapper.AiOpLogMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.userlog.dto.UserConsultationLogRequest;
import com.regionalai.floatingball.server.modules.userlog.entity.AiUserConsultationLog;
import com.regionalai.floatingball.server.modules.userlog.mapper.AiUserConsultationLogMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserConsultationLogServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private AiUserConsultationLogMapper mapper;

    @Mock
    private AiOpLogMapper opLogMapper;

    @Mock
    private AudioLogStorageService audioLogStorageService;

    private UserConsultationLogService service;

    @BeforeEach
    void setUp() {
        service = new UserConsultationLogService(mapper, opLogMapper, new ObjectMapper(), audioLogStorageService, new DatabaseDialect(DatabaseDialect.Kind.ORACLE));
    }

    @Test
    void saveShouldInsertFirstSnapshot() throws Exception {
        when(mapper.selectOne(any())).thenReturn(null);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");

        Map<String, Object> firstSnapshot = new HashMap<String, Object>();
        firstSnapshot.put("chiefComplaint", "咳嗽3天");
        firstSnapshot.put("diagnoses", Collections.singletonList(Collections.singletonMap("name", "急性支气管炎")));

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("voice");
        request.setPatientId("P001");
        request.setPatientName("王某");
        request.setDoctorId("D001");
        request.setDoctorWorkNo("0123");
        request.setDoctorName("张医生");
        request.setOrgCode("HIS-ORG-001");
        request.setHisOrgId("HIS-ORG-ID-001");
        request.setOrgName("区域中心医院");
        request.setFirstSnapshot(firstSnapshot);

        service.save(device, request);

        ArgumentCaptor<AiUserConsultationLog> captor = ArgumentCaptor.forClass(AiUserConsultationLog.class);
        verify(mapper).insert(captor.capture());
        verify(mapper, never()).updateById(any());

        AiUserConsultationLog saved = captor.getValue();
        assertEquals("CONSULT-001", saved.getConsultationId());
        assertEquals("ROUND-001", saved.getConsultationRoundId());
        assertEquals("voice", saved.getConsultationType());
        assertEquals("DEV001", saved.getIdDevice());
        assertEquals("ORG001", saved.getIdOrg());
        assertEquals("HIS-ORG-ID-001", saved.getHisOrgId());
        assertEquals("区域中心医院", saved.getNaOrg());
        assertEquals("D001", saved.getIdDoctor());
        assertEquals("0123", saved.getDoctorWorkNo());
        assertEquals("张医生", saved.getNaDoctor());
        assertEquals("王某", saved.getPatientName());
        assertEquals("generated", saved.getStatus());
        assertEquals(OBJECT_MAPPER.valueToTree(firstSnapshot), OBJECT_MAPPER.readTree(saved.getFirstSnapshotJson()));
    }

    @Test
    void saveShouldKeepDeviceOrgAndPersistHisOrgSeparately() {
        when(mapper.selectOne(any())).thenReturn(null);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("SERVER-ORG-001");

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("smart");
        request.setOrgCode("HIS-ORG-FALLBACK");
        request.setHisOrgId("HIS-ORG-001");

        service.save(device, request);

        ArgumentCaptor<AiUserConsultationLog> captor = ArgumentCaptor.forClass(AiUserConsultationLog.class);
        verify(mapper).insert(captor.capture());

        AiUserConsultationLog saved = captor.getValue();
        assertEquals("SERVER-ORG-001", saved.getIdOrg());
        assertEquals("HIS-ORG-001", saved.getHisOrgId());
    }

    @Test
    void saveShouldNotUseOrgCodeAsHisOrgFallback() {
        when(mapper.selectOne(any())).thenReturn(null);
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("SERVER-ORG-001");

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("smart");
        request.setOrgCode("HIS-ORG-LEGACY");

        service.save(device, request);

        ArgumentCaptor<AiUserConsultationLog> captor = ArgumentCaptor.forClass(AiUserConsultationLog.class);
        verify(mapper).insert(captor.capture());

        AiUserConsultationLog saved = captor.getValue();
        assertEquals("SERVER-ORG-001", saved.getIdOrg());
        assertEquals(null, saved.getHisOrgId());
    }

    @Test
    void saveShouldPersistSpeechTextAndAudioMetadata() throws Exception {
        when(mapper.selectOne(any())).thenReturn(null);
        when(audioLogStorageService.store(any(byte[].class), any(String.class), any(String.class)))
            .thenReturn("/tmp/floating-ball-server/speech-audit/voice.wav");
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("voice");
        request.setSpeechText("患者发热一天");
        request.setAudio(Base64.getEncoder().encodeToString("audio-bytes".getBytes("UTF-8")));
        request.setAudioMimeType("audio/wav");
        request.setAudioFileName("voice.wav");

        service.save(device, request);

        ArgumentCaptor<AiUserConsultationLog> captor = ArgumentCaptor.forClass(AiUserConsultationLog.class);
        verify(mapper).insert(captor.capture());

        AiUserConsultationLog saved = captor.getValue();
        assertEquals("患者发热一天", saved.getSpeechText());
        assertEquals("/tmp/floating-ball-server/speech-audit/voice.wav", saved.getAudioFilePath());
        assertEquals("voice.wav", saved.getAudioFileName());
        assertEquals("audio/wav", saved.getAudioMimeType());
        assertEquals(Long.valueOf("audio-bytes".getBytes("UTF-8").length), saved.getAudioSize());
        assertEquals(Boolean.TRUE, saved.getHasAudio());
        assertEquals(Boolean.TRUE, saved.getHasSpeechText());
    }

    @Test
    void saveShouldUpdateFinalSnapshotOnExistingRecord() throws Exception {
        AiUserConsultationLog existing = new AiUserConsultationLog();
        existing.setIdLog("LOG001");
        existing.setConsultationId("CONSULT-001");
        existing.setConsultationRoundId("ROUND-001");
        existing.setConsultationType("voice");
        existing.setIdDevice("DEV001");
        existing.setFgActive("1");
        existing.setStatus("generated");
        existing.setFirstSnapshotJson("{\"chiefComplaint\":\"咳嗽3天\"}");
        when(mapper.selectOne(any())).thenReturn(existing);

        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");

        Map<String, Object> finalSnapshot = new HashMap<String, Object>();
        finalSnapshot.put("chiefComplaint", "咳嗽、咳痰3天");
        Map<String, Object> selectionSnapshot = new HashMap<String, Object>();
        selectionSnapshot.put("selectedMedicineNames", Collections.singletonList("氨溴索"));

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("voice");
        request.setFinalSnapshot(finalSnapshot);
        request.setSelectionSnapshot(selectionSnapshot);

        service.save(device, request);

        ArgumentCaptor<AiUserConsultationLog> captor = ArgumentCaptor.forClass(AiUserConsultationLog.class);
        verify(mapper, never()).insert(any());
        verify(mapper).updateById(captor.capture());

        AiUserConsultationLog updated = captor.getValue();
        assertEquals("LOG001", updated.getIdLog());
        assertEquals("completed", updated.getStatus());
        assertEquals(OBJECT_MAPPER.readTree("{\"chiefComplaint\":\"咳嗽3天\"}"), OBJECT_MAPPER.readTree(updated.getFirstSnapshotJson()));
        assertEquals(OBJECT_MAPPER.valueToTree(finalSnapshot), OBJECT_MAPPER.readTree(updated.getFinalSnapshotJson()));
        assertEquals(OBJECT_MAPPER.valueToTree(selectionSnapshot), OBJECT_MAPPER.readTree(updated.getSelectionJson()));
    }

    @Test
    void saveShouldUpdateStatusToAbandonedWhenAbandonedFlagIsTrue() throws Exception {
        // 模拟"放弃"场景：已有一条 generated 记录，提交 abandoned=true 后应更新为 abandoned
        AiUserConsultationLog existing = new AiUserConsultationLog();
        existing.setIdLog("LOG001");
        existing.setConsultationId("CONSULT-001");
        existing.setConsultationRoundId("ROUND-001");
        existing.setConsultationType("smart");
        existing.setIdDevice("DEV001");
        existing.setFgActive("1");
        existing.setStatus("generated");
        existing.setFirstSnapshotJson("{\"chiefComplaint\":\"发热1天\"}");
        when(mapper.selectOne(any())).thenReturn(existing);

        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");

        Map<String, Object> finalSnapshot = new HashMap<String, Object>();
        finalSnapshot.put("chiefComplaint", "发热1天");

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("smart");
        request.setFinalSnapshot(finalSnapshot);
        request.setAbandoned(Boolean.TRUE);

        service.save(device, request);

        ArgumentCaptor<AiUserConsultationLog> captor = ArgumentCaptor.forClass(AiUserConsultationLog.class);
        verify(mapper, never()).insert(any());
        verify(mapper).updateById(captor.capture());

        AiUserConsultationLog updated = captor.getValue();
        assertEquals("LOG001", updated.getIdLog());
        assertEquals("abandoned", updated.getStatus());
        assertEquals(OBJECT_MAPPER.valueToTree(finalSnapshot), OBJECT_MAPPER.readTree(updated.getFinalSnapshotJson()));
    }

    @Test
    void saveShouldSetAbandonedEvenWithoutFinalSnapshot() {
        // 模拟"放弃"场景：已有一条 generated 记录，仅提交 abandoned=true（无 finalSnapshot）也应更新为 abandoned
        AiUserConsultationLog existing = new AiUserConsultationLog();
        existing.setIdLog("LOG001");
        existing.setConsultationId("CONSULT-001");
        existing.setConsultationRoundId("ROUND-001");
        existing.setConsultationType("voice");
        existing.setIdDevice("DEV001");
        existing.setFgActive("1");
        existing.setStatus("generated");
        when(mapper.selectOne(any())).thenReturn(existing);

        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("voice");
        request.setAbandoned(Boolean.TRUE);

        service.save(device, request);

        ArgumentCaptor<AiUserConsultationLog> captor = ArgumentCaptor.forClass(AiUserConsultationLog.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("abandoned", captor.getValue().getStatus());
    }

    @Test
    void saveShouldRetryAsUpdateWhenConcurrentCreateHitsUniqueConstraint() {
        AiUserConsultationLog existing = new AiUserConsultationLog();
        existing.setIdLog("LOG001");
        existing.setConsultationId("CONSULT-001");
        existing.setConsultationRoundId("ROUND-001");
        existing.setConsultationType("voice");
        existing.setIdDevice("DEV001");
        existing.setFgActive("1");
        existing.setStatus("generated");

        when(mapper.selectOne(any())).thenReturn(null).thenReturn(existing);
        when(mapper.insert(any(AiUserConsultationLog.class)))
            .thenThrow(new DuplicateKeyException("uk_c_ai_user_log_round_active"));

        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");

        Map<String, Object> finalSnapshot = new HashMap<String, Object>();
        finalSnapshot.put("chiefComplaint", "咳嗽、咳痰3天");

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("voice");
        request.setFinalSnapshot(finalSnapshot);

        AiUserConsultationLog result = service.save(device, request);

        assertEquals("LOG001", result.getIdLog());
        assertEquals("completed", result.getStatus());
        verify(mapper).insert(any(AiUserConsultationLog.class));
        verify(mapper).updateById(existing);
    }

    @Test
    void saveShouldCleanNewAudioWhenConcurrentCreateRetryIsNeeded() throws Exception {
        AiUserConsultationLog existing = new AiUserConsultationLog();
        existing.setIdLog("LOG001");
        existing.setConsultationId("CONSULT-001");
        existing.setConsultationRoundId("ROUND-001");
        existing.setConsultationType("voice");
        existing.setIdDevice("DEV001");
        existing.setFgActive("1");
        existing.setStatus("generated");

        when(mapper.selectOne(any())).thenReturn(null).thenReturn(existing);
        when(mapper.insert(any(AiUserConsultationLog.class)))
            .thenThrow(new DuplicateKeyException("uk_c_ai_user_log_round_active"));
        when(audioLogStorageService.store(any(byte[].class), any(String.class), any(String.class)))
            .thenReturn("/tmp/floating-ball-server/speech-audit/new.wav");

        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("voice");
        request.setAudio(Base64.getEncoder().encodeToString("audio-bytes".getBytes("UTF-8")));
        request.setAudioMimeType("audio/wav");
        request.setAudioFileName("voice.wav");

        service.save(device, request);

        verify(audioLogStorageService).deleteQuietly("/tmp/floating-ball-server/speech-audit/new.wav");
        verify(mapper).updateById(existing);
    }

    @Test
    void saveShouldDeletePreviousAudioOnlyAfterTransactionCommit() throws Exception {
        AiUserConsultationLog existing = existingGeneratedLogWithAudio("v1/20260810/old.wav");
        when(mapper.selectOne(any())).thenReturn(existing);
        when(audioLogStorageService.store(any(byte[].class), any(String.class), any(String.class)))
            .thenReturn("v1/20260811/new.wav");
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.save(device(), audioRequest());

            verify(audioLogStorageService, never()).deleteQuietly("v1/20260810/old.wav");
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.get(0).afterCommit();
            synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            verify(audioLogStorageService).deleteQuietly("v1/20260810/old.wav");
            verify(audioLogStorageService, never()).deleteQuietly("v1/20260811/new.wav");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void saveShouldKeepPreviousAudioAndDeleteNewAudioAfterTransactionRollback() throws Exception {
        AiUserConsultationLog existing = existingGeneratedLogWithAudio("v1/20260810/old.wav");
        when(mapper.selectOne(any())).thenReturn(existing);
        when(audioLogStorageService.store(any(byte[].class), any(String.class), any(String.class)))
            .thenReturn("v1/20260811/new.wav");
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.save(device(), audioRequest());

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(audioLogStorageService, never()).deleteQuietly("v1/20260810/old.wav");
            verify(audioLogStorageService).deleteQuietly("v1/20260811/new.wav");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private AiUserConsultationLog existingGeneratedLogWithAudio(String audioPath) {
        AiUserConsultationLog existing = new AiUserConsultationLog();
        existing.setIdLog("LOG001");
        existing.setConsultationId("CONSULT-001");
        existing.setConsultationRoundId("ROUND-001");
        existing.setConsultationType("voice");
        existing.setIdDevice("DEV001");
        existing.setFgActive("1");
        existing.setStatus("generated");
        existing.setAudioFilePath(audioPath);
        return existing;
    }

    private AiDevice device() {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");
        return device;
    }

    private UserConsultationLogRequest audioRequest() throws Exception {
        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("voice");
        request.setAudio(Base64.getEncoder().encodeToString("audio-bytes".getBytes("UTF-8")));
        request.setAudioMimeType("audio/wav");
        request.setAudioFileName("voice.wav");
        return request;
    }

    @Test
    void saveShouldRejectUnsupportedConsultationType() {
        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-001");
        request.setConsultationType("raw");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.save(null, request));

        assertEquals("问诊类型非法", ex.getMessage());
    }

    @Test
    void saveShouldAcceptNewClinicalScenarioTypes() {
        when(mapper.selectOne(any())).thenReturn(null);
        String[] types = {"chronic_refill", "report_consultation", "report_interpretation"};

        for (int i = 0; i < types.length; i++) {
            UserConsultationLogRequest request = new UserConsultationLogRequest();
            request.setConsultationId("CONSULT-" + i);
            request.setConsultationRoundId("ROUND-" + i);
            request.setConsultationType(types[i]);
            request.setFirstSnapshot(Collections.singletonMap("scene", types[i]));
            service.save(null, request);
        }

        ArgumentCaptor<AiUserConsultationLog> captor = ArgumentCaptor.forClass(AiUserConsultationLog.class);
        verify(mapper, times(3)).insert(captor.capture());
        assertEquals("chronic_refill", captor.getAllValues().get(0).getConsultationType());
        assertEquals("report_consultation", captor.getAllValues().get(1).getConsultationType());
        assertEquals("report_interpretation", captor.getAllValues().get(2).getConsultationType());
    }

    @Test
    void saveShouldRejectMissingConsultationRoundId() {
        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationType("voice");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.save(null, request));

        assertEquals("问诊轮次ID不能为空", ex.getMessage());
    }

    @Test
    void listShouldRejectInvalidDateFormat() {
        BusinessException ex = assertThrows(
            BusinessException.class,
            () -> service.list(1, 10, null, null, null, null, null, "bad-date", null)
        );

        assertEquals("用户日志查询时间格式非法", ex.getMessage());
    }

    @Test
    void saveShouldCreateNewRecordWhenPreviousConsultationAlreadyCompleted() throws Exception {
        // 第一轮问诊已完成（status=completed），同一就诊再次发起问诊时必须新建记录，不能覆盖
        AiUserConsultationLog previousCompleted = new AiUserConsultationLog();
        previousCompleted.setIdLog("LOG-OLD");
        previousCompleted.setConsultationId("CONSULT-001");
        previousCompleted.setConsultationRoundId("ROUND-001");
        previousCompleted.setConsultationType("voice");
        previousCompleted.setIdDevice("DEV001");
        previousCompleted.setFgActive("1");
        previousCompleted.setStatus("completed");
        previousCompleted.setFirstSnapshotJson("{\"chiefComplaint\":\"咳嗽3天\"}");
        previousCompleted.setFinalSnapshotJson("{\"chiefComplaint\":\"咳嗽3天\"}");

        // findExisting 按 consultationRoundId 查找且只匹配 status=generated 的记录，
        // 第二轮使用新的 roundId，已 completed 的第一轮记录不会被命中
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(AiUserConsultationLog.class))).thenAnswer(invocation -> {
            AiUserConsultationLog entity = invocation.getArgument(0);
            entity.setIdLog("LOG-NEW");
            return 1;
        });

        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");

        Map<String, Object> firstSnapshot = new HashMap<String, Object>();
        firstSnapshot.put("chiefComplaint", "发热1天");

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-002");
        request.setConsultationType("voice");
        request.setPatientId("P001");
        request.setPatientName("王某");
        request.setFirstSnapshot(firstSnapshot);

        AiUserConsultationLog result = service.save(device, request);

        // 必须是新建，不是更新旧记录
        verify(mapper).insert(any(AiUserConsultationLog.class));
        verify(mapper, never()).updateById(any());
        assertEquals("LOG-NEW", result.getIdLog());
        assertEquals("ROUND-002", result.getConsultationRoundId());
        assertEquals("generated", result.getStatus());
        assertEquals(OBJECT_MAPPER.valueToTree(firstSnapshot), OBJECT_MAPPER.readTree(result.getFirstSnapshotJson()));
    }

    @Test
    void saveShouldCreateNewRecordWhenPreviousConsultationAlreadyAbandoned() throws Exception {
        // 第一轮问诊已放弃（status=abandoned），同一就诊再次发起问诊时必须新建记录
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(AiUserConsultationLog.class))).thenAnswer(invocation -> {
            AiUserConsultationLog entity = invocation.getArgument(0);
            entity.setIdLog("LOG-NEW");
            return 1;
        });

        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");

        Map<String, Object> firstSnapshot = new HashMap<String, Object>();
        firstSnapshot.put("chiefComplaint", "发热1天");

        UserConsultationLogRequest request = new UserConsultationLogRequest();
        request.setConsultationId("CONSULT-001");
        request.setConsultationRoundId("ROUND-002");
        request.setConsultationType("smart");
        request.setFirstSnapshot(firstSnapshot);

        AiUserConsultationLog result = service.save(device, request);

        verify(mapper).insert(any(AiUserConsultationLog.class));
        verify(mapper, never()).updateById(any());
        assertEquals("generated", result.getStatus());
    }

    @Test
    void getTimelineShouldExposeDisplayFields() {
        AiUserConsultationLog log = new AiUserConsultationLog();
        log.setIdLog("LOG001");
        log.setFgActive("1");
        log.setConsultationId("CONSULT-001");

        AiOpLog opLog = new AiOpLog();
        opLog.setSdLogType("operation");
        opLog.setNaModule("consultation");
        opLog.setOpAction("complete_consultation");
        opLog.setOpTitle("完成智能问诊");
        opLog.setDesOp("完成智能问诊");
        opLog.setOpResult("1");
        opLog.setOperationTime(java.time.LocalDateTime.of(2026, 5, 1, 10, 1, 0));
        opLog.setPayloadJson("{}");

        when(mapper.selectById("LOG001")).thenReturn(log);
        when(opLogMapper.selectList(any())).thenReturn(Collections.singletonList(opLog));

        List<com.regionalai.floatingball.server.modules.userlog.dto.ConsultationTimelineItem> timeline = service.getTimeline("LOG001");

        assertEquals(1, timeline.size());
        assertEquals("智能问诊", timeline.get(0).getDisplayModule());
        assertEquals("完成智能问诊", timeline.get(0).getDisplayAction());
    }

    @Test
    void exportExcelShouldCreateWorkbookWithFontIndependentColumnWidths() throws Exception {
        AiUserConsultationLog record = new AiUserConsultationLog();
        record.setNaOrg("区域中心医院");
        record.setIdOrg("ORG001");
        record.setHisOrgId("HIS-ORG-001");
        record.setNaDoctor("张医生");
        record.setConsultationTime(java.time.LocalDateTime.of(2026, 7, 16, 17, 19, 0));
        record.setPatientName("王某");
        record.setPatientGender("男");
        record.setPatientAge("42");
        record.setConsultationType("voice");
        record.setStatus("completed");
        record.setTotalChanges(2);
        record.setConsultationId("CONSULT-001");
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(record));

        byte[] data = service.exportExcel(null, null, null, null, null, null, null);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertEquals("用户日志", workbook.getSheetAt(0).getSheetName());
            assertEquals("区域中心医院", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals(14 * 256, workbook.getSheetAt(0).getColumnWidth(0));
        }
    }
}
