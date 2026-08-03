package com.regionalai.floatingball.server.modules.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.AesUtils;
import com.regionalai.floatingball.server.common.util.MaskingUtils;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigSaveRequest;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigView;
import com.regionalai.floatingball.server.modules.config.dto.BootstrapVO;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.entity.AiConfig;
import com.regionalai.floatingball.server.modules.config.mapper.AiConfigMapper;
import com.regionalai.floatingball.server.modules.datapackage.service.DataPackageService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.prompt.service.PromptService;
import com.regionalai.floatingball.server.modules.symptom.service.SymptomTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    private static final String AES_KEY = "1234567890abcdef";

    @Mock
    private AiConfigMapper aiConfigMapper;

    @Mock
    private PromptService promptService;

    @Mock
    private DataPackageService dataPackageService;

    @Mock
    private SymptomTemplateService symptomTemplateService;

    private AesUtils aesUtils;
    private ConfigService configService;

    @BeforeEach
    void setUp() {
        aesUtils = new AesUtils(AES_KEY);
        configService = new ConfigService(
            aiConfigMapper,
            aesUtils,
            new ObjectMapper(),
            promptService,
            dataPackageService,
            symptomTemplateService
        );
    }

    @Test
    void resolveByDeviceShouldPreferOrgScopedConfig() {
        AiConfig global = buildConfig(null, null, "https://global.example.com/", "global-key", "global-model");
        AiConfig region = buildConfig(null, "REG001", "https://region.example.com/", "region-key", "region-model");
        AiConfig otherOrgInRegion = buildConfig("ORG002", "REG001", "https://other-org.example.com/", "other-key", "other-model");
        AiConfig org = buildConfig("ORG001", null, "https://org.example.com/", "org-key", "org-model");
        org.setFeaturesJson("{\"voice\":true}");

        when(aiConfigMapper.selectList(any())).thenReturn(Arrays.asList(global, region, otherOrgInRegion, org));

        AiDevice device = new AiDevice();
        device.setIdOrg("ORG001");
        device.setIdRegion("REG001");

        ResolvedAiConfig resolved = configService.resolveByDevice(device);

        assertEquals("https://org.example.com", resolved.getBaseUrl());
        assertEquals("org-key", resolved.getApiKey());
        assertEquals("org-model", resolved.getModel());
        assertEquals("org-model", resolved.getFastModel());
        assertFalse(Boolean.TRUE.equals(resolved.getEnableThinking()));
        assertEquals("org-key", resolved.getAudioApiKey());
        assertEquals("https://org.example.com", resolved.getAudioBaseUrl());
        assertEquals("whisper-1", resolved.getAudioModel());
        assertEquals("openai-compatible", resolved.getSpeechProvider());
        assertEquals(null, resolved.getSpeechRealtimeUrl());
        assertEquals("whisper-1", resolved.getSpeechModel());
        assertTrue(Boolean.TRUE.equals(resolved.getReviewerCheckExaminationEnabled()));
        assertTrue(resolved.getFeatures().get("voice"));
    }

    @Test
    void buildBootstrapShouldUseResolvedConfigAndVisibleVersions() {
        AiConfig config = buildConfig("ORG001", "REG001", "https://llm.example.com/", "secret-key", "deepseek-chat");
        config.setFastModelName("deepseek-chat-lite");
        config.setEnableThinking("1");
        config.setAudioBaseUrl(null);
        config.setAudioModel("whisper-1");
        config.setSpeechProvider("aliyun");
        config.setSpeechModel("paraformer-realtime-v2");
        config.setKnowledgeBaseEnabled("1");
        config.setKnowledgeBaseBaseUrl("https://kb.example.com");
        config.setPmphaiEnabled("0");
        config.setReviewerEnabled("1");
        config.setReviewerModel("reviewer-v1");
        config.setReviewerCheckExaminationEnabled("0");
        config.setFeaturesJson("{\"voice\":true,\"knowledge\":false}");

        when(aiConfigMapper.selectList(any())).thenReturn(Arrays.asList(config));
        when(promptService.latestVisibleVersion("ORG001", "REG001")).thenReturn("2026.04.20.1");
        when(symptomTemplateService.latestVisibleVersion("ORG001", "REG001")).thenReturn("tpl-2");
        when(dataPackageService.latestVisibleVersion("mapping", "ORG001", "REG001")).thenReturn("map-3");

        AiDevice device = new AiDevice();
        device.setIdOrg("ORG001");
        device.setIdRegion("REG001");

        BootstrapVO bootstrap = configService.buildBootstrap(device);

        assertEquals("deepseek-chat-lite", bootstrap.getLlm().getFastModel());
        assertTrue(Boolean.TRUE.equals(bootstrap.getLlm().getEnableThinking()));
        assertEquals("qwen3-asr-flash", bootstrap.getLlm().getAudioModel());
        assertEquals("aliyun-dashscope", bootstrap.getSpeech().getProvider());
        assertEquals("paraformer-realtime-v2", bootstrap.getSpeech().getModel());
        assertTrue(bootstrap.getKnowledgeBase().getEnabled());
        assertFalse(bootstrap.getPmphai().getEnabled());
        assertTrue(bootstrap.getReviewer().getEnabled());
        assertEquals("reviewer-v1", bootstrap.getReviewer().getModel());
        assertFalse(Boolean.TRUE.equals(bootstrap.getReviewer().getCheckExaminationEnabled()));
        assertTrue(bootstrap.getFeatures().get("voice"));
        assertEquals("2026.04.20.1", bootstrap.getPromptVersion());
        assertEquals("tpl-2", bootstrap.getTemplateVersion());
        assertEquals("map-3", bootstrap.getDataPackageVersion());
    }

    @Test
    void resolveByDeviceShouldKeepCustomDashScopeRunTaskModel() {
        AiConfig config = buildConfig("ORG001", "REG001", "https://llm.example.com/", "secret-key", "deepseek-chat");
        config.setSpeechProvider("dashscope");
        config.setAudioModel("qwen3-asr-flash");
        config.setSpeechModel("hospital-realtime-asr-v1");

        when(aiConfigMapper.selectList(any())).thenReturn(Arrays.asList(config));

        AiDevice device = new AiDevice();
        device.setIdOrg("ORG001");
        device.setIdRegion("REG001");

        ResolvedAiConfig resolved = configService.resolveByDevice(device);

        assertEquals("aliyun-dashscope", resolved.getSpeechProvider());
        assertEquals("qwen3-asr-flash", resolved.getAudioModel());
        assertEquals("hospital-realtime-asr-v1", resolved.getSpeechModel());
    }

    @Test
    void saveShouldPersistCustomDashScopeRunTaskModelWithoutSilentFallback() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setNaConfig("阿里云语音配置");
        request.setApiBaseUrl("https://llm.example.com");
        request.setModelName("deepseek-chat");
        request.setSpeechProvider("aliyun-dashscope");
        request.setSpeechModel("hospital-realtime-asr-v1");

        AiConfigView view = configService.save(request);

        org.mockito.ArgumentCaptor<AiConfig> captor = org.mockito.ArgumentCaptor.forClass(AiConfig.class);
        verify(aiConfigMapper).insert(captor.capture());
        assertEquals("hospital-realtime-asr-v1", captor.getValue().getSpeechModel());
        assertEquals("hospital-realtime-asr-v1", view.getSpeechModel());
    }

    @Test
    void saveShouldRejectDashScopeRealtimeSessionModel() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setNaConfig("阿里云语音配置");
        request.setApiBaseUrl("https://llm.example.com");
        request.setModelName("deepseek-chat");
        request.setSpeechProvider("aliyun-dashscope");
        request.setSpeechModel("qwen3-asr-flash-realtime");

        BusinessException ex = assertThrows(BusinessException.class, () -> configService.save(request));

        assertEquals("qwen3-asr-flash-realtime 使用不同的实时语音协议，当前仅支持 DashScope run-task 协议模型", ex.getMessage());
        verify(aiConfigMapper, never()).insert(any(AiConfig.class));
    }

    @Test
    void updateShouldRetainExistingEncryptedApiKeyWhenRequestOmitsIt() {
        AiConfig existing = buildConfig("ORG001", "REG001", "https://llm.example.com", "persisted-key", "deepseek-chat");
        existing.setIdConfig("CFG001");
        existing.setAudioApiKeyEncrypted(aesUtils.encrypt("persisted-speech-key"));

        when(aiConfigMapper.selectById("CFG001")).thenReturn(existing);

        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setCdConfig("default");
        request.setNaConfig("默认配置");
        request.setProvider("openai-compatible");
        request.setApiBaseUrl("https://new.example.com/");
        request.setModelName("deepseek-v2");
        request.setFeaturesJson("{\"voice\":true}");
        request.setIdOrg("ORG001");
        request.setIdRegion("REG001");
        request.setSdStatus("1");

        String encryptedBefore = existing.getApiKeyEncrypted();
        String encryptedAudioBefore = existing.getAudioApiKeyEncrypted();
        String maskedExpected = MaskingUtils.maskSecret("persisted-key");
        String maskedAudioExpected = MaskingUtils.maskSecret("persisted-speech-key");

        AiConfigView view = configService.update("CFG001", request);

        assertEquals(encryptedBefore, existing.getApiKeyEncrypted());
        assertEquals(encryptedAudioBefore, existing.getAudioApiKeyEncrypted());
        assertEquals("https://new.example.com/", existing.getApiBaseUrl());
        assertEquals("deepseek-v2", existing.getModelName());
        assertEquals("whisper-1", existing.getAudioModel());
        assertEquals("openai-compatible", existing.getSpeechProvider());
        assertEquals("whisper-1", existing.getSpeechModel());
        verify(aiConfigMapper).updateById(existing);
        assertEquals(maskedExpected, view.getApiKeyMasked());
        assertEquals(maskedAudioExpected, view.getAudioApiKeyMasked());
    }

    @Test
    void saveShouldRejectUnknownSpeechProvider() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setNaConfig("配置");
        request.setApiBaseUrl("https://llm.example.com");
        request.setModelName("deepseek-chat");
        request.setSpeechProvider("unknown-provider");

        BusinessException ex = assertThrows(BusinessException.class, () -> configService.save(request));

        assertEquals("语音服务提供方必须是 openai-compatible、aliyun-dashscope 或 funasr-websocket", ex.getMessage());
        verify(aiConfigMapper, never()).insert(any(AiConfig.class));
    }

    @Test
    void saveShouldPersistFunAsrRealtimeConfiguration() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setNaConfig("FunASR 配置");
        request.setApiBaseUrl("https://llm.example.com");
        request.setModelName("deepseek-chat");
        request.setAudioBaseUrl("https://speech-fallback.example.com/v1");
        request.setSpeechProvider("funasr");
        request.setSpeechRealtimeUrl("ws://10.35.34.83:18111/");

        AiConfigView view = configService.save(request);

        org.mockito.ArgumentCaptor<AiConfig> captor = org.mockito.ArgumentCaptor.forClass(AiConfig.class);
        verify(aiConfigMapper).insert(captor.capture());
        AiConfig saved = captor.getValue();
        assertEquals("funasr-websocket", saved.getSpeechProvider());
        assertEquals("ws://10.35.34.83:18111", saved.getSpeechRealtimeUrl());
        assertEquals("funasr-2pass", saved.getSpeechModel());
        assertEquals("whisper-1", saved.getAudioModel());
        assertEquals("ws://10.35.34.83:18111", view.getSpeechRealtimeUrl());
    }

    @Test
    void saveShouldRequireFunAsrRealtimeUrl() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setNaConfig("FunASR 配置");
        request.setApiBaseUrl("https://llm.example.com");
        request.setModelName("deepseek-chat");
        request.setSpeechProvider("funasr-websocket");

        BusinessException ex = assertThrows(BusinessException.class, () -> configService.save(request));

        assertEquals("FunASR 实时识别地址不能为空", ex.getMessage());
        verify(aiConfigMapper, never()).insert(any(AiConfig.class));
    }

    @Test
    void saveShouldRejectInvalidRealtimeUrlProtocol() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setNaConfig("FunASR 配置");
        request.setApiBaseUrl("https://llm.example.com");
        request.setModelName("deepseek-chat");
        request.setSpeechProvider("funasr-websocket");
        request.setSpeechRealtimeUrl("http://10.35.34.83:18111");

        BusinessException ex = assertThrows(BusinessException.class, () -> configService.save(request));

        assertEquals("实时语音地址必须是有效的 ws:// 或 wss:// 地址", ex.getMessage());
        verify(aiConfigMapper, never()).insert(any(AiConfig.class));
    }

    @Test
    void saveShouldRejectInvalidFeaturesJson() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setNaConfig("配置");
        request.setApiBaseUrl("https://llm.example.com");
        request.setModelName("deepseek-chat");
        request.setFeaturesJson("{bad-json}");

        BusinessException ex = assertThrows(BusinessException.class, () -> configService.save(request));

        assertEquals("featuresJson 必须是合法 JSON", ex.getMessage());
        verify(aiConfigMapper, never()).insert(any(AiConfig.class));
    }

    @Test
    void resolveByDeviceShouldRejectWhenNoVisibleConfigExists() {
        when(aiConfigMapper.selectList(any())).thenReturn(Arrays.asList());

        AiDevice device = new AiDevice();
        device.setIdOrg("ORG001");
        device.setIdRegion("REG001");

        BusinessException ex = assertThrows(BusinessException.class, () -> configService.resolveByDevice(device));

        assertEquals("未找到有效 AI 配置", ex.getMessage());
    }

    @Test
    void saveShouldEncryptAllProvidedSecretsAndNormalizeDashScopeAliases() {
        AiConfigSaveRequest request = new AiConfigSaveRequest();
        request.setCdConfig("dashscope");
        request.setNaConfig("阿里云语音配置");
        request.setProvider("openai-compatible");
        request.setApiBaseUrl("https://llm.example.com/");
        request.setApiKey("main-key");
        request.setModelName("deepseek-chat");
        request.setAudioApiKey("audio-key");
        request.setSpeechProvider("dashscope");
        request.setAudioModel("paraformer-realtime-v2");
        request.setSpeechModel(null);
        request.setReviewerApiKey("reviewer-key");
        request.setPmphaiAppKey("pmphai-key");
        request.setPmphaiAppSecret("pmphai-secret");
        request.setReviewerCheckExaminationEnabled(false);

        AiConfigView view = configService.save(request);

        org.mockito.ArgumentCaptor<AiConfig> captor = org.mockito.ArgumentCaptor.forClass(AiConfig.class);
        verify(aiConfigMapper).insert(captor.capture());
        AiConfig saved = captor.getValue();
        assertEquals("aliyun-dashscope", saved.getSpeechProvider());
        assertEquals("qwen3-asr-flash", saved.getAudioModel());
        assertEquals("paraformer-realtime-v2", saved.getSpeechModel());
        assertEquals("0", saved.getReviewerCheckExaminationEnabled());
        assertEquals("main-key", aesUtils.decrypt(saved.getApiKeyEncrypted()));
        assertEquals("audio-key", aesUtils.decrypt(saved.getAudioApiKeyEncrypted()));
        assertEquals("reviewer-key", aesUtils.decrypt(saved.getReviewerApiKeyEncrypted()));
        assertEquals("pmphai-key", aesUtils.decrypt(saved.getPmphaiAppKeyEncrypted()));
        assertEquals("pmphai-secret", aesUtils.decrypt(saved.getPmphaiAppSecretEncrypted()));
        assertEquals("aliyun-dashscope", view.getSpeechProvider());
        assertEquals("qwen3-asr-flash", view.getAudioModel());
    }

    @Test
    void invalidateShouldSoftDeleteExistingConfig() {
        AiConfig existing = buildConfig("ORG001", "REG001", "https://llm.example.com", "key", "deepseek-chat");
        existing.setIdConfig("CFG001");
        existing.setFgActive("1");
        when(aiConfigMapper.selectById("CFG001")).thenReturn(existing);

        configService.invalidate("CFG001");

        assertEquals("0", existing.getFgActive());
        verify(aiConfigMapper).updateById(existing);
    }

    private AiConfig buildConfig(String idOrg, String idRegion, String apiBaseUrl, String apiKey, String modelName) {
        AiConfig config = new AiConfig();
        config.setIdOrg(idOrg);
        config.setIdRegion(idRegion);
        config.setFgActive("1");
        config.setSdStatus("1");
        config.setCdConfig("default");
        config.setNaConfig("默认配置");
        config.setProvider("openai-compatible");
        config.setApiBaseUrl(apiBaseUrl);
        config.setApiKeyEncrypted(aesUtils.encrypt(apiKey));
        config.setModelName(modelName);
        config.setEnableThinking("0");
        config.setKnowledgeBaseEnabled("0");
        config.setPmphaiEnabled("0");
        config.setReviewerEnabled("0");
        config.setReviewerCheckExaminationEnabled("1");
        return config;
    }
}
