package com.regionalai.floatingball.server.modules.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.ai.dto.ChatRequest;
import com.regionalai.floatingball.server.modules.ai.dto.SpeechRequest;
import com.regionalai.floatingball.server.modules.audit.service.AuditService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiProxyServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void chatShouldPersistServerMeasuredLatencyAndBusinessContext() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer upstream = MockRestServiceServer.createServer(restTemplate);
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        OutboundSecurityProperties properties = new OutboundSecurityProperties();
        AiProxyService service = new AiProxyService(
            configService,
            auditService,
            restTemplate,
            new ObjectMapper(),
            new OutboundSecurityService(properties),
            Runnable::run
        );
        ResolvedAiConfig config = resolvedChatConfig();
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(config);
        upstream.expect(requestTo("http://127.0.0.1:18080/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", MediaType.APPLICATION_JSON));

        ChatRequest request = chatRequest(false);
        String response = service.chat(new AiDevice(), request);

        assertThat(response).isEqualTo("ok");
        org.mockito.ArgumentCaptor<Object> payloadCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(auditService).saveSystemLog(
            any(AiDevice.class),
            eq("ai_proxy"),
            eq("ai"),
            eq("assess_medication_with_current_information"),
            payloadCaptor.capture(),
            eq(true)
        );
        Map<String, Object> auditPayload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(auditPayload).containsEntry("provider", "dashscope");
        assertThat(auditPayload).containsEntry("model", "qwen-plus");
        assertThat(auditPayload).containsEntry("action", "assess_medication_with_current_information");
        assertThat(auditPayload).containsEntry("title", "医生主动基于现有信息评估用药");
        assertThat((Long) auditPayload.get("durationMs")).isGreaterThanOrEqualTo(0L);
        assertThat(auditPayload.get("firstTokenMs")).isNull();
        upstream.verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatStreamShouldPersistFirstTokenAndTotalLatency() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer upstream = MockRestServiceServer.createServer(restTemplate);
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        AiProxyService service = new AiProxyService(
            configService,
            auditService,
            restTemplate,
            new ObjectMapper(),
            new OutboundSecurityService(new OutboundSecurityProperties()),
            Runnable::run
        );
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(resolvedChatConfig());
        upstream.expect(requestTo("http://127.0.0.1:18080/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n",
                MediaType.TEXT_EVENT_STREAM
            ));

        service.chatStream(new AiDevice(), chatRequest(true));

        org.mockito.ArgumentCaptor<Object> payloadCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(auditService).saveSystemLog(
            any(AiDevice.class),
            eq("ai_proxy"),
            eq("ai"),
            eq("assess_medication_with_current_information"),
            payloadCaptor.capture(),
            eq(true)
        );
        Map<String, Object> auditPayload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat((Long) auditPayload.get("firstTokenMs")).isGreaterThanOrEqualTo(0L);
        assertThat((Long) auditPayload.get("durationMs"))
            .isGreaterThanOrEqualTo((Long) auditPayload.get("firstTokenMs"));
        upstream.verify();
    }

    @Test
    void resolveChatConfigUsesReviewerOverrides() {
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        AiProxyService service = newService(configService, auditService);

        ResolvedAiConfig resolved = new ResolvedAiConfig();
        resolved.setBaseUrl("https://main.example/v1");
        resolved.setApiKey("main-key");
        resolved.setModel("main-model");
        resolved.setReviewerEnabled(Boolean.TRUE);
        resolved.setReviewerBaseUrl("https://reviewer.example/v1");
        resolved.setReviewerApiKey("reviewer-key");
        resolved.setReviewerModel("reviewer-model");
        resolved.setEnableThinking(Boolean.TRUE);
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(resolved);

        ChatRequest request = new ChatRequest();
        request.setConfigProfile("reviewer");

        Object upstream = ReflectionTestUtils.invokeMethod(service, "resolveChatConfig", new AiDevice(), request);

        assertThat(ReflectionTestUtils.getField(upstream, "baseUrl")).isEqualTo("https://reviewer.example/v1");
        assertThat(ReflectionTestUtils.getField(upstream, "apiKey")).isEqualTo("reviewer-key");
        assertThat(ReflectionTestUtils.getField(upstream, "model")).isEqualTo("reviewer-model");
        assertThat(ReflectionTestUtils.getField(upstream, "enableThinking")).isEqualTo(true);
    }

    @Test
    void resolveChatConfigUsesFastModelWhenRequested() {
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        AiProxyService service = newService(configService, auditService);

        ResolvedAiConfig resolved = new ResolvedAiConfig();
        resolved.setBaseUrl("https://main.example/v1");
        resolved.setApiKey("main-key");
        resolved.setModel("main-model");
        resolved.setFastModel("fast-model");
        resolved.setEnableThinking(Boolean.TRUE);
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(resolved);

        ChatRequest request = new ChatRequest();
        request.setConfigProfile("fast");

        Object upstream = ReflectionTestUtils.invokeMethod(service, "resolveChatConfig", new AiDevice(), request);

        assertThat(ReflectionTestUtils.getField(upstream, "baseUrl")).isEqualTo("https://main.example/v1");
        assertThat(ReflectionTestUtils.getField(upstream, "apiKey")).isEqualTo("main-key");
        assertThat(ReflectionTestUtils.getField(upstream, "model")).isEqualTo("fast-model");
        assertThat(ReflectionTestUtils.getField(upstream, "enableThinking")).isEqualTo(true);
    }

    @Test
    void resolveChatConfigFastProfileFallsBackToMainModel() {
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        AiProxyService service = newService(configService, auditService);

        ResolvedAiConfig resolved = new ResolvedAiConfig();
        resolved.setBaseUrl("https://main.example/v1");
        resolved.setApiKey("main-key");
        resolved.setModel("main-model");
        resolved.setEnableThinking(Boolean.TRUE);
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(resolved);

        ChatRequest request = new ChatRequest();
        request.setConfigProfile("fast");

        Object upstream = ReflectionTestUtils.invokeMethod(service, "resolveChatConfig", new AiDevice(), request);

        assertThat(ReflectionTestUtils.getField(upstream, "model")).isEqualTo("main-model");
        assertThat(ReflectionTestUtils.getField(upstream, "enableThinking")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildChatPayloadShouldCarryEnableThinkingFlag() {
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        AiProxyService service = newService(configService, auditService);

        Map<String, Object> payload = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
            service,
            "buildChatPayload",
            "main-model",
            Collections.<Map<String, Object>>emptyList(),
            Boolean.FALSE,
            null,
            true,
            false
        );

        assertThat(payload).containsEntry("enable_thinking", true);
        assertThat(payload).containsEntry("stream", false);
        assertThat(payload).containsEntry("model", "main-model");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildChatPayloadShouldIncludeSearchOnlyWhenEnabled() {
        AiProxyService service = newService(mock(ConfigService.class), mock(AuditService.class));

        Map<String, Object> enabledPayload = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
            service,
            "buildChatPayload",
            "main-model",
            Collections.<Map<String, Object>>emptyList(),
            Boolean.TRUE,
            null,
            false,
            true
        );
        Map<String, Object> disabledPayload = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
            service,
            "buildChatPayload",
            "main-model",
            Collections.<Map<String, Object>>emptyList(),
            Boolean.TRUE,
            null,
            false,
            false
        );

        assertThat(enabledPayload).containsEntry("enable_search", true);
        assertThat(disabledPayload).doesNotContainKey("enable_search");
    }

    @Test
    void shouldEnableWebSearchOnlyForAssistantStreamingRequests() {
        AiProxyService service = newService(mock(ConfigService.class), mock(AuditService.class));
        ChatRequest request = new ChatRequest();
        request.setEnableSearch(Boolean.TRUE);
        request.setStream(Boolean.TRUE);
        request.setScene("chat-stream");
        request.setSourceModule("chat_panel");

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldEnableWebSearch", request)).isTrue();

        request.setScene("voice-intent-recognition");
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldEnableWebSearch", request)).isFalse();

        request.setScene("chat-stream");
        request.setStream(Boolean.FALSE);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "shouldEnableWebSearch", request)).isFalse();
    }

    @Test
    void resolveChatConfigFallsBackToDefaultWhenReviewerOverridesMissing() {
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        AiProxyService service = newService(configService, auditService);

        ResolvedAiConfig resolved = new ResolvedAiConfig();
        resolved.setBaseUrl("https://main.example/v1");
        resolved.setApiKey("main-key");
        resolved.setModel("main-model");
        resolved.setReviewerEnabled(Boolean.TRUE);
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(resolved);

        ChatRequest request = new ChatRequest();
        request.setConfigProfile("reviewer");

        Object upstream = ReflectionTestUtils.invokeMethod(service, "resolveChatConfig", new AiDevice(), request);

        assertThat(ReflectionTestUtils.getField(upstream, "baseUrl")).isEqualTo("https://main.example/v1");
        assertThat(ReflectionTestUtils.getField(upstream, "apiKey")).isEqualTo("main-key");
        assertThat(ReflectionTestUtils.getField(upstream, "model")).isEqualTo("main-model");
    }

    @Test
    void resolveChatConfigRejectsReviewerProfileWhenDisabled() {
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        AiProxyService service = newService(configService, auditService);

        ResolvedAiConfig resolved = new ResolvedAiConfig();
        resolved.setBaseUrl("https://main.example/v1");
        resolved.setApiKey("main-key");
        resolved.setModel("main-model");
        resolved.setReviewerEnabled(Boolean.FALSE);
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(resolved);

        ChatRequest request = new ChatRequest();
        request.setConfigProfile("reviewer");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "resolveChatConfig", new AiDevice(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前设备未开启独立审查 AI");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildSpeechLogPayloadShouldNotContainRawAudio() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        AiProxyService service = newService(configService, auditService);

        SpeechRequest request = new SpeechRequest();
        request.setAudio("BASE64_AUDIO_SHOULD_NOT_BE_LOGGED");
        request.setTraceId("TRACE001");
        request.setSourceModule("llm");
        request.setSessionId("SESSION001");
        request.setMimeType("audio/webm");
        request.setFormat("webm");
        request.setFileName("speech.webm");
        request.setScene("chat-input");

        Class<?> preparedSpeechFileClass = Class.forName("com.regionalai.floatingball.server.modules.ai.service.AiProxyService$PreparedSpeechFile");
        Constructor<?> constructor = preparedSpeechFileClass.getDeclaredConstructor(
            byte[].class,
            byte[].class,
            String.class,
            String.class,
            String.class,
            String.class,
            boolean.class
        );
        constructor.setAccessible(true);
        Object preparedFile = constructor.newInstance(
            "source-audio".getBytes(StandardCharsets.UTF_8),
            "upload-audio".getBytes(StandardCharsets.UTF_8),
            "audio/webm",
            "audio/webm",
            "speech.webm",
            "speech.webm",
            false
        );

        Map<String, Object> payload = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
            service,
            "buildSpeechLogPayload",
            request,
            preparedFile,
            "https://speech.example/v1",
            "whisper-1",
            "ok",
            true,
            null,
            "{\"text\":\"ok\"}"
        );

        Map<String, Object> requestBody = (Map<String, Object>) payload.get("requestBody");
        assertThat(requestBody).doesNotContainKey("audio");
        assertThat(requestBody).containsEntry("fileName", "speech.webm");
        assertThat(requestBody).containsEntry("sourceAudioSize", 12);
    }

    @Test
    void resolveAudioApiKeyShouldPreferSpeechKeyAndFallbackToMainKey() {
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        AiProxyService service = newService(configService, auditService);

        ResolvedAiConfig resolved = new ResolvedAiConfig();
        resolved.setApiKey("main-key");
        resolved.setAudioApiKey("speech-key");

        String speechKey = ReflectionTestUtils.invokeMethod(service, "resolveAudioApiKey", resolved);
        assertThat(speechKey).isEqualTo("speech-key");

        resolved.setAudioApiKey("");
        String fallbackKey = ReflectionTestUtils.invokeMethod(service, "resolveAudioApiKey", resolved);
        assertThat(fallbackKey).isEqualTo("main-key");
    }

    @Test
    void dashScopeSpeechShouldNormalizeEndpointAndLegacyModel() {
        ConfigService configService = mock(ConfigService.class);
        AuditService auditService = mock(AuditService.class);
        AiProxyService service = newService(configService, auditService);

        ResolvedAiConfig resolved = new ResolvedAiConfig();
        resolved.setSpeechProvider("aliyun-dashscope");
        resolved.setAudioModel("paraformer-realtime-v2");

        String model = ReflectionTestUtils.invokeMethod(service, "resolveAudioModel", resolved);
        String endpoint = ReflectionTestUtils.invokeMethod(service, "buildDashScopeSpeechEndpoint", "https://dashscope.aliyuncs.com");

        assertThat(model).isEqualTo("qwen3-asr-flash");
        assertThat(endpoint).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
    }

    @Test
    void testSpeechConnectionShouldCallDashScopeWithSilentAudio() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer upstream = MockRestServiceServer.createServer(restTemplate);
        OutboundSecurityProperties properties = new OutboundSecurityProperties();
        AiProxyService service = new AiProxyService(
            mock(ConfigService.class),
            mock(AuditService.class),
            restTemplate,
            new ObjectMapper(),
            new OutboundSecurityService(properties),
            Runnable::run
        );

        upstream.expect(requestTo("http://127.0.0.1:18080/compatible-mode/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer speech-key"))
            .andExpect(jsonPath("$.model").value("qwen3-asr-flash"))
            .andExpect(jsonPath("$.messages[0].content[0].input_audio.data").exists())
            .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        String result = service.testSpeechConnection(
            "aliyun-dashscope",
            "http://127.0.0.1:18080/compatible-mode/v1",
            "speech-key",
            "qwen3-asr-flash"
        );

        assertThat(result).isEqualTo("批量转写模型可用");
        upstream.verify();
    }

    private AiProxyService newService(ConfigService configService, AuditService auditService) {
        return new AiProxyService(
            configService,
            auditService,
            new RestTemplate(),
            new ObjectMapper(),
            mock(OutboundSecurityService.class),
            Runnable::run
        );
    }

    private ResolvedAiConfig resolvedChatConfig() {
        ResolvedAiConfig config = new ResolvedAiConfig();
        config.setProvider("dashscope");
        config.setBaseUrl("http://127.0.0.1:18080");
        config.setApiKey("test-key");
        config.setModel("qwen-plus");
        return config;
    }

    private ChatRequest chatRequest(boolean stream) {
        ChatRequest request = new ChatRequest();
        request.setMessages(Collections.<Map<String, Object>>singletonList(
            Collections.<String, Object>singletonMap("role", "user")
        ));
        request.setStream(stream);
        request.setScene("current-information-medication");
        request.setSourceModule("voice_treatment_recommendation");
        request.setOperationAction("assess_medication_with_current_information");
        request.setOperationTitle("医生主动基于现有信息评估用药");
        request.setTraceId("trace-latency-test");
        return request;
    }
}
