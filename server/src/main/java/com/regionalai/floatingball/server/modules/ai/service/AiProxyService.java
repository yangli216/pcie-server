package com.regionalai.floatingball.server.modules.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService.OutboundCall;
import com.regionalai.floatingball.server.modules.ai.dto.ChatRequest;
import com.regionalai.floatingball.server.modules.ai.dto.SpeechRequest;
import com.regionalai.floatingball.server.modules.audit.service.AuditService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class AiProxyService {

    private static final Logger log = LoggerFactory.getLogger(AiProxyService.class);

    private static final String ALIYUN_SPEECH_PROVIDER = "aliyun-dashscope";
    private static final String DEFAULT_OPENAI_AUDIO_MODEL = "whisper-1";
    private static final String DEFAULT_DASHSCOPE_AUDIO_MODEL = "qwen3-asr-flash";

    private final ConfigService configService;
    private final AuditService auditService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final OutboundSecurityService outboundSecurityService;
    private final Executor aiStreamExecutor;

    public AiProxyService(ConfigService configService,
                          AuditService auditService,
                          RestTemplate restTemplate,
                          ObjectMapper objectMapper,
                          OutboundSecurityService outboundSecurityService,
                          @Qualifier("aiStreamExecutor") Executor aiStreamExecutor) {
        this.configService = configService;
        this.auditService = auditService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.outboundSecurityService = outboundSecurityService;
        this.aiStreamExecutor = aiStreamExecutor;
    }

    public String chat(AiDevice device, ChatRequest request) {
        UpstreamChatConfig upstreamConfig = resolveChatConfig(device, request);
        return chat(device, request, upstreamConfig);
    }

    public void validateChatConfig(AiDevice device, ChatRequest request) {
        UpstreamChatConfig upstreamConfig = resolveChatConfig(device, request);
        outboundSecurityService.validateHttpUrl(upstreamConfig.getBaseUrl() + "/chat/completions", "ai-chat");
    }

    public String testChatConnection(String baseUrl, String apiKey, String model, boolean enableThinking) {
        UpstreamChatConfig upstreamConfig = new UpstreamChatConfig(trimRightSlash(baseUrl), apiKey, model, enableThinking);
        Map<String, Object> userMessage = new LinkedHashMap<String, Object>();
        userMessage.put("role", "user");
        userMessage.put("content", "您好，这是一条连通性测试消息，请只回复“测试成功”。");
        return chatOnce(upstreamConfig, Collections.<Map<String, Object>>singletonList(userMessage));
    }

    public String testSpeechConnection(String speechProvider,
                                       String baseUrl,
                                       String apiKey,
                                       String model) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new BusinessException("请先填写批量转写地址");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new BusinessException("请先填写语音服务密钥");
        }
        if (!StringUtils.hasText(model)) {
            throw new BusinessException("请先填写批量转写模型");
        }

        byte[] sourcePcm = new byte[3200];
        byte[] silentWav = wrapPcmAsWav(sourcePcm, 16000, (short) 1, (short) 16);
        PreparedSpeechFile preparedFile = new PreparedSpeechFile(
            sourcePcm,
            silentWav,
            "audio/pcm",
            "audio/wav",
            "speech-model-test.pcm",
            "speech-model-test.wav",
            true
        );

        if (ALIYUN_SPEECH_PROVIDER.equalsIgnoreCase(speechProvider)) {
            testDashScopeSpeechConnection(baseUrl.trim(), apiKey.trim(), model.trim(), preparedFile);
        } else {
            testOpenAiSpeechConnection(baseUrl.trim(), apiKey.trim(), model.trim(), preparedFile);
        }
        return "批量转写模型可用";
    }

    private void testDashScopeSpeechConnection(String baseUrl,
                                                String apiKey,
                                                String model,
                                                PreparedSpeechFile preparedFile) {
        String endpoint = buildDashScopeSpeechEndpoint(baseUrl);
        OutboundCall outboundCall = null;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            outboundCall = outboundSecurityService.acquireHttp(endpoint, "speech-batch-test");
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                outboundCall.getUrl(),
                new HttpEntity<Map<String, Object>>(buildDashScopeSpeechPayload(preparedFile, model), headers),
                JsonNode.class
            );
            if (response.getBody() == null) {
                throw new BusinessException("批量转写模型响应为空");
            }
            outboundCall.success();
        } catch (HttpStatusCodeException ex) {
            markOutboundFailure(outboundCall, ex);
            throw new BusinessException(buildUpstreamErrorMessage("批量转写模型", ex));
        } catch (ResourceAccessException ex) {
            markOutboundFailure(outboundCall, ex);
            throw new BusinessException(buildUpstreamRequestErrorMessage("批量转写模型", ex));
        } catch (RuntimeException ex) {
            markOutboundFailure(outboundCall, ex);
            throw ex;
        }
    }

    private void testOpenAiSpeechConnection(String baseUrl,
                                             String apiKey,
                                             String model,
                                             PreparedSpeechFile preparedFile) {
        String endpoint = buildOpenAiSpeechEndpoint(baseUrl);
        OutboundCall outboundCall = null;
        try {
            MultiValueMap<String, Object> formData = new LinkedMultiValueMap<String, Object>();
            formData.add("file", new ByteArrayResource(preparedFile.audioBytes) {
                @Override
                public String getFilename() {
                    return preparedFile.fileName;
                }
            });
            formData.add("model", model);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(apiKey);
            outboundCall = outboundSecurityService.acquireHttp(endpoint, "speech-batch-test");
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                outboundCall.getUrl(),
                new HttpEntity<MultiValueMap<String, Object>>(formData, headers),
                JsonNode.class
            );
            if (response.getBody() == null) {
                throw new BusinessException("批量转写模型响应为空");
            }
            outboundCall.success();
        } catch (HttpStatusCodeException ex) {
            markOutboundFailure(outboundCall, ex);
            throw new BusinessException(buildUpstreamErrorMessage("批量转写模型", ex));
        } catch (ResourceAccessException ex) {
            markOutboundFailure(outboundCall, ex);
            throw new BusinessException(buildUpstreamRequestErrorMessage("批量转写模型", ex));
        } catch (RuntimeException ex) {
            markOutboundFailure(outboundCall, ex);
            throw ex;
        }
    }

    private String chat(AiDevice device, ChatRequest request, UpstreamChatConfig upstreamConfig) {
        return chatOnce(device, request, upstreamConfig, request.getMessages(), request.getTemperature());
    }

    private String chatOnce(UpstreamChatConfig upstreamConfig,
                            List<Map<String, Object>> messages) {
        return chatOnce(upstreamConfig, messages, null);
    }

    private String chatOnce(UpstreamChatConfig upstreamConfig,
                            List<Map<String, Object>> messages,
                            Double temperature) {
        return chatOnce(null, null, upstreamConfig, messages, temperature);
    }

    private String chatOnce(AiDevice device,
                            ChatRequest request,
                            UpstreamChatConfig upstreamConfig,
                            List<Map<String, Object>> messages,
                            Double temperature) {
        Map<String, Object> payload = buildChatPayload(upstreamConfig.getModel(), messages, Boolean.FALSE, temperature, upstreamConfig.isEnableThinking());
        OutboundCall outboundCall = null;

        try {
            log.info("ai chat request. model={}, baseUrl={}, stream=false", upstreamConfig.getModel(), upstreamConfig.getBaseUrl());

            StringBuilder responseTextBuilder = new StringBuilder();
            String[] rawLogHolder = new String[1];
            outboundCall = outboundSecurityService.acquireHttp(upstreamConfig.getBaseUrl() + "/chat/completions", "ai-chat");
            final OutboundCall activeOutboundCall = outboundCall;

            restTemplate.execute(
                activeOutboundCall.getUrl(),
                HttpMethod.POST,
                requestCallback -> {
                    requestCallback.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    requestCallback.getHeaders().setBearerAuth(upstreamConfig.getApiKey());
                    objectMapper.writeValue(requestCallback.getBody(), payload);
                },
                response -> {
                    String rawBody = readFully(response.getBody());
                    if (rawBody == null || rawBody.trim().isEmpty()) {
                        throw new BusinessException("AI 响应为空");
                    }
                    rawLogHolder[0] = rawBody;

                    MediaType ct = response.getHeaders().getContentType();
                    boolean declaredSse = ct != null && MediaType.TEXT_EVENT_STREAM.includes(ct);
                    boolean hasDataPrefix = rawBody.contains("data:");

                    if (declaredSse && hasDataPrefix) {
                        for (String line : rawBody.split("\n")) {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("data:")) {
                                String data = trimmed.substring(5).trim();
                                if ("[DONE]".equals(data)) {
                                    break;
                                }
                                String content = extractSseContent(data);
                                if (content != null) {
                                    responseTextBuilder.append(content);
                                }
                            }
                        }
                    } else {
                        try {
                            JsonNode jsonNode = objectMapper.readTree(rawBody);
                            JsonNode contentNode = jsonNode.path("choices").path(0).path("message").path("content");
                            String text = contentNode.isMissingNode() ? jsonNode.toString() : contentNode.asText();
                            responseTextBuilder.append(text);
                            rawLogHolder[0] = jsonNode.toString();
                        } catch (IOException e) {
                            throw new RuntimeException("AI 响应 JSON 解析失败: " + e.getMessage(), e);
                        }
                    }
                    return null;
                }
            );
            activeOutboundCall.success();

            String responseText = responseTextBuilder.toString();
            String rawLog = rawLogHolder[0] != null ? rawLogHolder[0] : responseText;

            log.info("ai chat succeeded. model={}, responseLength={}", upstreamConfig.getModel(), responseText.length());
            auditService.saveSystemLog(
                device,
                "ai_proxy",
                "ai",
                "chat",
                buildChatLogPayload(request, upstreamConfig, payload, responseText, null, rawLog),
                true
            );
            return responseText;
        } catch (HttpStatusCodeException ex) {
            markOutboundFailure(outboundCall, ex);
            log.error("ai chat upstream error. model={}, status={}", upstreamConfig.getModel(), ex.getStatusCode());
            auditService.saveSystemLog(
                device,
                "ai_proxy",
                "ai",
                "chat",
                buildChatLogPayload(request, upstreamConfig, payload, null, buildUpstreamErrorMessage("AI", ex), ex.getResponseBodyAsString()),
                false
            );
            throw new BusinessException(buildUpstreamErrorMessage("AI", ex));
        } catch (ResourceAccessException ex) {
            markOutboundFailure(outboundCall, ex);
            log.error("ai chat upstream unreachable. model={}, error={}", upstreamConfig.getModel(), ex.getMessage());
            auditService.saveSystemLog(
                device,
                "ai_proxy",
                "ai",
                "chat",
                buildChatLogPayload(request, upstreamConfig, payload, null, buildUpstreamRequestErrorMessage("AI", ex), null),
                false
            );
            throw new BusinessException(buildUpstreamRequestErrorMessage("AI", ex));
        } catch (BusinessException ex) {
            markOutboundFailure(outboundCall, ex);
            auditService.saveSystemLog(
                device,
                "ai_proxy",
                "ai",
                "chat",
                buildChatLogPayload(request, upstreamConfig, payload, null, ex.getMessage(), null),
                false
            );
            throw ex;
        } catch (RuntimeException ex) {
            markOutboundFailure(outboundCall, ex);
            auditService.saveSystemLog(
                device,
                "ai_proxy",
                "ai",
                "chat",
                buildChatLogPayload(request, upstreamConfig, payload, null, ex.getMessage(), null),
                false
            );
            throw new BusinessException("AI调用失败：" + (StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "未知异常"));
        }
    }

    public SseEmitter chatStream(AiDevice device, ChatRequest request) {
        UpstreamChatConfig upstreamConfig = resolveChatConfig(device, request);
        SseEmitter emitter = new SseEmitter(120000L);
        try {
            aiStreamExecutor.execute(() -> streamChat(device, request, upstreamConfig, emitter));
        } catch (RejectedExecutionException ex) {
            log.warn("ai chat stream rejected by bounded executor. model={}", upstreamConfig.getModel());
            sendErrorFrame(emitter, "AI流式请求过多，请稍后重试");
        }
        return emitter;
    }

    private void streamChat(AiDevice device, ChatRequest request, UpstreamChatConfig upstreamConfig, SseEmitter emitter) {
        log.info("ai chat stream request. model={}, baseUrl={}", upstreamConfig.getModel(), upstreamConfig.getBaseUrl());
        Map<String, Object> payload = buildChatPayload(
            upstreamConfig.getModel(),
            request.getMessages(),
            Boolean.TRUE,
            request.getTemperature(),
            upstreamConfig.isEnableThinking()
        );

        StringBuilder responseTextBuilder = new StringBuilder();
        OutboundCall outboundCall = null;
        try {
            outboundCall = outboundSecurityService.acquireHttp(upstreamConfig.getBaseUrl() + "/chat/completions", "ai-chat-stream");
            final OutboundCall activeOutboundCall = outboundCall;
            restTemplate.execute(
                activeOutboundCall.getUrl(),
                HttpMethod.POST,
                requestCallback -> {
                    requestCallback.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    requestCallback.getHeaders().setAccept(Collections.singletonList(MediaType.TEXT_EVENT_STREAM));
                    requestCallback.getHeaders().setBearerAuth(upstreamConfig.getApiKey());
                    objectMapper.writeValue(requestCallback.getBody(), payload);
                },
                response -> {
                    StreamForwardResult result = forwardSseBody(response.getBody(), emitter, responseTextBuilder);
                    log.info("ai chat stream completed. model={}, responseLength={}", upstreamConfig.getModel(), result.responseText.length());
                    activeOutboundCall.success();
                    auditService.saveSystemLog(
                        device,
                        "ai_proxy",
                        "ai",
                        "chat_stream",
                        buildChatLogPayload(request, upstreamConfig, payload, result.responseText, result.errorMessage, null),
                        !StringUtils.hasText(result.errorMessage)
                    );
                    return null;
                }
            );
        } catch (HttpStatusCodeException ex) {
            markOutboundFailure(outboundCall, ex);
            String errorMessage = buildUpstreamErrorMessage("AI", ex);
            log.error("ai chat stream upstream error. model={}, status={}", upstreamConfig.getModel(), ex.getStatusCode());
            auditService.saveSystemLog(
                device,
                "ai_proxy",
                "ai",
                "chat_stream",
                buildChatLogPayload(request, upstreamConfig, payload, responseTextBuilder.toString(), errorMessage, ex.getResponseBodyAsString()),
                false
            );
            sendErrorFrame(emitter, errorMessage);
        } catch (ResourceAccessException ex) {
            markOutboundFailure(outboundCall, ex);
            String errorMessage = buildUpstreamRequestErrorMessage("AI", ex);
            log.error("ai chat stream upstream unreachable. model={}, error={}", upstreamConfig.getModel(), ex.getMessage());
            auditService.saveSystemLog(
                device,
                "ai_proxy",
                "ai",
                "chat_stream",
                buildChatLogPayload(request, upstreamConfig, payload, responseTextBuilder.toString(), errorMessage, null),
                false
            );
            sendErrorFrame(emitter, errorMessage);
        } catch (BusinessException ex) {
            markOutboundFailure(outboundCall, ex);
            log.warn("ai chat stream business rejected. model={}, error={}", upstreamConfig.getModel(), ex.getMessage());
            auditService.saveSystemLog(
                device,
                "ai_proxy",
                "ai",
                "chat_stream",
                buildChatLogPayload(request, upstreamConfig, payload, responseTextBuilder.toString(), ex.getMessage(), null),
                false
            );
            sendErrorFrame(emitter, ex.getMessage());
        } catch (Exception ex) {
            markOutboundFailure(outboundCall, ex);
            String errorMessage = "AI流式响应封装失败：" + (StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "未知异常");
            log.error("ai chat stream internal error. model={}, error={}", upstreamConfig.getModel(), ex.getMessage());
            auditService.saveSystemLog(
                device,
                "ai_proxy",
                "ai",
                "chat_stream",
                buildChatLogPayload(request, upstreamConfig, payload, responseTextBuilder.toString(), errorMessage, null),
                false
            );
            sendErrorFrame(emitter, errorMessage);
        }
    }

    private StreamForwardResult forwardSseBody(InputStream bodyStream,
                                               SseEmitter emitter,
                                               StringBuilder responseTextBuilder) throws IOException {
        if (bodyStream == null) {
            throw new BusinessException("AI 流式响应为空");
        }

        boolean receivedDone = false;
        String errorMessage = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring(5).trim();
            emitter.send(SseEmitter.event().data(data));
            String content = extractSseContent(data);
            if (content != null) {
                responseTextBuilder.append(content);
            }
            String currentErrorMessage = extractSseErrorMessage(data);
            if (StringUtils.hasText(currentErrorMessage)) {
                errorMessage = currentErrorMessage;
            }
            if ("[DONE]".equals(data)) {
                receivedDone = true;
                break;
            }
        }

        if (!receivedDone) {
            throw new BusinessException("AI 流式响应未正常结束");
        }
        emitter.complete();
        return new StreamForwardResult(responseTextBuilder.toString(), errorMessage);
    }

    private void sendErrorFrame(SseEmitter emitter, String message) {
        try {
            Map<String, Object> errorPayload = new HashMap<String, Object>();
            Map<String, String> error = new HashMap<String, String>();
            error.put("message", message);
            errorPayload.put("error", error);
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(errorPayload)));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (Exception sendError) {
            emitter.completeWithError(sendError);
        }
    }

    public String transcribe(AiDevice device, SpeechRequest request) {
        return proxySpeech(device, request, "transcribe");
    }

    public String realtime(AiDevice device, SpeechRequest request) {
        return proxySpeech(device, request, "realtime");
    }

    private String proxySpeech(AiDevice device, SpeechRequest request, String action) {
        ResolvedAiConfig config = configService.resolveByDevice(device);
        if (!StringUtils.hasText(config.getAudioBaseUrl())) {
            throw new BusinessException("未配置语音服务地址");
        }
        PreparedSpeechFile preparedFile = prepareSpeechFile(request);
        String audioModel = resolveAudioModel(config);
        String audioApiKey = resolveAudioApiKey(config);
        log.info("speech proxy request. action={}, provider={}, model={}", action, config.getSpeechProvider(), audioModel);

        if (isDashScopeSpeech(config)) {
            return proxyDashScopeSpeech(device, request, action, config, preparedFile, audioModel, audioApiKey);
        }

        return proxyOpenAiCompatibleSpeech(device, request, action, config, preparedFile, audioModel, audioApiKey);
    }

    private String proxyOpenAiCompatibleSpeech(AiDevice device,
                                               SpeechRequest request,
                                               String action,
                                               ResolvedAiConfig config,
                                               PreparedSpeechFile preparedFile,
                                               String audioModel,
                                               String audioApiKey) {
        String endpoint = buildOpenAiSpeechEndpoint(config.getAudioBaseUrl());
        OutboundCall outboundCall = null;
        try {
            MultiValueMap<String, Object> formData = new LinkedMultiValueMap<String, Object>();
            formData.add("file", new ByteArrayResource(preparedFile.audioBytes) {
                @Override
                public String getFilename() {
                    return preparedFile.fileName;
                }
            });
            formData.add("model", audioModel);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(audioApiKey);

            outboundCall = outboundSecurityService.acquireHttp(endpoint, "speech-" + action);
            final OutboundCall activeOutboundCall = outboundCall;
            ResponseEntity<JsonNode> responseEntity = restTemplate.postForEntity(
                activeOutboundCall.getUrl(),
                new HttpEntity<MultiValueMap<String, Object>>(formData, headers),
                JsonNode.class
            );
            JsonNode response = responseEntity.getBody();

            if (response == null) {
                throw new BusinessException("语音转写响应为空");
            }

            JsonNode textNode = response.path("text");
            String text = textNode.isMissingNode() ? response.toString() : textNode.asText();
            activeOutboundCall.success();
            auditService.saveSystemLogWithAudioFile(
                device,
                "speech_proxy",
                "speech",
                action,
                buildSpeechLogPayload(request, preparedFile, endpoint, audioModel, text, true, null, response.toString()),
                true,
                preparedFile.audioBytes,
                preparedFile.fileName
            );
            return text;
        } catch (HttpStatusCodeException ex) {
            markOutboundFailure(outboundCall, ex);
            String errorMessage = buildUpstreamErrorMessage("语音服务", ex);
            auditService.saveSystemLogWithAudioFile(
                device,
                "speech_proxy",
                "speech",
                action,
                buildSpeechLogPayload(request, preparedFile, endpoint, audioModel, null, false, errorMessage, ex.getResponseBodyAsString()),
                false,
                preparedFile.audioBytes,
                preparedFile.fileName
            );
            throw new BusinessException(errorMessage);
        } catch (ResourceAccessException ex) {
            markOutboundFailure(outboundCall, ex);
            String errorMessage = buildUpstreamRequestErrorMessage("语音服务", ex);
            auditService.saveSystemLogWithAudioFile(
                device,
                "speech_proxy",
                "speech",
                action,
                buildSpeechLogPayload(request, preparedFile, endpoint, audioModel, null, false, errorMessage, null),
                false,
                preparedFile.audioBytes,
                preparedFile.fileName
            );
            throw new BusinessException(errorMessage);
        } catch (BusinessException ex) {
            markOutboundFailure(outboundCall, ex);
            auditService.saveSystemLogWithAudioFile(
                device,
                "speech_proxy",
                "speech",
                action,
                buildSpeechLogPayload(request, preparedFile, endpoint, audioModel, null, false, ex.getMessage(), null),
                false,
                preparedFile.audioBytes,
                preparedFile.fileName
            );
            throw ex;
        } catch (RuntimeException ex) {
            markOutboundFailure(outboundCall, ex);
            auditService.saveSystemLogWithAudioFile(
                device,
                "speech_proxy",
                "speech",
                action,
                buildSpeechLogPayload(request, preparedFile, endpoint, audioModel, null, false, ex.getMessage(), null),
                false,
                preparedFile.audioBytes,
                preparedFile.fileName
            );
            throw ex;
        }
    }

    private String proxyDashScopeSpeech(AiDevice device,
                                        SpeechRequest request,
                                        String action,
                                        ResolvedAiConfig config,
                                        PreparedSpeechFile preparedFile,
                                        String audioModel,
                                        String audioApiKey) {
        String endpoint = buildDashScopeSpeechEndpoint(config.getAudioBaseUrl());
        Map<String, Object> payload = buildDashScopeSpeechPayload(preparedFile, audioModel);
        OutboundCall outboundCall = null;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(audioApiKey);

            outboundCall = outboundSecurityService.acquireHttp(endpoint, "speech-" + action);
            final OutboundCall activeOutboundCall = outboundCall;
            ResponseEntity<JsonNode> responseEntity = restTemplate.postForEntity(
                activeOutboundCall.getUrl(),
                new HttpEntity<Map<String, Object>>(payload, headers),
                JsonNode.class
            );
            JsonNode response = responseEntity.getBody();

            if (response == null) {
                throw new BusinessException("语音转写响应为空");
            }

            String text = extractDashScopeSpeechText(response);
            activeOutboundCall.success();
            auditService.saveSystemLogWithAudioFile(
                device,
                "speech_proxy",
                "speech",
                action,
                buildSpeechLogPayload(request, preparedFile, endpoint, audioModel, text, true, null, response.toString()),
                true,
                preparedFile.audioBytes,
                preparedFile.fileName
            );
            return text;
        } catch (HttpStatusCodeException ex) {
            markOutboundFailure(outboundCall, ex);
            String errorMessage = buildUpstreamErrorMessage("语音服务", ex);
            auditService.saveSystemLogWithAudioFile(
                device,
                "speech_proxy",
                "speech",
                action,
                buildSpeechLogPayload(request, preparedFile, endpoint, audioModel, null, false, errorMessage, ex.getResponseBodyAsString()),
                false,
                preparedFile.audioBytes,
                preparedFile.fileName
            );
            throw new BusinessException(errorMessage);
        } catch (ResourceAccessException ex) {
            markOutboundFailure(outboundCall, ex);
            String errorMessage = buildUpstreamRequestErrorMessage("语音服务", ex);
            auditService.saveSystemLogWithAudioFile(
                device,
                "speech_proxy",
                "speech",
                action,
                buildSpeechLogPayload(request, preparedFile, endpoint, audioModel, null, false, errorMessage, null),
                false,
                preparedFile.audioBytes,
                preparedFile.fileName
            );
            throw new BusinessException(errorMessage);
        } catch (BusinessException ex) {
            markOutboundFailure(outboundCall, ex);
            auditService.saveSystemLogWithAudioFile(
                device,
                "speech_proxy",
                "speech",
                action,
                buildSpeechLogPayload(request, preparedFile, endpoint, audioModel, null, false, ex.getMessage(), null),
                false,
                preparedFile.audioBytes,
                preparedFile.fileName
            );
            throw ex;
        } catch (RuntimeException ex) {
            markOutboundFailure(outboundCall, ex);
            auditService.saveSystemLogWithAudioFile(
                device,
                "speech_proxy",
                "speech",
                action,
                buildSpeechLogPayload(request, preparedFile, endpoint, audioModel, null, false, ex.getMessage(), null),
                false,
                preparedFile.audioBytes,
                preparedFile.fileName
            );
            throw ex;
        }
    }

    private void markOutboundFailure(OutboundCall outboundCall, Throwable error) {
        if (outboundCall != null) {
            outboundCall.failure(error);
        }
    }

    private String buildUpstreamErrorMessage(String serviceName, HttpStatusCodeException ex) {
        String upstreamMessage = extractUpstreamErrorMessage(ex.getResponseBodyAsString());
        String statusPart = ex.getRawStatusCode() > 0 ? "（" + ex.getRawStatusCode() + "）" : "";
        if (StringUtils.hasText(upstreamMessage)) {
            return serviceName + "调用失败" + statusPart + "：" + upstreamMessage;
        }
        return serviceName + "调用失败" + statusPart + "：" + ex.getStatusText();
    }

    private String buildUpstreamRequestErrorMessage(String serviceName, ResourceAccessException ex) {
        String reason = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "网络连接异常";
        return serviceName + "调用失败：网络连接异常，" + reason;
    }

    private String extractUpstreamErrorMessage(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errorNode = root.path("error");
            if (errorNode.isObject()) {
                JsonNode messageNode = errorNode.path("message");
                if (messageNode.isTextual() && StringUtils.hasText(messageNode.asText())) {
                    return messageNode.asText();
                }
            }
            JsonNode messageNode = root.path("message");
            if (messageNode.isTextual() && StringUtils.hasText(messageNode.asText())) {
                return messageNode.asText();
            }
            return responseBody;
        } catch (Exception ex) {
            log.debug("upstream error message extraction failed. error={}", ex.getMessage());
            return responseBody;
        }
    }

    private Map<String, Object> buildChatPayload(String model,
                                                 List<Map<String, Object>> messages,
                                                 Boolean stream,
                                                 Double temperature,
                                                 boolean enableThinking) {
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", stream);
        payload.put("enable_thinking", enableThinking);
        if (!Boolean.TRUE.equals(stream)) {
            payload.put("max_tokens", 4096);
        }
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        return payload;
    }

    private String trimRightSlash(String value) {
        return value == null ? null : value.replaceAll("/+$", "");
    }

    private PreparedSpeechFile prepareSpeechFile(SpeechRequest request) {
        byte[] sourceAudioBytes = decodeAudio(request.getAudio());
        String sourceMimeType = resolveMimeType(request);
        String sourceFileName = resolveFileName(request);

        if (isPcmRequest(request, sourceMimeType)) {
            return new PreparedSpeechFile(
                sourceAudioBytes,
                wrapPcmAsWav(sourceAudioBytes, 16000, (short) 1, (short) 16),
                sourceMimeType,
                "audio/wav",
                sourceFileName,
                replaceExtension(sourceFileName, ".wav"),
                true
            );
        }

        return new PreparedSpeechFile(
            sourceAudioBytes,
            sourceAudioBytes,
            sourceMimeType,
            sourceMimeType,
            sourceFileName,
            sourceFileName,
            false
        );
    }

    private byte[] decodeAudio(String base64Audio) {
        try {
            return Base64.getDecoder().decode(base64Audio);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("音频内容不是合法的 Base64");
        }
    }

    private String resolveMimeType(SpeechRequest request) {
        if (StringUtils.hasText(request.getMimeType())) {
            return request.getMimeType().trim();
        }
        if ("pcm".equalsIgnoreCase(request.getFormat())) {
            return "audio/pcm";
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private String resolveAudioModel(ResolvedAiConfig config) {
        String model = StringUtils.hasText(config.getAudioModel()) ? config.getAudioModel().trim() : null;
        if (isDashScopeSpeech(config)) {
            if (!StringUtils.hasText(model) || DEFAULT_OPENAI_AUDIO_MODEL.equalsIgnoreCase(model)
                || model.toLowerCase().startsWith("paraformer")) {
                return DEFAULT_DASHSCOPE_AUDIO_MODEL;
            }
            return model;
        }
        return StringUtils.hasText(model) ? model : DEFAULT_OPENAI_AUDIO_MODEL;
    }

    private String resolveAudioApiKey(ResolvedAiConfig config) {
        String apiKey = StringUtils.hasText(config.getAudioApiKey()) ? config.getAudioApiKey() : config.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new BusinessException("未配置语音服务密钥");
        }
        return apiKey;
    }

    private boolean isDashScopeSpeech(ResolvedAiConfig config) {
        return ALIYUN_SPEECH_PROVIDER.equalsIgnoreCase(config.getSpeechProvider());
    }

    private String buildOpenAiSpeechEndpoint(String baseUrl) {
        String trimmedBaseUrl = trimRightSlash(baseUrl);
        if (trimmedBaseUrl.endsWith("/audio/transcriptions")) {
            return trimmedBaseUrl;
        }
        return trimmedBaseUrl + "/audio/transcriptions";
    }

    private String buildDashScopeSpeechEndpoint(String baseUrl) {
        String normalizedBaseUrl = normalizeDashScopeBaseUrl(baseUrl);
        if (normalizedBaseUrl.endsWith("/chat/completions")) {
            return normalizedBaseUrl;
        }
        return normalizedBaseUrl + "/chat/completions";
    }

    private String normalizeDashScopeBaseUrl(String baseUrl) {
        String trimmedBaseUrl = trimRightSlash(baseUrl);
        if (trimmedBaseUrl.endsWith("/chat/completions")) {
            return trimmedBaseUrl.substring(0, trimmedBaseUrl.length() - "/chat/completions".length());
        }
        if (trimmedBaseUrl.contains("/compatible-mode/v1")) {
            return trimmedBaseUrl;
        }
        if ("https://dashscope.aliyuncs.com".equalsIgnoreCase(trimmedBaseUrl)
            || "https://dashscope-intl.aliyuncs.com".equalsIgnoreCase(trimmedBaseUrl)) {
            return trimmedBaseUrl + "/compatible-mode/v1";
        }
        return trimmedBaseUrl;
    }

    private Map<String, Object> buildDashScopeSpeechPayload(PreparedSpeechFile preparedFile, String audioModel) {
        String dataUrl = "data:" + preparedFile.mimeType + ";base64,"
            + Base64.getEncoder().encodeToString(preparedFile.audioBytes);

        Map<String, Object> inputAudio = new LinkedHashMap<String, Object>();
        inputAudio.put("data", dataUrl);

        Map<String, Object> contentItem = new LinkedHashMap<String, Object>();
        contentItem.put("type", "input_audio");
        contentItem.put("input_audio", inputAudio);

        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", Collections.singletonList(contentItem));

        Map<String, Object> asrOptions = new LinkedHashMap<String, Object>();
        asrOptions.put("enable_itn", Boolean.TRUE);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", audioModel);
        payload.put("messages", Collections.singletonList(message));
        payload.put("stream", Boolean.FALSE);
        payload.put("asr_options", asrOptions);
        return payload;
    }

    private String extractDashScopeSpeechText(JsonNode response) {
        JsonNode contentNode = response.path("choices").path(0).path("message").path("content");
        return contentNode.isMissingNode() ? response.toString() : contentNode.asText();
    }

    private String resolveFileName(SpeechRequest request) {
        if (StringUtils.hasText(request.getFileName())) {
            return request.getFileName().trim();
        }
        String scene = StringUtils.hasText(request.getScene()) ? request.getScene().trim() : "speech";
        return scene + "-" + System.currentTimeMillis() + resolveExtension(request);
    }

    private String resolveExtension(SpeechRequest request) {
        String mimeType = resolveMimeType(request);
        if ("audio/webm".equalsIgnoreCase(mimeType)) {
            return ".webm";
        }
        if ("audio/wav".equalsIgnoreCase(mimeType) || "audio/wave".equalsIgnoreCase(mimeType)) {
            return ".wav";
        }
        if ("audio/pcm".equalsIgnoreCase(mimeType) || "pcm".equalsIgnoreCase(request.getFormat())) {
            return ".pcm";
        }
        if ("audio/ogg".equalsIgnoreCase(mimeType)) {
            return ".ogg";
        }
        if ("audio/mpeg".equalsIgnoreCase(mimeType)) {
            return ".mp3";
        }
        if ("audio/mp4".equalsIgnoreCase(mimeType)) {
            return ".m4a";
        }
        return ".bin";
    }

    private boolean isPcmRequest(SpeechRequest request, String mimeType) {
        return "pcm".equalsIgnoreCase(request.getFormat()) || "audio/pcm".equalsIgnoreCase(mimeType);
    }

    private byte[] wrapPcmAsWav(byte[] pcmBytes, int sampleRate, short channels, short bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        short blockAlign = (short) (channels * bitsPerSample / 8);
        ByteBuffer buffer = ByteBuffer.allocate(44 + pcmBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.UTF_8));
        buffer.putInt(36 + pcmBytes.length);
        buffer.put("WAVE".getBytes(StandardCharsets.UTF_8));
        buffer.put("fmt ".getBytes(StandardCharsets.UTF_8));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort(channels);
        buffer.putInt(sampleRate);
        buffer.putInt(byteRate);
        buffer.putShort(blockAlign);
        buffer.putShort(bitsPerSample);
        buffer.put("data".getBytes(StandardCharsets.UTF_8));
        buffer.putInt(pcmBytes.length);
        buffer.put(pcmBytes);
        return buffer.array();
    }

    private String replaceExtension(String fileName, String extension) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName + extension;
        }
        return fileName.substring(0, dotIndex) + extension;
    }

    private Map<String, Object> buildChatLogPayload(ChatRequest request,
                                                    UpstreamChatConfig upstreamConfig,
                                                    Map<String, Object> requestBody,
                                                    String responseText,
                                                    String errorMessage,
                                                    String upstreamBody) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("consultationId", request == null ? null : request.getConsultationId());
        payload.put("traceId", request == null ? null : request.getTraceId());
        payload.put("scene", request == null ? null : request.getScene());
        payload.put("sourceModule", request == null ? null : request.getSourceModule());
        payload.put("sessionId", request == null ? null : request.getSessionId());
        payload.put("consultationId", request == null ? null : request.getConsultationId());
        payload.put("configProfile", request == null ? null : request.getConfigProfile());
        payload.put("baseUrl", upstreamConfig.getBaseUrl());
        payload.put("model", upstreamConfig.getModel());
        payload.put("requestBody", requestBody);
        payload.put("responseText", responseText);
        payload.put("upstreamBody", upstreamBody);
        payload.put("errorMessage", errorMessage);
        return payload;
    }

    private Map<String, Object> buildSpeechLogPayload(SpeechRequest request,
                                                      PreparedSpeechFile preparedFile,
                                                      String audioBaseUrl,
                                                      String audioModel,
                                                      String responseText,
                                                      boolean success,
                                                      String errorMessage,
                                                      String upstreamBody) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        Map<String, Object> requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("traceId", request.getTraceId());
        requestBody.put("sourceModule", request.getSourceModule());
        requestBody.put("sessionId", request.getSessionId());
        requestBody.put("mimeType", request.getMimeType());
        requestBody.put("format", request.getFormat());
        requestBody.put("fileName", request.getFileName());
        requestBody.put("scene", request.getScene());
        requestBody.put("sourceAudioSize", preparedFile.sourceAudioBytes.length);
        payload.put("traceId", request.getTraceId());
        payload.put("sourceModule", request.getSourceModule());
        payload.put("sessionId", request.getSessionId());
        payload.put("scene", request.getScene());
        payload.put("baseUrl", audioBaseUrl);
        payload.put("requestBody", requestBody);
        payload.put("preparedFile", buildPreparedFilePayload(preparedFile));
        payload.put("audioModel", audioModel);
        payload.put("responseText", responseText);
        payload.put("upstreamBody", upstreamBody);
        payload.put("success", success);
        payload.put("errorMessage", errorMessage);
        return payload;
    }

    private Map<String, Object> buildPreparedFilePayload(PreparedSpeechFile preparedFile) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sourceMimeType", preparedFile.sourceMimeType);
        payload.put("uploadMimeType", preparedFile.mimeType);
        payload.put("sourceFileName", preparedFile.sourceFileName);
        payload.put("uploadFileName", preparedFile.fileName);
        payload.put("sourceAudioSize", preparedFile.sourceAudioBytes.length);
        payload.put("uploadAudioSize", preparedFile.audioBytes.length);
        payload.put("normalizedToWav", preparedFile.normalizedToWav);
        return payload;
    }

    private String readFully(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[4096];
        int len;
        while ((len = reader.read(buffer)) != -1) {
            sb.append(buffer, 0, len);
        }
        return sb.toString();
    }

    private String extractSseContent(String data) {
        if (!StringUtils.hasText(data) || "[DONE]".equals(data)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode contentNode = root.path("choices").path(0).path("delta").path("content");
            if (contentNode.isTextual()) {
                return contentNode.asText();
            }
        } catch (Exception ex) {
            log.debug("sse content extraction failed. error={}", ex.getMessage());
        }
        return null;
    }

    private String extractSseErrorMessage(String data) {
        if (!StringUtils.hasText(data) || "[DONE]".equals(data)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode errorNode = root.path("error");
            if (errorNode.isObject()) {
                JsonNode messageNode = errorNode.path("message");
                if (messageNode.isTextual() && StringUtils.hasText(messageNode.asText())) {
                    return messageNode.asText();
                }
            }
        } catch (Exception ex) {
            log.debug("sse error message extraction failed. error={}", ex.getMessage());
        }
        return null;
    }

    private UpstreamChatConfig resolveChatConfig(AiDevice device, ChatRequest request) {
        ResolvedAiConfig resolved = configService.resolveByDevice(device);
        String profile = request == null ? null : request.getConfigProfile();
        if ("fast".equalsIgnoreCase(profile)) {
            return new UpstreamChatConfig(
                resolved.getBaseUrl(),
                resolved.getApiKey(),
                StringUtils.hasText(resolved.getFastModel()) ? resolved.getFastModel() : resolved.getModel(),
                Boolean.TRUE.equals(resolved.getEnableThinking())
            );
        }
        if ("reviewer".equalsIgnoreCase(profile)) {
            if (!Boolean.TRUE.equals(resolved.getReviewerEnabled())) {
                throw new BusinessException("当前设备未开启独立审查 AI");
            }
            return new UpstreamChatConfig(
                StringUtils.hasText(resolved.getReviewerBaseUrl()) ? resolved.getReviewerBaseUrl() : resolved.getBaseUrl(),
                StringUtils.hasText(resolved.getReviewerApiKey()) ? resolved.getReviewerApiKey() : resolved.getApiKey(),
                StringUtils.hasText(resolved.getReviewerModel()) ? resolved.getReviewerModel() : resolved.getModel(),
                Boolean.TRUE.equals(resolved.getEnableThinking())
            );
        }
        return new UpstreamChatConfig(
            resolved.getBaseUrl(),
            resolved.getApiKey(),
            resolved.getModel(),
            Boolean.TRUE.equals(resolved.getEnableThinking())
        );
    }

    private static class UpstreamChatConfig {

        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final boolean enableThinking;

        private UpstreamChatConfig(String baseUrl, String apiKey, String model, boolean enableThinking) {
            if (!StringUtils.hasText(baseUrl)) {
                throw new BusinessException("未配置 AI 服务地址");
            }
            if (!StringUtils.hasText(apiKey)) {
                throw new BusinessException("未配置 AI 服务密钥");
            }
            if (!StringUtils.hasText(model)) {
                throw new BusinessException("未配置 AI 模型");
            }
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.enableThinking = enableThinking;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public boolean isEnableThinking() {
            return enableThinking;
        }

        public String getModel() {
            return model;
        }
    }

    private static final class StreamForwardResult {

        private final String responseText;
        private final String errorMessage;

        private StreamForwardResult(String responseText, String errorMessage) {
            this.responseText = responseText;
            this.errorMessage = errorMessage;
        }
    }

    private static final class PreparedSpeechFile {

        private final byte[] sourceAudioBytes;
        private final byte[] audioBytes;
        private final String sourceMimeType;
        private final String mimeType;
        private final String sourceFileName;
        private final String fileName;
        private final boolean normalizedToWav;

        private PreparedSpeechFile(byte[] sourceAudioBytes,
                                   byte[] audioBytes,
                                   String sourceMimeType,
                                   String mimeType,
                                   String sourceFileName,
                                   String fileName,
                                   boolean normalizedToWav) {
            this.sourceAudioBytes = sourceAudioBytes;
            this.audioBytes = audioBytes;
            this.sourceMimeType = sourceMimeType;
            this.mimeType = mimeType;
            this.sourceFileName = sourceFileName;
            this.fileName = fileName;
            this.normalizedToWav = normalizedToWav;
        }
    }
}
