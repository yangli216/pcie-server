package com.regionalai.floatingball.server.modules.ai.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
}
