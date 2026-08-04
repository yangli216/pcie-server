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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiProxyServiceTest {

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
            true
        );

        assertThat(payload).containsEntry("enable_thinking", true);
        assertThat(payload).containsEntry("stream", false);
        assertThat(payload).containsEntry("model", "main-model");
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
}
