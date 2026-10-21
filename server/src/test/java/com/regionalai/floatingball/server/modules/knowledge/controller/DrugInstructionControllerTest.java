package com.regionalai.floatingball.server.modules.knowledge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.knowledge.dto.*;
import com.regionalai.floatingball.server.modules.knowledge.service.DrugInstructionService;
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

class DrugInstructionControllerTest {
    private static final String BASE = "/v1/knowledge/drug-instructions";
    private DrugInstructionService service;
    private MockMvc mvc;
    private KeyPair keys;

    @BeforeEach
    void setup() throws Exception {
        service = mock(DrugInstructionService.class);
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
        mvc = MockMvcBuilders.standaloneSetup(new DrugInstructionController(service))
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

    @Test
    void bothRoutesRejectMissingTokenOrSignatureBeforeCallingService() throws Exception {
        mvc.perform(post(BASE + "/search").contentType("application/json").content("{\"keyword\":\"药\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-401"));
        mvc.perform(get(BASE + "/id")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/search").header("Authorization", "Bearer test-token"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIG-401"));
        mvc.perform(get(BASE + "/id").header("Authorization", "Bearer test-token"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIG-401"));
        verifyNoInteractions(service);
    }

    @Test
    void signedSearchReturnsCandidatesWithoutSummary() throws Exception {
        DrugInstructionItem item = new DrugInstructionItem("id", "合成药", "", "1g", "合成厂家", "片剂", "");
        when(service.search(any())).thenReturn(new DrugInstructionSearchResponse(1, 20, false, Collections.singletonList(item)));
        mvc.perform(signed("POST", BASE + "/search", "{\"keyword\":\"合成药\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value("test-request"))
            .andExpect(jsonPath("$.data.records[0].productName").value("合成药"))
            .andExpect(jsonPath("$.data.records[0].summary").doesNotExist());
    }

    @Test
    void rejectsTamperedSignedSearchAndInvalidPagination() throws Exception {
        mvc.perform(signed("POST", BASE + "/search", "{\"keyword\":\"药\"}").content("{\"keyword\":\"篡改\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(signed("POST", BASE + "/search", "{\"keyword\":\"  \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(signed("POST", BASE + "/search", "{\"keyword\":\"药\",\"size\":101}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void detailReturnsFullOriginalTextIncludingHtmlAsData() throws Exception {
        String summary = "【用法用量】\r\n" + String.join("", Collections.nCopies(15000, "合成文本")) + "\r\n<script>untrusted</script>";
        when(service.find("id")).thenReturn(Optional.of(new DrugInstructionDetail("id", "合成药", "", "", "", "", "", summary, "")));
        mvc.perform(signed("GET", BASE + "/id", "")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.summary").value(summary));
    }

    @Test
    void distinguishesMissingDrugAndUpstreamFailure() throws Exception {
        when(service.find("missing")).thenReturn(Optional.empty());
        mvc.perform(signed("GET", BASE + "/missing", "")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("DRUG_INSTRUCTION_NOT_FOUND"));
        when(service.find("id")).thenThrow(new ServiceUnavailableException("DRUG_INSTRUCTIONS_UNAVAILABLE", "服务暂不可用"));
        mvc.perform(signed("GET", BASE + "/id", "")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("DRUG_INSTRUCTIONS_UNAVAILABLE"))
            .andExpect(jsonPath("$.requestId").value("test-request"));
    }
}
