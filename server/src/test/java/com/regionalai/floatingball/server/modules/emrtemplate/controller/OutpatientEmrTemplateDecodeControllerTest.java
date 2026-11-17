package com.regionalai.floatingball.server.modules.emrtemplate.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.emrtemplate.service.OutpatientEmrTemplateDecodeService;
import com.regionalai.floatingball.server.modules.emrtemplate.service.OutpatientEmrTemplateSnapshotService;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import com.regionalai.floatingball.server.modules.security.service.SecurityRejectionLogService;
import com.regionalai.floatingball.server.security.DeviceAuthFilter;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import com.regionalai.floatingball.server.security.RequestSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OutpatientEmrTemplateDecodeControllerTest {
    private static final String PATH = "/v1/client/outpatient-emr/templates/decode";
    private static final String HTML = " \r\n<section data-article=\"主诉\">中文咳嗽🙂</section>\n ";
    private static final String DEFINITION = "[{\"ID\":\"chief\",\"NAME\":\"主诉\",\"ARTICLE\":\"主诉\",\"eles\":[]}]";
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private KeyPair keyPair;
    private OutpatientEmrTemplateSnapshotService snapshots;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256); keyPair = generator.generateKeyPair();
        AiDevice device = new AiDevice();
        device.setIdDevice("SIM-DEVICE"); device.setIdOrg("SIM-ORG"); device.setClientVersion("1.0.0");
        device.setDevicePublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        DeviceService devices = mock(DeviceService.class);
        when(devices.findActiveByToken("synthetic-token")).thenReturn(device);
        snapshots = mock(OutpatientEmrTemplateSnapshotService.class);
        DeviceAuthFilter filter = new DeviceAuthFilter(devices, mock(ReleaseService.class),
            new RequestSignatureVerifier(), mock(SecurityRejectionLogService.class), mapper);
        mvc = MockMvcBuilders.standaloneSetup(new ClientOutpatientEmrTemplateController(
                snapshots, new OutpatientEmrTemplateDecodeService(mapper)))
            .setControllerAdvice(new GlobalExceptionHandler()).addFilters(filter).build();
    }

    @Test
    void signedDecodeReturnsExactSourceWithoutRegisteringAnySnapshot() throws Exception {
        mvc.perform(signed(body().toString()).header("X-Request-Id", "SIM-DECODE-REQUEST"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("SIM-DECODE-REQUEST"))
            .andExpect(jsonPath("$.data.schemaVersion").value("outpatient-emr-template-decoded.v1"))
            .andExpect(jsonPath("$.data.templateHtml").value(HTML))
            .andExpect(jsonPath("$.data.templateDefinition").value(DEFINITION));
        verifyNoInteractions(snapshots);
        assertNull(DeviceContextHolder.get());
    }

    @Test
    void rejectsMissingTokenMissingSignatureAndTamperedBody() throws Exception {
        String json = body().toString();
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-401"));
        mvc.perform(post(PATH).header("Authorization", "Bearer synthetic-token")
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIG-401"));
        mvc.perform(signed(json).content(json + " "))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIG-401"));
        verifyNoInteractions(snapshots);
        assertNull(DeviceContextHolder.get());
    }

    @Test
    void rejectsAReplayedSignedRequest() throws Exception {
        MockHttpServletRequestBuilder request = signed(body().toString());
        mvc.perform(request).andExpect(status().isOk());
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIG-401"));
        verifyNoInteractions(snapshots);
    }

    @Test
    void rejectsUnknownMissingAndNonStringDtoFields() throws Exception {
        ObjectNode unknown = body(); unknown.put("legacySource", "sensitive-source");
        mvc.perform(signed(unknown.toString())).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BIZ-001"));
        ObjectNode missing = body(); missing.remove("templateDefinition");
        mvc.perform(signed(missing.toString())).andExpect(status().isBadRequest());
        ObjectNode number = body(); number.put("templateHtml", 42);
        mvc.perform(signed(number.toString())).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION-002"));
        ObjectNode array = body(); array.putArray("templateDefinition").add("unexpected");
        mvc.perform(signed(array.toString())).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION-002"));
        verifyNoInteractions(snapshots);
    }

    private ObjectNode body() {
        return mapper.createObjectNode().put("schemaVersion", "outpatient-emr-template-decode.v1")
            .put("templateHtml", OutpatientEmrTemplateDecodeService.HTML_PREFIX + base64(HTML))
            .put("templateDefinition", OutpatientEmrTemplateDecodeService.DEFINITION_PREFIX + base64(DEFINITION));
    }

    private MockHttpServletRequestBuilder signed(String body) throws Exception {
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format("%02x", value));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(("POST\n" + PATH + "\n" + timestamp + "\n" + nonce + "\n" + hash + "\n")
            .getBytes(StandardCharsets.UTF_8));
        return post(PATH).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8))
            .header("Authorization", "Bearer synthetic-token").header("X-Client-Version", "1.0.0")
            .header("X-Timestamp", timestamp).header("X-Nonce", nonce).header("X-Body-SHA256", hash.toString())
            .header("X-Signature", Base64.getEncoder().encodeToString(signature.sign()));
    }

    private String base64(String source) {
        return Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }
}
