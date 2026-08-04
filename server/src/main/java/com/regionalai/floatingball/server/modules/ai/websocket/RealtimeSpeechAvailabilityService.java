package com.regionalai.floatingball.server.modules.ai.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService.OutboundCall;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RealtimeSpeechAvailabilityService {

    private static final String ALIYUN_SPEECH_PROVIDER = "aliyun-dashscope";
    private static final String FUNASR_SPEECH_PROVIDER = "funasr-websocket";
    private static final String DEFAULT_DASHSCOPE_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    private static final long TEST_TIMEOUT_SECONDS = 8L;

    private final ObjectMapper objectMapper;
    private final OutboundSecurityService outboundSecurityService;
    private final StandardWebSocketClient webSocketClient;

    public RealtimeSpeechAvailabilityService(ObjectMapper objectMapper,
                                             OutboundSecurityService outboundSecurityService) {
        this(objectMapper, outboundSecurityService, new StandardWebSocketClient());
    }

    RealtimeSpeechAvailabilityService(ObjectMapper objectMapper,
                                      OutboundSecurityService outboundSecurityService,
                                      StandardWebSocketClient webSocketClient) {
        this.objectMapper = objectMapper;
        this.outboundSecurityService = outboundSecurityService;
        this.webSocketClient = webSocketClient;
    }

    public String testConnection(ResolvedAiConfig config) {
        String provider = config == null ? null : config.getSpeechProvider();
        if (!ALIYUN_SPEECH_PROVIDER.equalsIgnoreCase(provider)
            && !FUNASR_SPEECH_PROVIDER.equalsIgnoreCase(provider)) {
            throw new BusinessException("当前语音提供方不启用实时 WebSocket");
        }
        boolean dashScope = ALIYUN_SPEECH_PROVIDER.equalsIgnoreCase(provider);
        if (dashScope && !StringUtils.hasText(config.getAudioApiKey())) {
            throw new BusinessException("请先填写语音服务密钥");
        }
        if (!dashScope && !StringUtils.hasText(config.getSpeechRealtimeUrl())) {
            throw new BusinessException("请先填写 FunASR 实时识别地址");
        }

        String endpoint = resolveEndpoint(config, dashScope);
        OutboundCall outboundCall = outboundSecurityService.acquireWebSocket(endpoint, "speech-realtime-test");
        AvailabilityProbe probe = new AvailabilityProbe(config, dashScope);
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (dashScope) {
            headers.setBearerAuth(config.getAudioApiKey());
        }

        try {
            webSocketClient.doHandshake(probe, headers, outboundCall.getUri())
                .addCallback(probe::onConnected, probe::onConnectFailed);
            if (!probe.await()) {
                BusinessException timeout = new BusinessException("实时语音模型测试超时，请检查地址、网络和模型名");
                outboundCall.failure(timeout);
                probe.close();
                throw timeout;
            }
            if (StringUtils.hasText(probe.getErrorMessage())) {
                BusinessException failure = new BusinessException(probe.getErrorMessage());
                outboundCall.failure(failure);
                throw failure;
            }
            outboundCall.success();
            return dashScope ? "实时识别模型可用" : "FunASR 实时服务可连接";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            outboundCall.failure(ex);
            probe.close();
            throw new BusinessException("实时语音模型测试被中断");
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            outboundCall.failure(ex);
            probe.close();
            throw new BusinessException("实时语音模型连接失败：" + safeMessage(ex));
        }
    }

    Map<String, Object> buildRunTaskPayload(String model, String taskId) {
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("format", "pcm");
        parameters.put("sample_rate", 16000);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("task_group", "audio");
        payload.put("task", "asr");
        payload.put("function", "recognition");
        payload.put("model", model);
        payload.put("parameters", parameters);
        payload.put("input", Collections.emptyMap());

        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("header", header);
        message.put("payload", payload);
        return message;
    }

    private Map<String, Object> buildFinishTaskPayload(String taskId) {
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("action", "finish-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("input", Collections.emptyMap());

        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("header", header);
        message.put("payload", payload);
        return message;
    }

    private String resolveEndpoint(ResolvedAiConfig config, boolean dashScope) {
        if (StringUtils.hasText(config.getSpeechRealtimeUrl())) {
            return config.getSpeechRealtimeUrl().trim().replaceAll("/+$", "");
        }
        return dashScope ? DEFAULT_DASHSCOPE_WS_URL : null;
    }

    private String resolveModel(ResolvedAiConfig config) {
        return StringUtils.hasText(config.getSpeechModel())
            ? config.getSpeechModel().trim()
            : "paraformer-realtime-v2";
    }

    private String safeMessage(Throwable error) {
        return error != null && StringUtils.hasText(error.getMessage()) ? error.getMessage() : "未知错误";
    }

    private final class AvailabilityProbe extends AbstractWebSocketHandler {

        private final ResolvedAiConfig config;
        private final boolean dashScope;
        private final String taskId = UUID.randomUUID().toString().replace("-", "");
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicBoolean settled = new AtomicBoolean(false);
        private final AtomicReference<String> errorMessage = new AtomicReference<String>();
        private volatile WebSocketSession session;

        private AvailabilityProbe(ResolvedAiConfig config, boolean dashScope) {
            this.config = config;
            this.dashScope = dashScope;
        }

        private void onConnected(WebSocketSession connectedSession) {
            this.session = connectedSession;
        }

        private void onConnectFailed(Throwable error) {
            fail("实时语音 WebSocket 连接失败：" + safeMessage(error));
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession connectedSession) throws Exception {
            this.session = connectedSession;
            if (!dashScope) {
                succeed();
                close();
                return;
            }
            connectedSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                buildRunTaskPayload(resolveModel(config), taskId)
            )));
        }

        @Override
        protected void handleTextMessage(WebSocketSession activeSession, TextMessage message) {
            try {
                JsonNode root = objectMapper.readTree(message.getPayload());
                String event = root.path("header").path("event").asText();
                if ("task-started".equals(event)) {
                    activeSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(buildFinishTaskPayload(taskId))));
                    succeed();
                    close();
                } else if ("task-failed".equals(event)) {
                    String upstreamMessage = root.path("header").path("error_message").asText("上游拒绝该实时识别模型");
                    fail("实时语音模型不可用：" + upstreamMessage);
                    close();
                }
            } catch (Exception ex) {
                fail("实时语音响应解析失败：" + safeMessage(ex));
                close();
            }
        }

        @Override
        public void handleTransportError(WebSocketSession activeSession, Throwable exception) {
            fail("实时语音 WebSocket 异常：" + safeMessage(exception));
            close();
        }

        @Override
        public void afterConnectionClosed(WebSocketSession activeSession, CloseStatus status) {
            if (!settled.get()) {
                fail("实时语音 WebSocket 在模型确认前已关闭");
            }
        }

        private boolean await() throws InterruptedException {
            return completed.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private String getErrorMessage() {
            return errorMessage.get();
        }

        private void succeed() {
            if (settled.compareAndSet(false, true)) {
                completed.countDown();
            }
        }

        private void fail(String message) {
            if (settled.compareAndSet(false, true)) {
                errorMessage.set(message);
                completed.countDown();
            }
        }

        private void close() {
            WebSocketSession activeSession = session;
            if (activeSession != null && activeSession.isOpen()) {
                try {
                    activeSession.close(CloseStatus.NORMAL);
                } catch (IOException ignored) {
                    // Test result has already been settled; connection cleanup is best effort.
                }
            }
        }
    }
}
