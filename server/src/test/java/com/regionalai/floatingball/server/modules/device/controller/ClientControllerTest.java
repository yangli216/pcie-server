package com.regionalai.floatingball.server.modules.device.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.audit.dto.AuditBatchRequest;
import com.regionalai.floatingball.server.modules.audit.service.AuditService;
import com.regionalai.floatingball.server.modules.config.dto.BootstrapVO;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.datapackage.dto.MappingDeltaVO;
import com.regionalai.floatingball.server.modules.datapackage.service.DataPackageService;
import com.regionalai.floatingball.server.modules.device.dto.RegisterDeviceRequest;
import com.regionalai.floatingball.server.modules.device.dto.RegisterDeviceResponse;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.prompt.dto.PromptDeltaVO;
import com.regionalai.floatingball.server.modules.prompt.service.PromptService;
import com.regionalai.floatingball.server.modules.symptom.service.SymptomTemplateService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ClientControllerTest {

    @Mock
    private DeviceService deviceService;

    @Mock
    private ConfigService configService;

    @Mock
    private PromptService promptService;

    @Mock
    private DataPackageService dataPackageService;

    @Mock
    private SymptomTemplateService symptomTemplateService;

    @Mock
    private AuditService auditService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new ClientController(
                deviceService,
                configService,
                promptService,
                dataPackageService,
                symptomTemplateService,
                auditService
            ))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        DeviceContextHolder.clear();
    }

    @Test
    void registerShouldValidateRequiredFieldsAndWrapServiceResponse() throws Exception {
        RegisterDeviceRequest request = new RegisterDeviceRequest();
        request.setCdDevice("clinic-room-1");
        request.setCdOrg("ORG-CODE");
        request.setNaDevice("诊室一终端");
        request.setClientVersion("1.2.3");
        request.setPublicKey("public-key");

        when(deviceService.register(any(RegisterDeviceRequest.class), eq("10.0.0.10"), eq("token-proof")))
            .thenReturn(new RegisterDeviceResponse("DEV001", "token-1", 30, true));

        mockMvc.perform(post("/v1/client/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-client-register")
                .header("X-Forwarded-For", "10.0.0.10, 10.0.0.1")
                .header("Authorization", "Bearer token-proof")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-client-register"))
            .andExpect(jsonPath("$.data.idDevice").value("DEV001"))
            .andExpect(jsonPath("$.data.deviceToken").value("token-1"))
            .andExpect(jsonPath("$.data.heartbeatInterval").value(30))
            .andExpect(jsonPath("$.data.hasPublicKey").value(true));

        verify(deviceService).register(any(RegisterDeviceRequest.class), eq("10.0.0.10"), eq("token-proof"));
    }

    @Test
    void registerShouldRejectMissingPublicKeyBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/v1/client/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-client-register-invalid")
                .content("{\"cdDevice\":\"clinic-room-1\",\"cdOrg\":\"ORG-CODE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION-001"))
            .andExpect(jsonPath("$.requestId").value("RID-client-register-invalid"))
            .andExpect(jsonPath("$.message").value("设备公钥不能为空"));
    }

    @Test
    void bootstrapShouldUseAuthenticatedDeviceContext() throws Exception {
        AiDevice device = device();
        DeviceContextHolder.set(device);

        BootstrapVO bootstrap = new BootstrapVO();
        BootstrapVO.LlmConfig llm = new BootstrapVO.LlmConfig();
        llm.setModel("deepseek-chat");
        bootstrap.setLlm(llm);
        BootstrapVO.KnowledgeBaseConfig knowledgeBase = new BootstrapVO.KnowledgeBaseConfig();
        knowledgeBase.setEnabled(true);
        bootstrap.setKnowledgeBase(knowledgeBase);
        bootstrap.setPromptVersion("prompt-2026");
        when(configService.buildBootstrap(device)).thenReturn(bootstrap);

        mockMvc.perform(get("/v1/client/bootstrap")
                .header("X-Request-Id", "RID-client-bootstrap"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-client-bootstrap"))
            .andExpect(jsonPath("$.data.llm.baseUrl").doesNotExist())
            .andExpect(jsonPath("$.data.llm.audioBaseUrl").doesNotExist())
            .andExpect(jsonPath("$.data.llm.model").value("deepseek-chat"))
            .andExpect(jsonPath("$.data.knowledgeBase.enabled").value(true))
            .andExpect(jsonPath("$.data.knowledgeBase.baseUrl").doesNotExist())
            .andExpect(jsonPath("$.data.promptVersion").value("prompt-2026"));

        verify(configService).buildBootstrap(device);
    }

    @Test
    void deltaEndpointsShouldForwardOrgRegionAndVersion() throws Exception {
        AiDevice device = device();
        DeviceContextHolder.set(device);
        PromptDeltaVO promptDelta = new PromptDeltaVO("prompt-v2", java.util.Collections.emptyList());
        MappingDeltaVO mappingDelta = new MappingDeltaVO();
        mappingDelta.setVersion("map-v2");

        when(promptService.getDelta("ORG001", "REG001", "prompt-v1")).thenReturn(promptDelta);
        when(dataPackageService.getMappingDelta("ORG001", "REG001", "map-v1")).thenReturn(mappingDelta);

        mockMvc.perform(get("/v1/client/prompts/delta")
                .param("version", "prompt-v1")
                .header("X-Request-Id", "RID-client-prompt-delta"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value("prompt-v2"));

        mockMvc.perform(get("/v1/client/mappings/delta")
                .param("version", "map-v1")
                .header("X-Request-Id", "RID-client-mapping-delta"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value("map-v2"));

        verify(promptService).getDelta(eq("ORG001"), eq("REG001"), eq("prompt-v1"));
        verify(dataPackageService).getMappingDelta(eq("ORG001"), eq("REG001"), eq("map-v1"));
    }

    @Test
    void heartbeatAndAuditShouldUseAuthenticatedDeviceContext() throws Exception {
        AiDevice device = device();
        DeviceContextHolder.set(device);
        when(auditService.saveBatch(eq(device), any(AuditBatchRequest.class))).thenReturn(3);

        mockMvc.perform(post("/v1/client/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-client-heartbeat")
                .header("X-Real-IP", "10.0.0.11")
                .header("X-Client-Version", "1.3.8")
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.status").value("ok"));

        mockMvc.perform(post("/v1/client/audit/events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-client-audit")
                .content("{\"events\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-client-audit"))
            .andExpect(jsonPath("$.data.accepted").value(3));

        verify(deviceService).heartbeat(device, "10.0.0.11", "1.3.8");
        verify(auditService).saveBatch(eq(device), any(AuditBatchRequest.class));
    }

    private AiDevice device() {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");
        device.setIdRegion("REG001");
        return device;
    }
}
