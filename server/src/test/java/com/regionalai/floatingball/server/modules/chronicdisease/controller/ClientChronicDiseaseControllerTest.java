package com.regionalai.floatingball.server.modules.chronicdisease.controller;

import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicArtifactSnapshotRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicArtifactSnapshotResponse;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicDiseaseFollowUpRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicDiseaseFollowUpResponse;
import com.regionalai.floatingball.server.modules.chronicdisease.service.ChronicArtifactSnapshotService;
import com.regionalai.floatingball.server.modules.chronicdisease.service.ChronicDiseaseFollowUpService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ClientChronicDiseaseControllerTest {

    @Mock
    private ChronicDiseaseFollowUpService followUpService;

    @Mock
    private ChronicArtifactSnapshotService artifactSnapshotService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ClientChronicDiseaseController(
                followUpService,
                artifactSnapshotService
            ))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        DeviceContextHolder.clear();
    }

    @Test
    void saveShouldUseDeviceContextAndReturnPublishedVersions() throws Exception {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");
        DeviceContextHolder.set(device);
        when(followUpService.save(
                eq(device),
                eq("RID-chronic-save"),
                any(ChronicDiseaseFollowUpRequest.class)
            ))
            .thenReturn(new ChronicDiseaseFollowUpResponse(
                "FU001",
                "RID-chronic-save",
                "saved",
                LocalDateTime.of(2026, 7, 23, 10, 30),
                "P001",
                "R001",
                "1,2"
            ));

        mockMvc.perform(post("/v1/client/chronic-disease/follow-ups")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-chronic-save")
                .content(validRequestJson()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-chronic-save"))
            .andExpect(jsonPath("$.data.followUpId").value("FU001"))
            .andExpect(jsonPath("$.data.status").value("saved"))
            .andExpect(jsonPath("$.data.idPhr").value("P001"))
            .andExpect(jsonPath("$.data.idRecord").value("R001"))
            .andExpect(jsonPath("$.data.sdVisitKind").value("1,2"));

        verify(followUpService).save(
            eq(device),
            eq("RID-chronic-save"),
            any(ChronicDiseaseFollowUpRequest.class)
        );
    }

    @Test
    void saveShouldRejectMissingPatientBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/v1/client/chronic-disease/follow-ups")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-chronic-invalid")
                .content(validRequestJson().replace("\"idPhr\":\"P001\",", "")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION-001"))
            .andExpect(jsonPath("$.requestId").value("RID-chronic-invalid"))
            .andExpect(jsonPath("$.message").value("人员主键 idPhr 不能为空"));

        verifyNoInteractions(followUpService, artifactSnapshotService);
    }

    @Test
    void saveArtifactSnapshotShouldUseDeviceContext() throws Exception {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");
        DeviceContextHolder.set(device);
        when(artifactSnapshotService.save(eq(device), any(ChronicArtifactSnapshotRequest.class)))
            .thenReturn(new ChronicArtifactSnapshotResponse(
                "SNAP001",
                "ART001",
                "saved",
                LocalDateTime.of(2026, 7, 24, 10, 30),
                "health_prescription"
            ));

        mockMvc.perform(post("/v1/client/chronic-disease/artifact-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-chronic-artifact")
                .content(validArtifactJson()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-chronic-artifact"))
            .andExpect(jsonPath("$.data.snapshotId").value("SNAP001"))
            .andExpect(jsonPath("$.data.artifactType").value("health_prescription"));

        verify(artifactSnapshotService).save(
            eq(device),
            any(ChronicArtifactSnapshotRequest.class)
        );
    }

    private String validRequestJson() {
        return "{"
            + "\"idPhr\":\"P001\","
            + "\"idRecord\":\"R001\","
            + "\"id\":\"\","
            + "\"status\":\"3\","
            + "\"sdVisitKind\":\"1,2\","
            + "\"pressureH\":\"148\","
            + "\"pressureL\":\"92\","
            + "\"glu\":\"7.2\","
            + "\"isGlu\":\"1\","
            + "\"sdHySymptom\":\"1\","
            + "\"sdDbsSymptom\":\"1\","
            + "\"drugList\":[]"
            + "}";
    }

    private String validArtifactJson() {
        return "{"
            + "\"requestId\":\"ART001\","
            + "\"artifactType\":\"health_prescription\","
            + "\"patientId\":\"PAT001\","
            + "\"visitId\":\"VISIT001\","
            + "\"patientName\":\"孔文\","
            + "\"diseaseTypes\":[\"hypertension\"],"
            + "\"dataAsOf\":\"2026-07-24T10:00:00+08:00\","
            + "\"templateVersions\":[\"HTN-FOLLOWUP-2026.1\"],"
            + "\"pathVersions\":[\"HTN-PATH-2024.1\"],"
            + "\"evidenceVersions\":[\"中国高血压防治指南-2024\"],"
            + "\"ruleVersion\":\"CHRONIC-RULE-2026.1\","
            + "\"summaryText\":\"医生确认健康处方\","
            + "\"bloodPressureRecordCount\":1,"
            + "\"bloodGlucoseRecordCount\":0,"
            + "\"acceptedItems\":[{"
            + "\"itemId\":\"ITEM001\","
            + "\"category\":\"test\","
            + "\"title\":\"复查血脂\","
            + "\"detail\":\"结合风险完成复查\","
            + "\"reason\":\"患者证据\""
            + "}],"
            + "\"doctorName\":\"李医生\""
            + "}";
    }
}
