package com.regionalai.floatingball.server.modules.config.service;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.AesUtils;
import com.regionalai.floatingball.server.modules.ai.service.AiProxyService;
import com.regionalai.floatingball.server.modules.ai.websocket.RealtimeSpeechAvailabilityService;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigSaveRequest;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigTestResult;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.entity.AiConfig;
import com.regionalai.floatingball.server.modules.config.mapper.AiConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConfigConnectionTestService {

    private static final Logger log = LoggerFactory.getLogger(ConfigConnectionTestService.class);
    private static final String DEFAULT_SPEECH_PROVIDER = "openai-compatible";
    private static final String ALIYUN_SPEECH_PROVIDER = "aliyun-dashscope";
    private static final String FUNASR_SPEECH_PROVIDER = "funasr-websocket";
    private static final String DEFAULT_AUDIO_MODEL = "whisper-1";
    private static final String DEFAULT_DASHSCOPE_AUDIO_MODEL = "qwen3-asr-flash";
    private static final String DEFAULT_DASHSCOPE_REALTIME_MODEL = "qwen-audio-3.0-asr-flash-streaming";
    private static final String DEFAULT_FUNASR_REALTIME_MODEL = "funasr-2pass";
    private static final String DEFAULT_DASHSCOPE_REALTIME_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";

    private final AiConfigMapper aiConfigMapper;
    private final AesUtils aesUtils;
    private final AiProxyService aiProxyService;
    private final RealtimeSpeechAvailabilityService realtimeSpeechAvailabilityService;

    public ConfigConnectionTestService(AiConfigMapper aiConfigMapper,
                                       AesUtils aesUtils,
                                       AiProxyService aiProxyService,
                                       RealtimeSpeechAvailabilityService realtimeSpeechAvailabilityService) {
        this.aiConfigMapper = aiConfigMapper;
        this.aesUtils = aesUtils;
        this.aiProxyService = aiProxyService;
        this.realtimeSpeechAvailabilityService = realtimeSpeechAvailabilityService;
    }

    public AiConfigTestResult testMainModel(AiConfigSaveRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        String baseUrl = request.getApiBaseUrl();
        String modelName = request.getModelName();
        String apiKey = resolveApiKey(request);
        String message = aiProxyService.testChatConnection(baseUrl, apiKey, modelName, Boolean.TRUE.equals(request.getEnableThinking()));
        log.info("ai config connection test. baseUrl={}, model={}, success=true", baseUrl, modelName);
        return new AiConfigTestResult(true, message, baseUrl, modelName);
    }

    public AiConfigTestResult testRealtimeSpeech(AiConfigSaveRequest request) {
        requireRequest(request);
        String provider = normalizeSpeechProvider(request.getSpeechProvider());
        ResolvedAiConfig config = new ResolvedAiConfig();
        config.setSpeechProvider(provider);
        config.setSpeechRealtimeUrl(normalizeOptional(request.getSpeechRealtimeUrl()));
        config.setSpeechModel(resolveRealtimeModel(provider, request.getSpeechModel()));
        if (ALIYUN_SPEECH_PROVIDER.equals(provider)) {
            config.setAudioApiKey(resolveAudioApiKey(request));
        }

        String message = realtimeSpeechAvailabilityService.testConnection(config);
        String endpoint = StringUtils.hasText(config.getSpeechRealtimeUrl())
            ? config.getSpeechRealtimeUrl()
            : DEFAULT_DASHSCOPE_REALTIME_URL;
        log.info("realtime speech config test. provider={}, endpoint={}, model={}, success=true",
            provider, endpoint, config.getSpeechModel());
        return new AiConfigTestResult(true, message, endpoint, config.getSpeechModel());
    }

    public AiConfigTestResult testBatchSpeech(AiConfigSaveRequest request) {
        requireRequest(request);
        String provider = normalizeSpeechProvider(request.getSpeechProvider());
        String baseUrl = StringUtils.hasText(request.getAudioBaseUrl())
            ? request.getAudioBaseUrl().trim()
            : normalizeOptional(request.getApiBaseUrl());
        String model = resolveBatchModel(provider, request.getAudioModel());
        if (ALIYUN_SPEECH_PROVIDER.equals(provider) && isDashScopeRealtimeOnlyModel(model)) {
            throw new BusinessException("该模型仅支持实时 WebSocket，请将它填写到“实时识别模型”；批量转写模型请使用 qwen3-asr-flash");
        }
        String apiKey = resolveAudioApiKey(request);
        String message = aiProxyService.testSpeechConnection(provider, baseUrl, apiKey, model);
        log.info("batch speech config test. provider={}, baseUrl={}, model={}, success=true", provider, baseUrl, model);
        return new AiConfigTestResult(true, message, baseUrl, model);
    }

    private void requireRequest(AiConfigSaveRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
    }

    private String resolveAudioApiKey(AiConfigSaveRequest request) {
        if (StringUtils.hasText(request.getAudioApiKey())) {
            return request.getAudioApiKey().trim();
        }
        if (StringUtils.hasText(request.getApiKey())) {
            return request.getApiKey().trim();
        }
        AiConfig existing = resolveExisting(request);
        if (existing != null) {
            String audioApiKey = aesUtils.decrypt(existing.getAudioApiKeyEncrypted());
            if (StringUtils.hasText(audioApiKey)) {
                return audioApiKey;
            }
            String apiKey = aesUtils.decrypt(existing.getApiKeyEncrypted());
            if (StringUtils.hasText(apiKey)) {
                return apiKey;
            }
        }
        throw new BusinessException("请先填写语音服务密钥");
    }

    private AiConfig resolveExisting(AiConfigSaveRequest request) {
        if (!StringUtils.hasText(request.getIdConfig())) {
            return null;
        }
        AiConfig existing = aiConfigMapper.selectById(request.getIdConfig());
        if (existing == null) {
            throw new BusinessException("配置不存在，无法复用原有密钥");
        }
        return existing;
    }

    private String resolveBatchModel(String provider, String value) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        return ALIYUN_SPEECH_PROVIDER.equals(provider) ? DEFAULT_DASHSCOPE_AUDIO_MODEL : DEFAULT_AUDIO_MODEL;
    }

    private String resolveRealtimeModel(String provider, String value) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        if (ALIYUN_SPEECH_PROVIDER.equals(provider)) {
            return DEFAULT_DASHSCOPE_REALTIME_MODEL;
        }
        if (FUNASR_SPEECH_PROVIDER.equals(provider)) {
            return DEFAULT_FUNASR_REALTIME_MODEL;
        }
        return DEFAULT_AUDIO_MODEL;
    }

    private String normalizeSpeechProvider(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toLowerCase() : DEFAULT_SPEECH_PROVIDER;
        if ("aliyun".equals(normalized) || "dashscope".equals(normalized) || ALIYUN_SPEECH_PROVIDER.equals(normalized)) {
            return ALIYUN_SPEECH_PROVIDER;
        }
        if ("funasr".equals(normalized) || FUNASR_SPEECH_PROVIDER.equals(normalized)) {
            return FUNASR_SPEECH_PROVIDER;
        }
        return DEFAULT_SPEECH_PROVIDER;
    }

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim().replaceAll("/+$", "") : null;
    }

    private boolean isDashScopeRealtimeOnlyModel(String model) {
        return StringUtils.hasText(model)
            && model.trim().toLowerCase().startsWith("qwen-audio-3.0-asr-flash-streaming");
    }

    private String resolveApiKey(AiConfigSaveRequest request) {
        if (StringUtils.hasText(request.getApiKey())) {
            return request.getApiKey().trim();
        }
        if (!StringUtils.hasText(request.getIdConfig())) {
            throw new BusinessException("请先填写 API Key");
        }
        AiConfig existing = aiConfigMapper.selectById(request.getIdConfig());
        if (existing == null) {
            throw new BusinessException("配置不存在，无法复用原有 API Key");
        }
        String apiKey = aesUtils.decrypt(existing.getApiKeyEncrypted());
        if (!StringUtils.hasText(apiKey)) {
            throw new BusinessException("当前配置未保存有效 API Key，请重新填写后再测试");
        }
        return apiKey;
    }
}
