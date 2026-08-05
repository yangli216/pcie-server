package com.regionalai.floatingball.server.modules.ai.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
}
