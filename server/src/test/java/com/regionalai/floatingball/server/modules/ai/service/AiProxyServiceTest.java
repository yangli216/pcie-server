package com.regionalai.floatingball.server.modules.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ServiceBusyException;
import com.regionalai.floatingball.server.common.metrics.AiProxyMetrics;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.ai.dto.ChatRequest;
import com.regionalai.floatingball.server.modules.ai.dto.SpeechRequest;
import com.regionalai.floatingball.server.modules.audit.service.AuditService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.conn.ConnectionPoolTimeoutException;

import java.lang.reflect.Constructor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
            new TaskExecutorAdapter(Runnable::run),
            AiProxyMetrics.noop()
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

    @Test
    void streamCancellationClosesBodyAndRemovesQueuedFuture() throws Exception {
        ThreadPoolTaskExecutor executor = singleThreadExecutor();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> await(running, release));
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        Future<?> queued = executor.submit(() -> { });
        Class<?> lifecycleClass = Class.forName(
            "com.regionalai.floatingball.server.modules.ai.service.AiProxyService$StreamLifecycle"
        );
        Constructor<?> constructor = lifecycleClass.getDeclaredConstructor(AsyncTaskExecutor.class);
        constructor.setAccessible(true);
        Object lifecycle = constructor.newInstance(executor);
        CloseAwareInputStream body = new CloseAwareInputStream();

        ReflectionTestUtils.invokeMethod(lifecycle, "attach", queued);
        ReflectionTestUtils.invokeMethod(lifecycle, "attach", body);
        Boolean cancelled = ReflectionTestUtils.invokeMethod(lifecycle, "cancel");

        assertThat(cancelled).isTrue();
        assertThat(body.closed).isTrue();
        assertThat(queued.isCancelled()).isTrue();
        assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        ReflectionTestUtils.invokeMethod(lifecycle, "detachAndClose", body);
        ReflectionTestUtils.invokeMethod(lifecycle, "finished");
        Boolean cancelledAgain = ReflectionTestUtils.invokeMethod(lifecycle, "cancel");
        assertThat(cancelledAgain).isFalse();
        assertThat(body.closeCount.get()).isEqualTo(1);
        release.countDown();
        executor.shutdown();
    }

    @Test
    void streamCancellationAndCompletionRaceReleasesBodyOnlyOnce() throws Exception {
        AsyncTaskExecutor directExecutor = new TaskExecutorAdapter(Runnable::run);
        Class<?> lifecycleClass = Class.forName(
            "com.regionalai.floatingball.server.modules.ai.service.AiProxyService$StreamLifecycle"
        );
        Constructor<?> constructor = lifecycleClass.getDeclaredConstructor(AsyncTaskExecutor.class);
        constructor.setAccessible(true);
        ExecutorService raceExecutor = Executors.newFixedThreadPool(2);

        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                Object lifecycle = constructor.newInstance(directExecutor);
                CloseAwareInputStream body = new CloseAwareInputStream();
                ReflectionTestUtils.invokeMethod(lifecycle, "attach", body);
                CountDownLatch start = new CountDownLatch(1);

                Future<Boolean> cancellation = raceExecutor.submit(() -> {
                    start.await();
                    return ReflectionTestUtils.invokeMethod(lifecycle, "cancel");
                });
                Future<?> completion = raceExecutor.submit(() -> {
                    start.await();
                    ReflectionTestUtils.invokeMethod(lifecycle, "finished");
                    return null;
                });

                start.countDown();
                boolean cancellationWon = cancellation.get(2, TimeUnit.SECONDS);
                completion.get(2, TimeUnit.SECONDS);
                ReflectionTestUtils.invokeMethod(lifecycle, "detachAndClose", body);

                Boolean cancelled = ReflectionTestUtils.invokeMethod(lifecycle, "isCancelled");
                Boolean cancelledAgain = ReflectionTestUtils.invokeMethod(lifecycle, "cancel");
                assertThat(cancelled).isEqualTo(cancellationWon);
                assertThat(cancelledAgain).isFalse();
                assertThat(body.closeCount.get()).isEqualTo(1);
            }
        } finally {
            raceExecutor.shutdownNow();
        }
    }

    @Test
    void rejectedChatStreamDoesNotResolveConfigurationBeforeAdmission() {
        ConfigService configService = mock(ConfigService.class);
        AsyncTaskExecutor rejecting = new TaskExecutorAdapter(command -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        });
        AiProxyService service = new AiProxyService(
            configService,
            mock(AuditService.class),
            new RestTemplate(),
            new ObjectMapper(),
            mock(OutboundSecurityService.class),
            rejecting,
            AiProxyMetrics.noop()
        );
        ChatRequest request = new ChatRequest();
        request.setStream(Boolean.TRUE);

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = service.chatStream(new AiDevice(), request);

        assertThat(emitter.getTimeout()).isEqualTo(130000L);
        verifyNoInteractions(configService);
    }

    @Test
    void streamErrorFrameMarksOutboundAndMetricsAsFailedWithoutThrowingAnotherFrame() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        ResolvedAiConfig resolved = new ResolvedAiConfig();
        resolved.setBaseUrl("http://localhost/v1");
        resolved.setApiKey("main-key");
        resolved.setModel("main-model");
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(resolved);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(restTemplate).build();
        String endpoint = "http://localhost/v1/chat/completions";
        upstream.expect(requestTo(endpoint)).andRespond(withSuccess(
            "data: {\"error\":{\"message\":\"upstream overloaded\"}}\n\ndata: [DONE]\n\n",
            MediaType.TEXT_EVENT_STREAM
        ));
        OutboundSecurityProperties outboundProperties = new OutboundSecurityProperties();
        outboundProperties.setCircuitFailureThreshold(1);
        outboundProperties.setCircuitOpenMs(60000L);
        OutboundSecurityService outboundSecurity = new OutboundSecurityService(outboundProperties);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiProxyMetrics metrics = new AiProxyMetrics(registry);
        AiProxyService service = new AiProxyService(
            configService,
            mock(AuditService.class),
            restTemplate,
            new ObjectMapper(),
            outboundSecurity,
            new TaskExecutorAdapter(Runnable::run),
            metrics
        );
        ChatRequest request = new ChatRequest();
        request.setStream(Boolean.TRUE);
        request.setMessages(Collections.<Map<String, Object>>emptyList());

        service.chatStream(new AiDevice(), request);

        upstream.verify();
        verify(configService, times(1)).resolveByDevice(any(AiDevice.class));
        assertThat(registry.get("pcie.ai.proxy.requests")
            .tags("mode", "stream", "outcome", "failed").counter().count()).isEqualTo(1D);
        assertThat(registry.find("pcie.ai.proxy.requests")
            .tags("mode", "stream", "outcome", "succeeded").counter()).isNull();
        assertThatThrownBy(() -> outboundSecurity.acquireHttp(endpoint, "ai-chat-stream"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("上游服务暂时不可用，请稍后重试");
    }

    @Test
    void nonStreamingConnectionPoolTimeoutReturnsBusyInsteadOfBusinessFailure() {
        ConfigService configService = mock(ConfigService.class);
        ResolvedAiConfig resolved = mainConfig("http://localhost/v1");
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(resolved);
        OutboundSecurityService outboundSecurity = new OutboundSecurityService(new OutboundSecurityProperties());
        AiProxyService service = new AiProxyService(
            configService,
            mock(AuditService.class),
            new PoolExhaustedRestTemplate(),
            new ObjectMapper(),
            outboundSecurity,
            new TaskExecutorAdapter(Runnable::run),
            AiProxyMetrics.noop()
        );
        ChatRequest request = new ChatRequest();
        request.setMessages(Collections.<Map<String, Object>>emptyList());

        assertThatThrownBy(() -> service.chat(new AiDevice(), request))
            .isInstanceOf(ServiceBusyException.class)
            .extracting("code", "retryAfterSeconds")
            .containsExactly("AI-BUSY", 1);

        assertThat(outboundSecurity.acquireHttp("http://localhost/v1/chat/completions", "ai-chat")).isNotNull();
    }

    @Test
    void streamingConnectionPoolTimeoutSendsControlledBusyFrames() {
        ConfigService configService = mock(ConfigService.class);
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(mainConfig("http://localhost/v1"));
        OutboundSecurityService outboundSecurity = new OutboundSecurityService(new OutboundSecurityProperties());
        AiProxyService service = new AiProxyService(
            configService,
            mock(AuditService.class),
            new PoolExhaustedRestTemplate(),
            new ObjectMapper(),
            outboundSecurity,
            new TaskExecutorAdapter(Runnable::run),
            AiProxyMetrics.noop()
        );
        ChatRequest request = new ChatRequest();
        request.setMessages(Collections.<Map<String, Object>>emptyList());

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = service.chatStream(new AiDevice(), request);

        assertThat(earlyFramePayload(emitter)).contains("AI连接资源繁忙，请稍后重试").contains("[DONE]");
        assertThat(outboundSecurity.acquireHttp("http://localhost/v1/chat/completions", "ai-chat-stream")).isNotNull();
    }

    @Test
    void admittedStreamConfigurationErrorUsesSseErrorContract() {
        ConfigService configService = mock(ConfigService.class);
        when(configService.resolveByDevice(any(AiDevice.class))).thenThrow(new BusinessException("未配置 AI 服务地址"));
        AiProxyService service = new AiProxyService(
            configService,
            mock(AuditService.class),
            new RestTemplate(),
            new ObjectMapper(),
            mock(OutboundSecurityService.class),
            new TaskExecutorAdapter(Runnable::run),
            AiProxyMetrics.noop()
        );

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = service.chatStream(new AiDevice(), new ChatRequest());

        assertThat(earlyFramePayload(emitter)).contains("未配置 AI 服务地址").contains("[DONE]");
        verify(configService, times(1)).resolveByDevice(any(AiDevice.class));
    }

    private AiProxyService newService(ConfigService configService, AuditService auditService) {
        return new AiProxyService(
            configService,
            auditService,
            new RestTemplate(),
            new ObjectMapper(),
            mock(OutboundSecurityService.class),
            new TaskExecutorAdapter(Runnable::run),
            AiProxyMetrics.noop()
        );
    }

    private ResolvedAiConfig mainConfig(String baseUrl) {
        ResolvedAiConfig resolved = new ResolvedAiConfig();
        resolved.setBaseUrl(baseUrl);
        resolved.setApiKey("main-key");
        resolved.setModel("main-model");
        return resolved;
    }

    private String earlyFramePayload(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        Set<?> earlyFrames = (Set<?>) ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        StringBuilder payload = new StringBuilder();
        for (Object frame : earlyFrames) {
            payload.append(String.valueOf(ReflectionTestUtils.getField(frame, "data")));
        }
        return payload.toString();
    }

    private ThreadPoolTaskExecutor singleThreadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        return executor;
    }

    private void await(CountDownLatch running, CountDownLatch release) {
        running.countDown();
        try {
            release.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CloseAwareInputStream extends ByteArrayInputStream {

        private boolean closed;
        private final AtomicInteger closeCount = new AtomicInteger();

        private CloseAwareInputStream() {
            super(new byte[] { 1 });
        }

        @Override
        public void close() throws IOException {
            closed = true;
            closeCount.incrementAndGet();
            super.close();
        }
    }

    private static final class PoolExhaustedRestTemplate extends RestTemplate {

        @Override
        public <T> T execute(String url,
                             HttpMethod method,
                             RequestCallback requestCallback,
                             ResponseExtractor<T> responseExtractor,
                             Object... uriVariables) {
            throw new org.springframework.web.client.ResourceAccessException(
                "connection pool exhausted",
                new ConnectionPoolTimeoutException("timeout waiting for connection")
            );
        }
    }
}
