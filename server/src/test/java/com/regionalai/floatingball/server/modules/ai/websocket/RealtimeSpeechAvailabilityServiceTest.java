package com.regionalai.floatingball.server.modules.ai.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealtimeSpeechAvailabilityServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void runTaskPayloadShouldCarryDraftModelAndPcmParameters() {
        RealtimeSpeechAvailabilityService service = new RealtimeSpeechAvailabilityService(
            new ObjectMapper(),
            mock(OutboundSecurityService.class),
            mock(StandardWebSocketClient.class)
        );

        Map<String, Object> message = service.buildRunTaskPayload(
            "qwen-audio-3.0-asr-flash-streaming",
            "task-001"
        );

        Map<String, Object> header = (Map<String, Object>) message.get("header");
        Map<String, Object> payload = (Map<String, Object>) message.get("payload");
        Map<String, Object> parameters = (Map<String, Object>) payload.get("parameters");
        assertThat(header.get("action")).isEqualTo("run-task");
        assertThat(header.get("task_id")).isEqualTo("task-001");
        assertThat(payload.get("model")).isEqualTo("qwen-audio-3.0-asr-flash-streaming");
        assertThat(parameters.get("format")).isEqualTo("pcm");
        assertThat(parameters.get("sample_rate")).isEqualTo(16000);
    }

    @Test
    void shouldRejectProviderWithoutRealtimeWebSocket() {
        RealtimeSpeechAvailabilityService service = new RealtimeSpeechAvailabilityService(
            new ObjectMapper(),
            mock(OutboundSecurityService.class),
            mock(StandardWebSocketClient.class)
        );
        ResolvedAiConfig config = new ResolvedAiConfig();
        config.setSpeechProvider("openai-compatible");

        assertThatThrownBy(() -> service.testConnection(config))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前语音提供方不启用实时 WebSocket");
    }

    @Test
    void shouldCompleteQwenAudioStreamingRunTaskHandshake() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        List<TextMessage> sentMessages = new ArrayList<TextMessage>();
        doAnswer(invocation -> {
            sentMessages.add((TextMessage) invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));

        FakeStandardWebSocketClient webSocketClient = new FakeStandardWebSocketClient(session);
        OutboundSecurityProperties properties = new OutboundSecurityProperties();
        RealtimeSpeechAvailabilityService service = new RealtimeSpeechAvailabilityService(
            objectMapper,
            new OutboundSecurityService(properties),
            webSocketClient
        );
        ResolvedAiConfig config = new ResolvedAiConfig();
        config.setSpeechProvider("aliyun-dashscope");
        config.setSpeechRealtimeUrl("ws://127.0.0.1:18081/api-ws/v1/inference");
        config.setSpeechModel("qwen-audio-3.0-asr-flash-streaming");
        config.setAudioApiKey("speech-key");

        String result = service.testConnection(config);

        assertThat(result).isEqualTo("实时识别模型可用");
        assertThat(sentMessages).hasSize(2);
        assertThat(objectMapper.readTree(sentMessages.get(0).getPayload())
            .path("payload").path("model").asText())
            .isEqualTo("qwen-audio-3.0-asr-flash-streaming");
        assertThat(objectMapper.readTree(sentMessages.get(1).getPayload())
            .path("header").path("action").asText())
            .isEqualTo("finish-task");
    }

    private static final class FakeStandardWebSocketClient extends StandardWebSocketClient {

        private final WebSocketSession session;

        private FakeStandardWebSocketClient(WebSocketSession session) {
            this.session = session;
        }

        @Override
        protected ListenableFuture<WebSocketSession> doHandshakeInternal(WebSocketHandler webSocketHandler,
                                                                          HttpHeaders headers,
                                                                          URI uri,
                                                                          List<String> protocols,
                                                                          List<WebSocketExtension> extensions,
                                                                          Map<String, Object> attributes) {
            try {
                webSocketHandler.afterConnectionEstablished(session);
                webSocketHandler.handleMessage(session, new TextMessage(
                    "{\"header\":{\"event\":\"task-started\"},\"payload\":{}}"
                ));
                SettableListenableFuture<WebSocketSession> future = new SettableListenableFuture<WebSocketSession>();
                future.set(session);
                return future;
            } catch (Exception ex) {
                SettableListenableFuture<WebSocketSession> future = new SettableListenableFuture<WebSocketSession>();
                future.setException(ex);
                return future;
            }
        }
    }
}
