package com.regionalai.floatingball.server.modules.config.service;

import com.regionalai.floatingball.server.common.util.AesUtils;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.ai.service.AiProxyService;
import com.regionalai.floatingball.server.modules.ai.websocket.RealtimeSpeechAvailabilityService;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigSaveRequest;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigTestResult;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.entity.AiConfig;
import com.regionalai.floatingball.server.modules.config.mapper.AiConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigConnectionTestServiceTest {

    private static final String AES_KEY = "1234567890abcdef";

    @Mock
    private AiConfigMapper aiConfigMapper;

    @Mock
    private AiProxyService aiProxyService;

    @Mock
    private RealtimeSpeechAvailabilityService realtimeSpeechAvailabilityService;

    private AesUtils aesUtils;
    private ConfigConnectionTestService service;

    @BeforeEach
    void setUp() {
        aesUtils = new AesUtils(AES_KEY);
        service = new ConfigConnectionTestService(
            aiConfigMapper,
            aesUtils,
            aiProxyService,
            realtimeSpeechAvailabilityService
        );
    }

    @Test
    void realtimeTestShouldUseDraftModelAndMainKeyFallback() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setSpeechProvider("dashscope");
        request.setSpeechModel("qwen-audio-3.0-asr-flash-streaming");
        request.setApiKey("main-key");
        when(realtimeSpeechAvailabilityService.testConnection(org.mockito.ArgumentMatchers.any(ResolvedAiConfig.class)))
            .thenReturn("实时识别模型可用");

        AiConfigTestResult result = service.testRealtimeSpeech(request);

        ArgumentCaptor<ResolvedAiConfig> captor = ArgumentCaptor.forClass(ResolvedAiConfig.class);
        verify(realtimeSpeechAvailabilityService).testConnection(captor.capture());
        assertEquals("aliyun-dashscope", captor.getValue().getSpeechProvider());
        assertEquals("qwen-audio-3.0-asr-flash-streaming", captor.getValue().getSpeechModel());
        assertEquals("main-key", captor.getValue().getAudioApiKey());
        assertEquals("实时识别模型可用", result.getMessage());
    }

    @Test
    void batchTestShouldReuseSavedAudioKey() {
        AiConfig existing = new AiConfig();
        existing.setAudioApiKeyEncrypted(aesUtils.encrypt("saved-audio-key"));
        existing.setApiKeyEncrypted(aesUtils.encrypt("saved-main-key"));
        when(aiConfigMapper.selectById("CFG001")).thenReturn(existing);
        when(aiProxyService.testSpeechConnection(
            "aliyun-dashscope",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "saved-audio-key",
            "qwen3-asr-flash"
        )).thenReturn("批量转写模型可用");

        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setIdConfig("CFG001");
        request.setSpeechProvider("aliyun-dashscope");
        request.setAudioBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        request.setAudioModel("qwen3-asr-flash");

        AiConfigTestResult result = service.testBatchSpeech(request);

        assertEquals("qwen3-asr-flash", result.getModelName());
        assertEquals("批量转写模型可用", result.getMessage());
        verify(aiProxyService).testSpeechConnection(
            "aliyun-dashscope",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "saved-audio-key",
            "qwen3-asr-flash"
        );
    }

    @Test
    void batchTestShouldRejectRealtimeOnlyModelBeforeCallingUpstream() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setSpeechProvider("aliyun-dashscope");
        request.setAudioModel("qwen-audio-3.0-asr-flash-streaming");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.testBatchSpeech(request));

        assertEquals("该模型仅支持实时 WebSocket，请将它填写到“实时识别模型”；批量转写模型请使用 qwen3-asr-flash", ex.getMessage());
    }
}
