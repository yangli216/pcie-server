package com.regionalai.floatingball.server.modules.chronicdisease.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicArtifactSnapshotItemRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicArtifactSnapshotRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicArtifactSnapshotResponse;
import com.regionalai.floatingball.server.modules.chronicdisease.entity.AiChronicArtifactSnapshot;
import com.regionalai.floatingball.server.modules.chronicdisease.mapper.AiChronicArtifactSnapshotMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChronicArtifactSnapshotServiceTest {

    @Mock
    private AiChronicArtifactSnapshotMapper snapshotMapper;

    @Mock
    private ChronicArtifactSnapshotWriter snapshotWriter;

    private ChronicArtifactSnapshotService service;
    private AiDevice device;

    @BeforeEach
    void setUp() {
        service = new ChronicArtifactSnapshotService(
            snapshotMapper,
            snapshotWriter,
            new ObjectMapper()
        );
        device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");
    }

    @Test
    void saveShouldPersistDoctorConfirmedHealthPrescriptionSnapshot() {
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            AiChronicArtifactSnapshot entity = invocation.getArgument(0);
            entity.setIdSnapshot("SNAP001");
            entity.setInsertTime(LocalDateTime.of(2026, 7, 24, 10, 30));
            return null;
        }).when(snapshotWriter).insert(any(AiChronicArtifactSnapshot.class));

        ChronicArtifactSnapshotResponse response = service.save(device, healthRequest());

        assertEquals("SNAP001", response.getSnapshotId());
        assertEquals("health_prescription", response.getArtifactType());

        ArgumentCaptor<AiChronicArtifactSnapshot> captor =
            ArgumentCaptor.forClass(AiChronicArtifactSnapshot.class);
        verify(snapshotWriter).insert(captor.capture());
        AiChronicArtifactSnapshot saved = captor.getValue();
        assertEquals("ORG001", saved.getIdOrg());
        assertEquals("[\"hypertension\"]", saved.getDiseaseTypesJson());
        assertEquals("[\"HTN-FOLLOWUP-2026.1\"]", saved.getTemplateVersionsJson());
        assertEquals("李医生", saved.getNaDoctor());
    }

    @Test
    void saveShouldAllowAnnualAssessmentWithoutAcceptedItems() {
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        ChronicArtifactSnapshotRequest request = healthRequest();
        request.setRequestId("ANNUAL001");
        request.setArtifactType("annual_assessment");
        request.setAssessmentYear(2026);
        request.setAcceptedItems(Collections.<ChronicArtifactSnapshotItemRequest>emptyList());

        ChronicArtifactSnapshotResponse response = service.save(device, request);

        assertEquals("annual_assessment", response.getArtifactType());
        verify(snapshotWriter).insert(any(AiChronicArtifactSnapshot.class));
    }

    @Test
    void saveShouldRejectIncompletePublishedVersionCoverage() {
        ChronicArtifactSnapshotRequest request = healthRequest();
        request.setDiseaseTypes(Arrays.asList("hypertension", "type2_diabetes"));

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device, request)
        );

        assertEquals("表单模板版本未完整覆盖当前病种或包含未发布版本", error.getMessage());
        verify(snapshotWriter, never()).insert(any(AiChronicArtifactSnapshot.class));
    }

    @Test
    void saveShouldRejectRequestIdReusedForDifferentArtifactType() {
        AiChronicArtifactSnapshot existing = new AiChronicArtifactSnapshot();
        existing.setIdSnapshot("SNAP-EXISTING");
        existing.setRequestId("ART001");
        existing.setPatientId("PAT001");
        existing.setVisitId("VISIT001");
        existing.setArtifactType("annual_assessment");
        existing.setDiseaseTypesJson("[\"hypertension\"]");
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.save(device, healthRequest())
        );

        assertEquals("CHRONIC-ARTIFACT-CONFLICT", error.getCode());
        verify(snapshotWriter, never()).insert(any(AiChronicArtifactSnapshot.class));
    }

    private ChronicArtifactSnapshotRequest healthRequest() {
        ChronicArtifactSnapshotRequest request = new ChronicArtifactSnapshotRequest();
        request.setRequestId(" ART001 ");
        request.setArtifactType("health_prescription");
        request.setPatientId("PAT001");
        request.setVisitId("VISIT001");
        request.setPatientName("孔文");
        request.setDiseaseTypes(Collections.singletonList("hypertension"));
        request.setDataAsOf("2026-07-24T10:00:00+08:00");
        request.setTemplateVersions(Collections.singletonList("HTN-FOLLOWUP-2026.1"));
        request.setPathVersions(Collections.singletonList("HTN-PATH-2024.1"));
        request.setEvidenceVersions(Collections.singletonList("中国高血压防治指南-2024"));
        request.setRuleVersion("CHRONIC-RULE-2026.1");
        request.setSummaryText("医生确认健康处方");
        request.setSystolicPressure(136);
        request.setDiastolicPressure(84);
        request.setBloodPressureRecordCount(1);
        request.setBloodGlucoseRecordCount(0);
        request.setDoctorId("DOC001");
        request.setDoctorName("李医生");

        ChronicArtifactSnapshotItemRequest item = new ChronicArtifactSnapshotItemRequest();
        item.setItemId("ITEM001");
        item.setCategory("test");
        item.setTitle("复查血脂");
        item.setDetail("结合风险完成复查");
        item.setReason("患者证据");
        request.setAcceptedItems(new ArrayList<ChronicArtifactSnapshotItemRequest>(
            Collections.singletonList(item)
        ));
        return request;
    }
}
