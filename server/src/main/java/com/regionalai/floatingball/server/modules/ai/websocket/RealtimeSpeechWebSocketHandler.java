package com.regionalai.floatingball.server.modules.ai.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService.OutboundCall;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class RealtimeSpeechWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSpeechWebSocketHandler.class);

    private static final String ALIYUN_SPEECH_PROVIDER = "aliyun-dashscope";
    private static final String FUNASR_SPEECH_PROVIDER = "funasr-websocket";
    private static final String DEFAULT_REALTIME_MODEL = "qwen-audio-3.0-asr-flash-streaming";
    private static final String DEFAULT_DASHSCOPE_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";

    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final OutboundSecurityService outboundSecurityService;
    private final StandardWebSocketClient webSocketClient;

    public RealtimeSpeechWebSocketHandler(ConfigService configService,
                                          ObjectMapper objectMapper,
                                          OutboundSecurityService outboundSecurityService) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.outboundSecurityService = outboundSecurityService;
        this.webSocketClient = new StandardWebSocketClient();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        AiDevice device = (AiDevice) session.getAttributes().get(RealtimeSpeechHandshakeInterceptor.DEVICE_ATTRIBUTE);
        if (device == null) {
            log.warn("realtime speech ws: connection rejected, no device attribute. sessionId={}", session.getId());
            sendErrorAndClose(session, "设备令牌无效或已停用");
            return;
        }

        ResolvedAiConfig config = configService.resolveByDevice(device);
        if (!isRealtimeProvider(config.getSpeechProvider())) {
            log.warn("realtime speech ws: connection rejected, provider does not support realtime. deviceId={}, provider={}", device.getIdDevice(), config.getSpeechProvider());
            sendErrorAndClose(session, "当前语音提供方未启用实时识别");
            return;
        }
        if (isDashScope(config) && isUnsupportedDashScopeSessionModel(resolveRealtimeModel(config))) {
            log.warn("realtime speech ws: connection rejected, unsupported protocol model. deviceId={}, model={}",
                device.getIdDevice(), resolveRealtimeModel(config));
            sendErrorAndClose(session, "qwen3-asr-flash-realtime 使用不同的 session 协议，当前实时地址不支持该模型");
            return;
        }

        String apiKey = isDashScope(config) ? resolveAudioApiKey(config) : null;
        if (isDashScope(config) && !StringUtils.hasText(apiKey)) {
            log.warn("realtime speech ws: connection rejected, no audio api key. deviceId={}", device.getIdDevice());
            sendErrorAndClose(session, "未配置语音服务密钥");
            return;
        }
        if (isFunAsr(config) && !StringUtils.hasText(config.getSpeechRealtimeUrl())) {
            log.warn("realtime speech ws: connection rejected, no FunASR realtime URL. deviceId={}", device.getIdDevice());
            sendErrorAndClose(session, "未配置 FunASR 实时识别地址");
            return;
        }

        log.info("realtime speech ws: connection established. deviceId={}, model={}", device.getIdDevice(), resolveRealtimeModel(config));
        RealtimeProxySession proxySession = new RealtimeProxySession(session, config);
        session.getAttributes().put(RealtimeProxySession.ATTRIBUTE, proxySession);
        proxySession.connect(apiKey);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        RealtimeProxySession proxySession = resolveProxySession(session);
        if (proxySession == null) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        proxySession.forwardAudio(bytes);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        RealtimeProxySession proxySession = resolveProxySession(session);
        if (proxySession == null) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.path("type").asText();
            if ("finish".equals(type)) {
                proxySession.finish();
            }
        } catch (Exception ex) {
            proxySession.sendError("实时语音控制消息解析失败：" + ex.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("realtime speech ws: connection closed. sessionId={}, status={}", session.getId(), status);
        RealtimeProxySession proxySession = resolveProxySession(session);
        if (proxySession != null) {
            proxySession.closeUpstream();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("realtime speech ws: transport error. sessionId={}, error={}", session.getId(), exception.getMessage());
        RealtimeProxySession proxySession = resolveProxySession(session);
        if (proxySession != null) {
            proxySession.sendError("实时语音连接异常：" + exception.getMessage());
            proxySession.closeUpstream();
        }
    }

    private RealtimeProxySession resolveProxySession(WebSocketSession session) {
        return (RealtimeProxySession) session.getAttributes().get(RealtimeProxySession.ATTRIBUTE);
    }

    private String resolveAudioApiKey(ResolvedAiConfig config) {
        return StringUtils.hasText(config.getAudioApiKey()) ? config.getAudioApiKey() : config.getApiKey();
    }

    private boolean isRealtimeProvider(String provider) {
        return ALIYUN_SPEECH_PROVIDER.equalsIgnoreCase(provider) || FUNASR_SPEECH_PROVIDER.equalsIgnoreCase(provider);
    }

    private boolean isDashScope(ResolvedAiConfig config) {
        return ALIYUN_SPEECH_PROVIDER.equalsIgnoreCase(config.getSpeechProvider());
    }

    private boolean isFunAsr(ResolvedAiConfig config) {
        return FUNASR_SPEECH_PROVIDER.equalsIgnoreCase(config.getSpeechProvider());
    }

    private String resolveRealtimeModel(ResolvedAiConfig config) {
        String model = StringUtils.hasText(config.getSpeechModel()) ? config.getSpeechModel().trim() : DEFAULT_REALTIME_MODEL;
        return model;
    }

    private boolean isUnsupportedDashScopeSessionModel(String model) {
        return StringUtils.hasText(model)
            && model.trim().toLowerCase().startsWith("qwen3-asr-flash-realtime");
    }

    private String resolveRealtimeWsUrl(ResolvedAiConfig config) {
        if (StringUtils.hasText(config.getSpeechRealtimeUrl())) {
            return config.getSpeechRealtimeUrl().trim().replaceAll("/+$", "");
        }
        return DEFAULT_DASHSCOPE_WS_URL;
    }

    private void sendErrorAndClose(WebSocketSession session, String message) {
        try {
            sendJson(session, errorPayload(message));
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ex) {
            log.debug("realtime speech ws: failed to send error and close session. sessionId={}, error={}", session.getId(), ex.getMessage());
        }
    }

    private Map<String, Object> errorPayload(String message) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "error");
        payload.put("message", message);
        return payload;
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        }
    }

    private final class RealtimeProxySession {

        private static final String ATTRIBUTE = "realtimeSpeechProxySession";

        private final WebSocketSession clientSession;
        private final ResolvedAiConfig config;
        private final String taskId;
        private final List<byte[]> audioBuffer = new ArrayList<byte[]>();
        private final StringBuilder fullText = new StringBuilder();
        private final FunAsrRealtimeProtocol funAsrProtocol;
        private OutboundCall outboundCall;
        private WebSocketSession upstreamSession;
        private boolean taskStarted;
        private boolean finishRequested;
        private boolean finalSent;
        private boolean closed;

        private RealtimeProxySession(WebSocketSession clientSession, ResolvedAiConfig config) {
            this.clientSession = clientSession;
            this.config = config;
            this.taskId = UUID.randomUUID().toString().replace("-", "");
            this.funAsrProtocol = isFunAsr(config) ? new FunAsrRealtimeProtocol() : null;
        }

        private void connect(String apiKey) {
            try {
                WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
                if (isDashScope(config)) {
                    headers.setBearerAuth(apiKey);
                }
                String endpoint = resolveRealtimeWsUrl(config);
                outboundCall = outboundSecurityService.acquireWebSocket(endpoint, "speech-realtime-ws");
                WebSocketHandler upstreamHandler = new UpstreamWebSocketHandler(this);
                webSocketClient.doHandshake(upstreamHandler, headers, outboundCall.getUri())
                    .addCallback(this::onUpstreamConnected, this::onUpstreamConnectFailed);
            } catch (RuntimeException ex) {
                markOutboundFailure(ex);
                sendError("实时语音连接创建失败：" + ex.getMessage());
            }
        }

        private synchronized void onUpstreamConnected(WebSocketSession session) {
            this.upstreamSession = session;
            markOutboundSuccess();
            try {
                sendStartMessage();
            } catch (IOException ex) {
                sendError("实时语音初始化消息发送失败：" + ex.getMessage());
            }
        }

        private void onUpstreamConnectFailed(Throwable error) {
            markOutboundFailure(error);
            sendError("实时语音连接失败：" + error.getMessage());
        }

        private synchronized void forwardAudio(byte[] bytes) {
            if (closed) {
                return;
            }
            if (!taskStarted || upstreamSession == null || !upstreamSession.isOpen()) {
                audioBuffer.add(bytes);
                return;
            }
            sendUpstreamBinary(bytes);
        }

        private synchronized void finish() {
            finishRequested = true;
            if (taskStarted && upstreamSession != null && upstreamSession.isOpen()) {
                sendFinishMessage();
            }
        }

        private synchronized void closeUpstream() {
            closed = true;
            if (upstreamSession != null && upstreamSession.isOpen()) {
                try {
                    upstreamSession.close();
                } catch (IOException ex) {
                    log.debug("realtime speech ws: failed to close upstream session. error={}", ex.getMessage());
                }
            }
        }

        private synchronized void onUpstreamMessage(String payload) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                if (isFunAsr(config)) {
                    onFunAsrMessage(root);
                    return;
                }
                onDashScopeMessage(root);
            } catch (Exception ex) {
                sendError("实时语音响应解析失败：" + ex.getMessage());
            }
        }

        private void onDashScopeMessage(JsonNode root) {
            try {
                JsonNode header = root.path("header");
                String event = header.path("event").asText();
                if ("task-started".equals(event)) {
                    taskStarted = true;
                    flushAudioBuffer();
                    if (finishRequested) {
                        sendFinishMessage();
                    }
                    return;
                }
                if ("result-generated".equals(event)) {
                    JsonNode sentence = root.path("payload").path("output").path("sentence");
                    String text = sentence.path("text").asText("");
                    boolean sentenceEnd = sentence.path("sentence_end").asBoolean(false);
                    if (sentenceEnd) {
                        fullText.append(text);
                    }
                    sendText(text, sentenceEnd);
                    return;
                }
                if ("task-finished".equals(event)) {
                    sendFinal();
                    closeUpstream();
                    return;
                }
                if ("task-failed".equals(event)) {
                    String message = header.path("error_message").asText(header.path("message").asText("DashScope 实时语音识别失败"));
                    sendError(message);
                    closeUpstream();
                }
            } catch (Exception ex) {
                sendError("DashScope 实时语音响应解析失败：" + ex.getMessage());
            }
        }

        private void onFunAsrMessage(JsonNode root) {
            FunAsrRealtimeProtocol.Result result = funAsrProtocol.accept(root);
            if (StringUtils.hasText(result.getErrorMessage())) {
                sendError(result.getErrorMessage());
                closeUpstream();
                return;
            }
            if (StringUtils.hasText(result.getText())) {
                sendText(result.getText(), result.isSentenceEnd());
            }
            if (result.isFinalSignal()) {
                sendFinal();
                closeUpstream();
            }
        }

        private void onUpstreamClosed() {
            if (!finalSent && clientSession.isOpen()) {
                sendFinal();
            }
        }

        private void sendStartMessage() throws IOException {
            if (isFunAsr(config)) {
                upstreamSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(funAsrProtocol.startPayload())));
                taskStarted = true;
                flushAudioBuffer();
                if (finishRequested) {
                    sendFinishMessage();
                }
                return;
            }
            sendRunTask();
        }

        private void sendRunTask() throws IOException {
            Map<String, Object> header = new LinkedHashMap<String, Object>();
            header.put("action", "run-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");

            Map<String, Object> parameters = new LinkedHashMap<String, Object>();
            parameters.put("format", "pcm");
            parameters.put("sample_rate", 16000);

            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("task_group", "audio");
            body.put("task", "asr");
            body.put("function", "recognition");
            body.put("model", resolveRealtimeModel(config));
            body.put("parameters", parameters);
            body.put("input", Collections.emptyMap());

            Map<String, Object> message = new LinkedHashMap<String, Object>();
            message.put("header", header);
            message.put("payload", body);
            upstreamSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        }

        private void sendFinishMessage() {
            try {
                if (isFunAsr(config)) {
                    upstreamSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(funAsrProtocol.finishPayload())));
                    return;
                }
                Map<String, Object> header = new LinkedHashMap<String, Object>();
                header.put("action", "finish-task");
                header.put("task_id", taskId);
                header.put("streaming", "duplex");

                Map<String, Object> body = new LinkedHashMap<String, Object>();
                body.put("input", Collections.emptyMap());

                Map<String, Object> message = new LinkedHashMap<String, Object>();
                message.put("header", header);
                message.put("payload", body);
                upstreamSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            } catch (IOException ex) {
                sendError("实时语音结束消息发送失败：" + ex.getMessage());
            }
        }

        private void flushAudioBuffer() {
            for (byte[] bytes : audioBuffer) {
                sendUpstreamBinary(bytes);
            }
            audioBuffer.clear();
        }

        private void sendUpstreamBinary(byte[] bytes) {
            try {
                upstreamSession.sendMessage(new BinaryMessage(bytes));
            } catch (IOException ex) {
                sendError("实时语音音频帧发送失败：" + ex.getMessage());
            }
        }

        private void sendText(String text, boolean sentenceEnd) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("type", "text");
            payload.put("text", text);
            payload.put("isSentenceEnd", sentenceEnd);
            sendClient(payload);
        }

        private void sendFinal() {
            if (finalSent) {
                return;
            }
            finalSent = true;
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("type", "final");
            payload.put("text", funAsrProtocol == null ? fullText.toString() : funAsrProtocol.getFullText());
            sendClient(payload);
        }

        private void sendError(String message) {
            sendClient(errorPayload(message));
        }

        private void markOutboundSuccess() {
            if (outboundCall != null) {
                outboundCall.success();
            }
        }

        private void markOutboundFailure(Throwable error) {
            if (outboundCall != null) {
                outboundCall.failure(error);
            }
        }

        private void sendClient(Map<String, Object> payload) {
            try {
                sendJson(clientSession, payload);
            } catch (IOException ex) {
                log.debug("realtime speech ws: failed to send client payload. error={}", ex.getMessage());
            }
        }
    }

    private final class UpstreamWebSocketHandler extends AbstractWebSocketHandler {

        private final RealtimeProxySession proxySession;

        private UpstreamWebSocketHandler(RealtimeProxySession proxySession) {
            this.proxySession = proxySession;
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            if (message instanceof TextMessage) {
                proxySession.onUpstreamMessage(((TextMessage) message).getPayload());
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            proxySession.onUpstreamClosed();
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            proxySession.markOutboundFailure(exception);
            proxySession.sendError("实时语音连接异常：" + exception.getMessage());
        }
    }
}
