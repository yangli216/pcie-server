package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import com.regionalai.floatingball.server.modules.security.service.SecurityRejectionLogService;
import com.regionalai.floatingball.server.security.DeviceAuthFilter;
import com.regionalai.floatingball.server.security.RequestSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClientChronicExaminationControllerTest {
    private static final String BASE = "/v1/client/chronic-disease/examination-recommendations/query";
    private ChronicExaminationService service;
    private MockMvc mvc;
    private KeyPair keys;

    @BeforeEach
    void setup() throws Exception {
        service = mock(ChronicExaminationService.class);
        DeviceService devices = mock(DeviceService.class);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        keys = generator.generateKeyPair();
        AiDevice device = new AiDevice();
        device.setIdDevice("synthetic");
        device.setDevicePublicKey(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        when(devices.findActiveByToken("test-token")).thenReturn(device);
        DeviceAuthFilter filter = new DeviceAuthFilter(devices, mock(ReleaseService.class), new RequestSignatureVerifier(),
            mock(SecurityRejectionLogService.class), new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new ClientChronicExaminationController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).addFilters(filter).build();
    }

    private MockHttpServletRequestBuilder signed(String method, String path, String body) throws Exception {
        String timestamp = Long.toString(System.currentTimeMillis()), nonce = UUID.randomUUID().toString();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keys.getPrivate());
        signature.update((method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + hex + "\n").getBytes(StandardCharsets.UTF_8));
        return ("GET".equals(method) ? get(path) : post(path)).contentType("application/json")
            .content(body).header("Authorization", "Bearer test-token").header("X-Request-Id", "test-request")
            .header("X-Timestamp", timestamp).header("X-Nonce", nonce).header("X-Body-SHA256", hex.toString())
            .header("X-Signature", Base64.getEncoder().encodeToString(signature.sign()));
    }

    private static final String BODY = "{\"patientId\":\"p\",\"visitId\":\"v\",\"idCard\":\"110101200001010011\"}";
    @Test
    void requiresDeviceTokenAndRealSignatureAndRejectsTampering() throws Exception {
        mvc.perform(post(BASE).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).header("Authorization", "Bearer test-token").contentType("application/json").content(BODY))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIG-401"));
        mvc.perform(signed("POST", BASE, BODY).content(BODY.replace("110101200001010011", "110101200001010022"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test
    void validatesIdentityBeforeDispatch() throws Exception {
        mvc.perform(signed("POST", BASE, "{\"patientId\":\"p\",\"idCard\":\"110101200001010011\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(signed("POST", BASE, BODY.replace("\"p\"", "\" \"")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test
    void returnsNeutralResponseAndExplicitRegionalScope() throws Exception {
        ExaminationRecommendationResponse value = new ExaminationRecommendationResponse();
        value.setPatientId("p"); value.setVisitId("v");
        value.setRegionalHistory(new ExaminationRecommendationResponse.RegionalHistory());
        when(service.query(any())).thenReturn(value);
        mvc.perform(signed("POST", BASE, BODY)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.patientId").value("p"))
            .andExpect(jsonPath("$.data.regionalHistory.status").value("not_connected"))
            .andExpect(jsonPath("$.data.recommendations").isEmpty())
            .andExpect(jsonPath("$.data.idCard").doesNotExist());
    }
    @Test
    void failureIsNotAnEmptySuccess() throws Exception {
        when(service.query(any())).thenThrow(new ServiceUnavailableException("CHRONIC-EXAM-UNAVAILABLE", "推荐暂不可用"));
        mvc.perform(signed("POST", BASE, BODY)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CHRONIC-EXAM-UNAVAILABLE"));
    }
}
