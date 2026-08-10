package com.regionalai.floatingball.server.modules.ai.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RealtimeSpeechWebSocketHandlerTest {

    @Test
    void shouldUseQwenAudioStreamingAsDefaultDashScopeModel() {
        RealtimeSpeechWebSocketHandler handler = new RealtimeSpeechWebSocketHandler(
            mock(ConfigService.class),
            new ObjectMapper(),
            mock(OutboundSecurityService.class)
        );
        ResolvedAiConfig config = new ResolvedAiConfig();
        config.setSpeechProvider("aliyun-dashscope");

        String model = ReflectionTestUtils.invokeMethod(handler, "resolveRealtimeModel", config);

        assertThat(model).isEqualTo("qwen-audio-3.0-asr-flash-streaming");
    }

    @Test
    void shouldKeepExplicitQwenAudioStreamingModel() {
        RealtimeSpeechWebSocketHandler handler = new RealtimeSpeechWebSocketHandler(
            mock(ConfigService.class),
            new ObjectMapper(),
            mock(OutboundSecurityService.class)
        );
        ResolvedAiConfig config = new ResolvedAiConfig();
        config.setSpeechProvider("aliyun-dashscope");
        config.setSpeechModel("qwen-audio-3.0-asr-flash-streaming");

        String model = ReflectionTestUtils.invokeMethod(handler, "resolveRealtimeModel", config);

        assertThat(model).isEqualTo("qwen-audio-3.0-asr-flash-streaming");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldEnableHeartbeatForDashScopeRunTask() {
        RealtimeSpeechWebSocketHandler handler = new RealtimeSpeechWebSocketHandler(
            mock(ConfigService.class),
            new ObjectMapper(),
            mock(OutboundSecurityService.class)
        );

        Map<String, Object> message = handler.buildDashScopeRunTaskPayload(
            "qwen-audio-3.0-asr-flash-streaming",
            "task-001"
        );
        Map<String, Object> payload = (Map<String, Object>) message.get("payload");
        Map<String, Object> parameters = (Map<String, Object>) payload.get("parameters");

        assertThat(parameters.get("heartbeat")).isEqualTo(true);
        assertThat(parameters.get("format")).isEqualTo("pcm");
        assertThat(parameters.get("sample_rate")).isEqualTo(16000);
    }
}
