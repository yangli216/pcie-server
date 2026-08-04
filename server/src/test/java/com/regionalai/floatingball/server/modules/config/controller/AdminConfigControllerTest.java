package com.regionalai.floatingball.server.modules.config.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigSaveRequest;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigTestResult;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigView;
import com.regionalai.floatingball.server.modules.config.service.ConfigConnectionTestService;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminConfigControllerTest {

    @Mock
    private ConfigService configService;

    @Mock
    private ConfigConnectionTestService configConnectionTestService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminConfigController(configService, configConnectionTestService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void listShouldReturnWrappedConfigs() throws Exception {
        AiConfigView config = configView("CFG001");
        PageResponse<AiConfigView> page = new PageResponse<AiConfigView>(1, 10, 1, Collections.singletonList(config));
        when(configService.list(1, 10, "默认")).thenReturn(page);

        mockMvc.perform(get("/admin/api/configs")
                .param("current", "1")
                .param("size", "10")
                .param("keyword", "默认")
                .header("X-Request-Id", "RID-config-list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-config-list"))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].idConfig").value("CFG001"))
            .andExpect(jsonPath("$.data.records[0].apiKeyMasked").value("sk****key"));
    }

    @Test
    void saveAndUpdateShouldPassBodyToService() throws Exception {
        AiConfigSaveRequest request = request();
        when(configService.save(any(AiConfigSaveRequest.class))).thenReturn(configView("CFG001"));
        when(configService.update(eq("CFG001"), any(AiConfigSaveRequest.class))).thenReturn(configView("CFG001"));

        mockMvc.perform(post("/admin/api/configs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-config-save")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-config-save"))
            .andExpect(jsonPath("$.data.idConfig").value("CFG001"));

        mockMvc.perform(put("/admin/api/configs/CFG001")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-config-update")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-config-update"))
            .andExpect(jsonPath("$.data.modelName").value("deepseek-chat"));

        verify(configService).save(any(AiConfigSaveRequest.class));
        verify(configService).update(eq("CFG001"), any(AiConfigSaveRequest.class));
    }

    @Test
    void testShouldReturnConnectionResult() throws Exception {
        AiConfigSaveRequest request = request();
        when(configConnectionTestService.testMainModel(any(AiConfigSaveRequest.class)))
            .thenReturn(new AiConfigTestResult(true, "连接成功", "https://llm.example.com", "deepseek-chat"));

        mockMvc.perform(post("/admin/api/configs/test")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-config-test")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-config-test"))
            .andExpect(jsonPath("$.data.success").value(true))
            .andExpect(jsonPath("$.data.message").value("连接成功"))
            .andExpect(jsonPath("$.data.modelName").value("deepseek-chat"));
    }

    @Test
    void speechTestsShouldReturnRealtimeAndBatchResults() throws Exception {
        AiConfigSaveRequest request = request();
        request.setSpeechProvider("aliyun-dashscope");
        request.setSpeechModel("qwen-audio-3.0-asr-flash-streaming");
        request.setAudioModel("qwen3-asr-flash");
        when(configConnectionTestService.testRealtimeSpeech(any(AiConfigSaveRequest.class)))
            .thenReturn(new AiConfigTestResult(true, "实时识别模型可用", "wss://speech.example.com", "qwen-audio-3.0-asr-flash-streaming"));
        when(configConnectionTestService.testBatchSpeech(any(AiConfigSaveRequest.class)))
            .thenReturn(new AiConfigTestResult(true, "批量转写模型可用", "https://speech.example.com/v1", "qwen3-asr-flash"));

        mockMvc.perform(post("/admin/api/configs/test/speech/realtime")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-speech-realtime-test")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value("RID-speech-realtime-test"))
            .andExpect(jsonPath("$.data.success").value(true))
            .andExpect(jsonPath("$.data.modelName").value("qwen-audio-3.0-asr-flash-streaming"));

        mockMvc.perform(post("/admin/api/configs/test/speech/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-speech-batch-test")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value("RID-speech-batch-test"))
            .andExpect(jsonPath("$.data.success").value(true))
            .andExpect(jsonPath("$.data.modelName").value("qwen3-asr-flash"));
    }

    @Test
    void invalidateShouldDelegateAndReturnBusinessErrors() throws Exception {
        mockMvc.perform(delete("/admin/api/configs/CFG001")
                .header("X-Request-Id", "RID-config-delete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-config-delete"));

        verify(configService).invalidate("CFG001");

        doThrow(new BusinessException("配置不存在")).when(configService).invalidate("MISSING");

        mockMvc.perform(delete("/admin/api/configs/MISSING")
                .header("X-Request-Id", "RID-config-missing"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BIZ-001"))
            .andExpect(jsonPath("$.requestId").value("RID-config-missing"))
            .andExpect(jsonPath("$.message").value("配置不存在"));
    }

    private AiConfigSaveRequest request() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setCdConfig("default");
        request.setNaConfig("默认配置");
        request.setProvider("openai-compatible");
        request.setApiBaseUrl("https://llm.example.com");
        request.setApiKey("sk-test-key");
        request.setModelName("deepseek-chat");
        request.setIdOrg("ORG001");
        request.setIdRegion("REG001");
        request.setSdStatus("1");
        return request;
    }

    private AiConfigView configView(String idConfig) {
        AiConfigView config = new AiConfigView();
        config.setIdConfig(idConfig);
        config.setCdConfig("default");
        config.setNaConfig("默认配置");
        config.setProvider("openai-compatible");
        config.setApiBaseUrl("https://llm.example.com");
        config.setApiKeyMasked("sk****key");
        config.setModelName("deepseek-chat");
        config.setSpeechProvider("openai-compatible");
        config.setSpeechModel("whisper-1");
        config.setSdStatus("1");
        return config;
    }
}
