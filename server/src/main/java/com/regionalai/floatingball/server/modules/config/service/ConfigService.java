package com.regionalai.floatingball.server.modules.config.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private static final String DEFAULT_AUDIO_MODEL = "whisper-1";
    private static final String DEFAULT_DASHSCOPE_AUDIO_MODEL = "qwen3-asr-flash";
    private static final String DEFAULT_DASHSCOPE_REALTIME_MODEL = "paraformer-realtime-v2";
    private static final String DEFAULT_FUNASR_REALTIME_MODEL = "funasr-2pass";
    private static final String DEFAULT_SPEECH_PROVIDER = "openai-compatible";
    private static final String ALIYUN_SPEECH_PROVIDER = "aliyun-dashscope";
    private static final String FUNASR_SPEECH_PROVIDER = "funasr-websocket";

    private final AiConfigMapper aiConfigMapper;
    private final AesUtils aesUtils;
    private final ObjectMapper objectMapper;
    private final PromptService promptService;
    private final DataPackageService dataPackageService;
    private final SymptomTemplateService symptomTemplateService;

    public ConfigService(AiConfigMapper aiConfigMapper,
                         AesUtils aesUtils,
                         ObjectMapper objectMapper,
                         PromptService promptService,
                         DataPackageService dataPackageService,
                         SymptomTemplateService symptomTemplateService) {
        this.aiConfigMapper = aiConfigMapper;
        this.aesUtils = aesUtils;
        this.objectMapper = objectMapper;
        this.promptService = promptService;
        this.dataPackageService = dataPackageService;
        this.symptomTemplateService = symptomTemplateService;
    }

    public BootstrapVO buildBootstrap(AiDevice device) {
        ResolvedAiConfig resolved = resolveByDevice(device);
        BootstrapVO vo = new BootstrapVO();

        BootstrapVO.LlmConfig llm = new BootstrapVO.LlmConfig();
        llm.setModel(resolved.getModel());
        llm.setFastModel(resolved.getFastModel());
        llm.setEnableThinking(Boolean.TRUE.equals(resolved.getEnableThinking()));
        llm.setAudioModel(resolved.getAudioModel());
        vo.setLlm(llm);

        BootstrapVO.SpeechConfig speech = new BootstrapVO.SpeechConfig();
        speech.setProvider(resolved.getSpeechProvider());
        speech.setModel(resolved.getSpeechModel());
        vo.setSpeech(speech);

        BootstrapVO.KnowledgeBaseConfig knowledgeBase = new BootstrapVO.KnowledgeBaseConfig();
        knowledgeBase.setEnabled(Boolean.TRUE.equals(resolved.getKnowledgeBaseEnabled()));
        vo.setKnowledgeBase(knowledgeBase);

        BootstrapVO.PmphaiConfig pmphai = new BootstrapVO.PmphaiConfig();
        pmphai.setEnabled(Boolean.TRUE.equals(resolved.getPmphaiEnabled()));
        vo.setPmphai(pmphai);

        BootstrapVO.ReviewerConfig reviewer = new BootstrapVO.ReviewerConfig();
        reviewer.setEnabled(Boolean.TRUE.equals(resolved.getReviewerEnabled()));
        reviewer.setModel(resolved.getReviewerModel());
        reviewer.setCheckExaminationEnabled(!Boolean.FALSE.equals(resolved.getReviewerCheckExaminationEnabled()));
        vo.setReviewer(reviewer);

        vo.setFeatures(resolved.getFeatures() == null ? Collections.<String, Boolean>emptyMap() : resolved.getFeatures());
        vo.setPromptVersion(promptService.latestVisibleVersion(device.getIdOrg(), device.getIdRegion()));
        vo.setTemplateVersion(symptomTemplateService.latestVisibleVersion(device.getIdOrg(), device.getIdRegion()));
        vo.setDataPackageVersion(dataPackageService.latestVisibleVersion("mapping", device.getIdOrg(), device.getIdRegion()));
        return vo;
    }

    public ResolvedAiConfig resolveByDevice(AiDevice device) {
        AiConfig config = resolveVisibleConfig(device.getIdOrg(), device.getIdRegion());
        if (config == null) {
            log.warn("no active ai config found. orgId={}, regionId={}", device.getIdOrg(), device.getIdRegion());
            throw new BusinessException("未找到有效 AI 配置");
        }
        log.info("ai config resolved. orgId={}, regionId={}, configId={}, model={}", device.getIdOrg(), device.getIdRegion(), config.getIdConfig(), config.getModelName());
        return toResolved(config);
    }

    public PageResponse<AiConfigView> list(long current, long size, String keyword) {
        Page<AiConfig> page = new Page<AiConfig>(current, size);
        LambdaQueryWrapper<AiConfig> wrapper = new LambdaQueryWrapper<AiConfig>()
            .eq(AiConfig::getFgActive, "1")
            .orderByDesc(AiConfig::getUpdateTime);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(q -> q.like(AiConfig::getNaConfig, keyword).or().like(AiConfig::getCdConfig, keyword));
        }
        Page<AiConfig> result = aiConfigMapper.selectPage(page, wrapper);
        List<AiConfigView> views = result.getRecords().stream().map(this::toView).collect(Collectors.toList());
        return new PageResponse<AiConfigView>(result.getCurrent(), result.getSize(), result.getTotal(), views);
    }

    public AiConfigView save(AiConfigSaveRequest request) {
        validateSaveRequest(request);
        AiConfig config = new AiConfig();
        mergeSaveRequest(config, request, null);
        config.setFgActive("1");
        if (!StringUtils.hasText(config.getSdStatus())) {
            config.setSdStatus("1");
        }
        aiConfigMapper.insert(config);
        log.info("ai config saved. configId={}, naConfig={}", config.getIdConfig(), config.getNaConfig());
        return toView(config);
    }

    public AiConfigView update(String idConfig, AiConfigSaveRequest request) {
        AiConfig existing = aiConfigMapper.selectById(idConfig);
        if (existing == null) {
            throw new BusinessException("配置不存在");
        }
        validateSaveRequest(request);
        mergeSaveRequest(existing, request, existing);
        aiConfigMapper.updateById(existing);
        log.info("ai config updated. configId={}, naConfig={}", idConfig, existing.getNaConfig());
        return toView(existing);
    }

    public void invalidate(String idConfig) {
        AiConfig config = aiConfigMapper.selectById(idConfig);
        if (config == null) {
            throw new BusinessException("配置不存在");
        }
        config.setFgActive("0");
        aiConfigMapper.updateById(config);
        log.info("ai config invalidated. configId={}", idConfig);
    }

    private void mergeSaveRequest(AiConfig target, AiConfigSaveRequest request, AiConfig existing) {
        target.setCdConfig(request.getCdConfig());
        target.setNaConfig(request.getNaConfig());
        target.setProvider(request.getProvider());
        target.setApiBaseUrl(request.getApiBaseUrl());
        target.setModelName(request.getModelName());
        target.setFastModelName(request.getFastModelName());
        target.setEnableThinking(Boolean.TRUE.equals(request.getEnableThinking()) ? "1" : "0");
        target.setAudioBaseUrl(request.getAudioBaseUrl());
        String speechProvider = normalizeSpeechProvider(request.getSpeechProvider());
        String audioModel = resolveAudioModel(speechProvider, request.getAudioModel());
        target.setAudioModel(audioModel);
        target.setSpeechProvider(speechProvider);
        target.setSpeechRealtimeUrl(normalizeOptionalUrl(request.getSpeechRealtimeUrl()));
        target.setSpeechModel(resolveSpeechModel(speechProvider, request.getSpeechModel(), audioModel));
        target.setKnowledgeBaseEnabled(Boolean.TRUE.equals(request.getKnowledgeBaseEnabled()) ? "1" : "0");
        target.setKnowledgeBaseBaseUrl(request.getKnowledgeBaseBaseUrl());
        target.setPmphaiEnabled(Boolean.TRUE.equals(request.getPmphaiEnabled()) ? "1" : "0");
        target.setPmphaiBaseUrl(request.getPmphaiBaseUrl());
        target.setReviewerEnabled(Boolean.TRUE.equals(request.getReviewerEnabled()) ? "1" : "0");
        target.setReviewerBaseUrl(request.getReviewerBaseUrl());
        target.setReviewerModel(request.getReviewerModel());
        target.setReviewerCheckExaminationEnabled(Boolean.FALSE.equals(request.getReviewerCheckExaminationEnabled()) ? "0" : "1");
        target.setFeaturesJson(request.getFeaturesJson());
        target.setIdOrg(request.getIdOrg());
        target.setIdRegion(request.getIdRegion());
        target.setSdStatus(request.getSdStatus());
        if (StringUtils.hasText(request.getApiKey())) {
            target.setApiKeyEncrypted(aesUtils.encrypt(request.getApiKey()));
        } else if (existing != null) {
            target.setApiKeyEncrypted(existing.getApiKeyEncrypted());
        }
        if (StringUtils.hasText(request.getAudioApiKey())) {
            target.setAudioApiKeyEncrypted(aesUtils.encrypt(request.getAudioApiKey()));
        } else if (existing != null) {
            target.setAudioApiKeyEncrypted(existing.getAudioApiKeyEncrypted());
        }
        if (StringUtils.hasText(request.getReviewerApiKey())) {
            target.setReviewerApiKeyEncrypted(aesUtils.encrypt(request.getReviewerApiKey()));
        } else if (existing != null) {
            target.setReviewerApiKeyEncrypted(existing.getReviewerApiKeyEncrypted());
        }
        if (StringUtils.hasText(request.getPmphaiAppKey())) {
            target.setPmphaiAppKeyEncrypted(aesUtils.encrypt(request.getPmphaiAppKey()));
        } else if (existing != null) {
            target.setPmphaiAppKeyEncrypted(existing.getPmphaiAppKeyEncrypted());
        }
        if (StringUtils.hasText(request.getPmphaiAppSecret())) {
            target.setPmphaiAppSecretEncrypted(aesUtils.encrypt(request.getPmphaiAppSecret()));
        } else if (existing != null) {
            target.setPmphaiAppSecretEncrypted(existing.getPmphaiAppSecretEncrypted());
        }
    }

    private void validateSaveRequest(AiConfigSaveRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        if (!StringUtils.hasText(request.getNaConfig())) {
            throw new BusinessException("配置名称不能为空");
        }
        if (!StringUtils.hasText(request.getApiBaseUrl())) {
            throw new BusinessException("AI 服务地址不能为空");
        }
        if (!StringUtils.hasText(request.getModelName())) {
            throw new BusinessException("模型名称不能为空");
        }
        if (StringUtils.hasText(request.getFeaturesJson())) {
            try {
                objectMapper.readValue(request.getFeaturesJson(), new TypeReference<Map<String, Boolean>>() {});
            } catch (IOException ex) {
                throw new BusinessException("featuresJson 必须是合法 JSON");
            }
        }
        if (StringUtils.hasText(request.getSpeechProvider()) && normalizeSpeechProviderOrNull(request.getSpeechProvider()) == null) {
            throw new BusinessException("语音服务提供方必须是 openai-compatible、aliyun-dashscope 或 funasr-websocket");
        }
        String speechProvider = normalizeSpeechProvider(request.getSpeechProvider());
        if (FUNASR_SPEECH_PROVIDER.equals(speechProvider) && !StringUtils.hasText(request.getSpeechRealtimeUrl())) {
            throw new BusinessException("FunASR 实时识别地址不能为空");
        }
        if (ALIYUN_SPEECH_PROVIDER.equals(speechProvider)
            && isUnsupportedDashScopeRealtimeModel(request.getSpeechModel())) {
            throw new BusinessException("qwen3-asr-flash-realtime 使用不同的实时语音协议，当前仅支持 DashScope run-task 协议模型");
        }
        if (StringUtils.hasText(request.getSpeechRealtimeUrl())) {
            validateWebSocketUrl(request.getSpeechRealtimeUrl());
        }
    }

    private AiConfig resolveVisibleConfig(String orgId, String regionId) {
        List<AiConfig> configs = aiConfigMapper.selectList(new LambdaQueryWrapper<AiConfig>()
            .eq(AiConfig::getFgActive, "1")
            .eq(AiConfig::getSdStatus, "1")
            .and(q -> q.eq(StringUtils.hasText(orgId), AiConfig::getIdOrg, orgId)
                .or()
                .isNull(AiConfig::getIdOrg).eq(StringUtils.hasText(regionId), AiConfig::getIdRegion, regionId)
                .or()
                .isNull(AiConfig::getIdOrg).isNull(AiConfig::getIdRegion))
            .orderByDesc(AiConfig::getUpdateTime));
        if (configs.isEmpty()) {
            return null;
        }
        configs.sort((left, right) -> Integer.compare(score(right, orgId, regionId), score(left, orgId, regionId)));
        return configs.get(0);
    }

    private int score(AiConfig config, String orgId, String regionId) {
        if (StringUtils.hasText(orgId) && orgId.equals(config.getIdOrg())) {
            return 3;
        }
        if (StringUtils.hasText(regionId) && regionId.equals(config.getIdRegion())) {
            return 2;
        }
        return 1;
    }

    private ResolvedAiConfig toResolved(AiConfig config) {
        ResolvedAiConfig resolved = new ResolvedAiConfig();
        resolved.setBaseUrl(trimRightSlash(config.getApiBaseUrl()));
        String apiKey = aesUtils.decrypt(config.getApiKeyEncrypted());
        String audioApiKey = aesUtils.decrypt(config.getAudioApiKeyEncrypted());
        resolved.setApiKey(apiKey);
        resolved.setAudioApiKey(StringUtils.hasText(audioApiKey) ? audioApiKey : apiKey);
        resolved.setModel(config.getModelName());
        resolved.setFastModel(StringUtils.hasText(config.getFastModelName()) ? config.getFastModelName() : config.getModelName());
        resolved.setEnableThinking("1".equals(config.getEnableThinking()));
        resolved.setAudioBaseUrl(StringUtils.hasText(config.getAudioBaseUrl()) ? trimRightSlash(config.getAudioBaseUrl()) : trimRightSlash(config.getApiBaseUrl()));
        String speechProvider = normalizeSpeechProvider(config.getSpeechProvider());
        String audioModel = resolveAudioModel(speechProvider, config.getAudioModel());
        resolved.setAudioModel(audioModel);
        resolved.setSpeechProvider(speechProvider);
        resolved.setSpeechRealtimeUrl(normalizeOptionalUrl(config.getSpeechRealtimeUrl()));
        resolved.setSpeechModel(resolveSpeechModel(speechProvider, config.getSpeechModel(), audioModel));
        resolved.setKnowledgeBaseEnabled("1".equals(config.getKnowledgeBaseEnabled()));
        resolved.setKnowledgeBaseBaseUrl(config.getKnowledgeBaseBaseUrl());
        resolved.setPmphaiEnabled("1".equals(config.getPmphaiEnabled()));
        resolved.setPmphaiBaseUrl(StringUtils.hasText(config.getPmphaiBaseUrl()) ? trimRightSlash(config.getPmphaiBaseUrl()) : null);
        resolved.setPmphaiAppKey(aesUtils.decrypt(config.getPmphaiAppKeyEncrypted()));
        resolved.setPmphaiAppSecret(aesUtils.decrypt(config.getPmphaiAppSecretEncrypted()));
        resolved.setReviewerEnabled("1".equals(config.getReviewerEnabled()));
        resolved.setReviewerBaseUrl(StringUtils.hasText(config.getReviewerBaseUrl()) ? trimRightSlash(config.getReviewerBaseUrl()) : null);
        resolved.setReviewerApiKey(aesUtils.decrypt(config.getReviewerApiKeyEncrypted()));
        resolved.setReviewerModel(config.getReviewerModel());
        resolved.setReviewerCheckExaminationEnabled(!"0".equals(config.getReviewerCheckExaminationEnabled()));
        resolved.setFeatures(parseFeatures(config.getFeaturesJson()));
        return resolved;
    }

    private Map<String, Boolean> parseFeatures(String featuresJson) {
        if (!StringUtils.hasText(featuresJson)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(featuresJson, new TypeReference<Map<String, Boolean>>() {});
        } catch (IOException ex) {
            throw new BusinessException("features_json 解析失败");
        }
    }

    private AiConfigView toView(AiConfig config) {
        AiConfigView view = new AiConfigView();
        view.setIdConfig(config.getIdConfig());
        view.setCdConfig(config.getCdConfig());
        view.setNaConfig(config.getNaConfig());
        view.setProvider(config.getProvider());
        view.setApiBaseUrl(config.getApiBaseUrl());
        view.setApiKeyMasked(MaskingUtils.maskSecret(aesUtils.decrypt(config.getApiKeyEncrypted())));
        view.setModelName(config.getModelName());
        view.setFastModelName(config.getFastModelName());
        view.setEnableThinking("1".equals(config.getEnableThinking()));
        view.setAudioApiKeyMasked(MaskingUtils.maskSecret(aesUtils.decrypt(config.getAudioApiKeyEncrypted())));
        view.setAudioBaseUrl(config.getAudioBaseUrl());
        String speechProvider = normalizeSpeechProvider(config.getSpeechProvider());
        String audioModel = resolveAudioModel(speechProvider, config.getAudioModel());
        view.setAudioModel(audioModel);
        view.setSpeechProvider(speechProvider);
        view.setSpeechRealtimeUrl(config.getSpeechRealtimeUrl());
        view.setSpeechModel(resolveSpeechModel(speechProvider, config.getSpeechModel(), audioModel));
        view.setKnowledgeBaseEnabled(config.getKnowledgeBaseEnabled());
        view.setKnowledgeBaseBaseUrl(config.getKnowledgeBaseBaseUrl());
        view.setPmphaiEnabled(config.getPmphaiEnabled());
        view.setPmphaiBaseUrl(config.getPmphaiBaseUrl());
        view.setPmphaiAppKeyMasked(MaskingUtils.maskSecret(aesUtils.decrypt(config.getPmphaiAppKeyEncrypted())));
        view.setPmphaiAppSecretMasked(MaskingUtils.maskSecret(aesUtils.decrypt(config.getPmphaiAppSecretEncrypted())));
        view.setReviewerEnabled(config.getReviewerEnabled());
        view.setReviewerBaseUrl(config.getReviewerBaseUrl());
        view.setReviewerApiKeyMasked(MaskingUtils.maskSecret(aesUtils.decrypt(config.getReviewerApiKeyEncrypted())));
        view.setReviewerModel(config.getReviewerModel());
        view.setReviewerCheckExaminationEnabled(!"0".equals(config.getReviewerCheckExaminationEnabled()));
        view.setFeaturesJson(config.getFeaturesJson());
        view.setIdOrg(config.getIdOrg());
        view.setIdRegion(config.getIdRegion());
        view.setSdStatus(config.getSdStatus());
        return view;
    }

    private String trimRightSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.replaceAll("/+$", "");
    }

    private String normalizeOptionalUrl(String value) {
        return StringUtils.hasText(value) ? trimRightSlash(value.trim()) : null;
    }

    private void validateWebSocketUrl(String value) {
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!("ws".equals(scheme) || "wss".equals(scheme)) || !StringUtils.hasText(uri.getHost())) {
                throw new BusinessException("实时语音地址必须是有效的 ws:// 或 wss:// 地址");
            }
        } catch (URISyntaxException ex) {
            throw new BusinessException("实时语音地址必须是有效的 ws:// 或 wss:// 地址");
        }
    }

    private String resolveAudioModel(String speechProvider, String value) {
        String model = StringUtils.hasText(value) ? value.trim() : null;
        if (ALIYUN_SPEECH_PROVIDER.equals(speechProvider)) {
            if (!StringUtils.hasText(model) || DEFAULT_AUDIO_MODEL.equalsIgnoreCase(model)
                || model.toLowerCase().startsWith("paraformer")) {
                return DEFAULT_DASHSCOPE_AUDIO_MODEL;
            }
            return model;
        }
        return StringUtils.hasText(model) ? model : DEFAULT_AUDIO_MODEL;
    }

    private String resolveSpeechModel(String speechProvider, String speechModel, String audioModel) {
        String model = StringUtils.hasText(speechModel) ? speechModel.trim() : null;
        if (StringUtils.hasText(model)) {
            return model;
        }
        if (ALIYUN_SPEECH_PROVIDER.equals(speechProvider)) {
            return DEFAULT_DASHSCOPE_REALTIME_MODEL;
        }
        if (FUNASR_SPEECH_PROVIDER.equals(speechProvider)) {
            return DEFAULT_FUNASR_REALTIME_MODEL;
        }
        return resolveAudioModel(speechProvider, audioModel);
    }

    private boolean isUnsupportedDashScopeRealtimeModel(String model) {
        return StringUtils.hasText(model)
            && model.trim().toLowerCase().startsWith("qwen3-asr-flash-realtime");
    }

    private String normalizeSpeechProvider(String value) {
        String normalized = normalizeSpeechProviderOrNull(value);
        return normalized == null ? DEFAULT_SPEECH_PROVIDER : normalized;
    }

    private String normalizeSpeechProviderOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if ("openai-compatible".equals(normalized) || "openai".equals(normalized) || "whisper".equals(normalized)) {
            return DEFAULT_SPEECH_PROVIDER;
        }
        if (ALIYUN_SPEECH_PROVIDER.equals(normalized) || "aliyun".equals(normalized) || "dashscope".equals(normalized)) {
            return ALIYUN_SPEECH_PROVIDER;
        }
        if (FUNASR_SPEECH_PROVIDER.equals(normalized) || "funasr".equals(normalized)) {
            return FUNASR_SPEECH_PROVIDER;
        }
        return null;
    }
}
