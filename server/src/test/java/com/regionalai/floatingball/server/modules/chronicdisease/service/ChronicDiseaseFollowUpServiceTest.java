package com.regionalai.floatingball.server.modules.chronicdisease.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicDiseaseFollowUpRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicDiseaseFollowUpResponse;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.TcdVisitDrugRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.entity.AiChronicDiseaseFollowUp;
import com.regionalai.floatingball.server.modules.chronicdisease.mapper.AiChronicDiseaseFollowUpMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChronicDiseaseFollowUpServiceTest {

    @Mock
    private AiChronicDiseaseFollowUpMapper followUpMapper;

    @Mock
    private ChronicDiseaseFollowUpWriter followUpWriter;

    private ChronicDiseaseFollowUpService service;
    private AiDevice device;

    @BeforeEach
    void setUp() {
        service = new ChronicDiseaseFollowUpService(
            followUpMapper,
            followUpWriter,
            new ObjectMapper()
        );
        device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");
    }

    @Test
    void saveShouldPersistOneExactCombinedTcdVisitFormSnapshot() {
        when(followUpMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            AiChronicDiseaseFollowUp entity = invocation.getArgument(0);
            entity.setIdFollowUp("FU001");
            entity.setInsertTime(LocalDateTime.of(2026, 7, 24, 10, 30));
            return null;
        }).when(followUpWriter).insert(any(AiChronicDiseaseFollowUp.class));

        ChronicDiseaseFollowUpResponse response =
            service.save(device, "REQ-TCD-001", validCombinedRequest());

        assertEquals("FU001", response.getFollowUpId());
        assertEquals("1,2", response.getSdVisitKind());

        ArgumentCaptor<AiChronicDiseaseFollowUp> captor =
            ArgumentCaptor.forClass(AiChronicDiseaseFollowUp.class);
        verify(followUpWriter).insert(captor.capture());
        AiChronicDiseaseFollowUp saved = captor.getValue();
        assertEquals("ORG001", saved.getIdOrg());
        assertEquals("P001", saved.getIdPhr());
        assertEquals("R001", saved.getIdRecord());
        assertEquals("combined", saved.getDiseaseType());
        assertEquals("25.00", saved.getBmi().toPlainString());
        assertEquals("1", saved.getSdVisitKind().substring(0, 1));
        assertTrue(saved.getFormDataJson().contains("\"sdVisitKind\":\"1,2\""));
        assertTrue(saved.getFormDataJson().contains("\"pressureH\":\"138\""));
        assertTrue(saved.getFormDataJson().contains("\"drugList\":[{"));
        assertTrue(!saved.getFormDataJson().contains("无ID药品"));
    }

    @Test
    void saveShouldRejectDiabetesVisitWithoutSelectedGlucose() {
        ChronicDiseaseFollowUpRequest request = validCombinedRequest();
        request.setGlu("");

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device, "REQ-TCD-001", request)
        );

        assertTrue(error.getMessage().contains("血糖"));
        verify(followUpWriter, never()).insert(any(AiChronicDiseaseFollowUp.class));
    }

    @Test
    void saveShouldRejectNoneSymptomCombinedWithConcreteSymptoms() {
        ChronicDiseaseFollowUpRequest request = validCombinedRequest();
        request.setSdHySymptom("1,2");

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device, "REQ-TCD-001", request)
        );

        assertTrue(error.getMessage().contains("不能与其它选项并存"));
    }

    @Test
    void saveShouldReturnExistingRecordForSameHeaderIdempotencyScope() {
        AiChronicDiseaseFollowUp existing = existing("REQ-TCD-001", "P001", "R001", "1,2");
        when(followUpMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        ChronicDiseaseFollowUpResponse response =
            service.save(device, "REQ-TCD-001", validCombinedRequest());

        assertEquals("FU-EXISTING", response.getFollowUpId());
        verify(followUpWriter, never()).insert(any(AiChronicDiseaseFollowUp.class));
    }

    @Test
    void saveShouldResolveConcurrentDuplicateAfterIsolatedInsertRollback() {
        AiChronicDiseaseFollowUp existing = existing("REQ-TCD-001", "P001", "R001", "1,2");
        when(followUpMapper.selectOne(any(Wrapper.class)))
            .thenReturn(null)
            .thenReturn(existing);
        doThrow(new DuplicateKeyException("uk_c_ai_chronic_fu_req"))
            .when(followUpWriter)
            .insert(any(AiChronicDiseaseFollowUp.class));

        ChronicDiseaseFollowUpResponse response =
            service.save(device, "REQ-TCD-001", validCombinedRequest());

        assertEquals("FU-EXISTING", response.getFollowUpId());
    }

    @Test
    void saveShouldRejectHeaderRequestIdReusedForAnotherRecord() {
        AiChronicDiseaseFollowUp existing = existing("REQ-TCD-001", "P-OTHER", "R001", "1,2");
        when(followUpMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.save(device, "REQ-TCD-001", validCombinedRequest())
        );

        assertEquals("CHRONIC-FOLLOWUP-CONFLICT", error.getCode());
    }

    private ChronicDiseaseFollowUpRequest validCombinedRequest() {
        ChronicDiseaseFollowUpRequest request = new ChronicDiseaseFollowUpRequest();
        request.setIdPhr(" P001 ");
        request.setIdRecord(" R001 ");
        request.setId("");
        request.setStatus("3");
        request.setSdVisitKind("1,2");
        request.setStature("160");
        request.setAvoirdupois("64");
        request.setAdvAdp("60");
        request.setWaistline("88");
        request.setAdvWaistline("84");
        request.setPressureH("138");
        request.setPressureL("86");
        request.setHeartRate("72");
        request.setIsGlu("1");
        request.setGlu("7.2");
        request.setInputUser("D001");
        request.setIdUser("D001");
        request.setSdHySymptom("2");
        request.setSdDbsSymptom("7");
        request.setSdArteriopalmus("4,5");
        request.setSdPsychicAdj("1");
        request.setSdWehtherSmoke("0");
        request.setSdWhetherDrink("0");
        request.setSportWeek("4");
        request.setAdvSportWeek("5");
        request.setSportMinute("30");
        request.setAdvSportMinute("30");
        request.setSdSalt("6");
        request.setSdAdvSalt("5");
        request.setRice("300");
        request.setTargRice("250");
        request.setFgDrugChange("1");
        request.setSdDrugPro("1");
        request.setSdSideEffects("1");

        TcdVisitDrugRequest blank = new TcdVisitDrugRequest();
        blank.setNaDrug("无ID药品");
        TcdVisitDrugRequest drug = new TcdVisitDrugRequest();
        drug.setIdDrug("MED001");
        drug.setNaDrug("二甲双胍");
        drug.setSdDrugFreq("2");
        drug.setPerDose("0.5");
        drug.setDoseUnit("g");
        drug.setInsulin("2");
        request.setDrugList(Arrays.asList(blank, drug));
        return request;
    }

    private AiChronicDiseaseFollowUp existing(
        String requestId,
        String idPhr,
        String idRecord,
        String sdVisitKind
    ) {
        AiChronicDiseaseFollowUp entity = new AiChronicDiseaseFollowUp();
        entity.setIdFollowUp("FU-EXISTING");
        entity.setRequestId(requestId);
        entity.setIdPhr(idPhr);
        entity.setIdRecord(idRecord);
        entity.setSdVisitKind(sdVisitKind);
        entity.setInsertTime(LocalDateTime.of(2026, 7, 24, 9, 0));
        return entity;
    }
}
