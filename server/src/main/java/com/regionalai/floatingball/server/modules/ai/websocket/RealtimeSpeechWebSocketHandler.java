package com.regionalai.floatingball.server.modules.ai.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.metrics.RealtimeSpeechMetrics;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService.OutboundCall;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PreDestroy;

@Component
public class RealtimeSpeechWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSpeechWebSocketHandler.class);

    private static final String ALIYUN_SPEECH_PROVIDER = "aliyun-dashscope";
    private static final String FUNASR_SPEECH_PROVIDER = "funasr-websocket";
    private static final String DEFAULT_REALTIME_MODEL = "qwen-audio-3.0-asr-flash-streaming";
    private static final String DEFAULT_DASHSCOPE_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    private static final CloseStatus CAPACITY_EXCEEDED = new CloseStatus(1013, "realtime speech capacity exceeded");
    private static final CloseStatus BUFFER_EXCEEDED = new CloseStatus(1009, "buffered audio limit exceeded");
    private static final CloseStatus UPSTREAM_FAILURE = new CloseStatus(1011, "upstream realtime speech failure");
    private static final CloseStatus SERVICE_RESTARTED = new CloseStatus(1012, "realtime speech service restarting");

    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final OutboundSecurityService outboundSecurityService;
    private final RealtimeSpeechMetrics metrics;
    private final int maxActiveSessions;
    private final int maxBufferedAudioBytes;
    private final long handshakeTimeoutMillis;
    private final long taskStartTimeoutMillis;
    private final long idleTimeoutMillis;
    private final long finalTimeoutMillis;
    private final WebSocketClient webSocketClient;
    private final ScheduledExecutorService handshakeScheduler;
    private final boolean ownsHandshakeScheduler;
    private final ConcurrentMap<String, RealtimeProxySession> activeProxySessions =
        new ConcurrentHashMap<String, RealtimeProxySession>();
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Autowired
    public RealtimeSpeechWebSocketHandler(ConfigService configService,
                                          ObjectMapper objectMapper,
                                          OutboundSecurityService outboundSecurityService,
                                          RealtimeSpeechMetrics metrics,
                                          @Value("${floating-ball.ai.realtime.max-active-sessions:64}") int maxActiveSessions,
                                          @Value("${floating-ball.ai.realtime.max-buffered-audio-bytes:2097152}") int maxBufferedAudioBytes,
                                          @Value("${floating-ball.ai.realtime.handshake-timeout-ms:10000}") long handshakeTimeoutMillis,
                                          @Value("${floating-ball.ai.realtime.task-start-timeout-ms:10000}") long taskStartTimeoutMillis,
                                          @Value("${floating-ball.ai.realtime.idle-timeout-ms:60000}") long idleTimeoutMillis,
                                          @Value("${floating-ball.ai.realtime.final-timeout-ms:15000}") long finalTimeoutMillis) {
        this(configService, objectMapper, outboundSecurityService, metrics, maxActiveSessions,
            maxBufferedAudioBytes, handshakeTimeoutMillis, taskStartTimeoutMillis, idleTimeoutMillis,
            finalTimeoutMillis, new StandardWebSocketClient(), newHandshakeScheduler(), true);
    }

    RealtimeSpeechWebSocketHandler(ConfigService configService,
                                   ObjectMapper objectMapper,
                                   OutboundSecurityService outboundSecurityService,
                                   RealtimeSpeechMetrics metrics,
                                   int maxActiveSessions,
                                   int maxBufferedAudioBytes,
                                   long handshakeTimeoutMillis) {
        this(configService, objectMapper, outboundSecurityService, metrics, maxActiveSessions,
            maxBufferedAudioBytes, handshakeTimeoutMillis, handshakeTimeoutMillis,
            Math.max(60000L, handshakeTimeoutMillis), Math.max(15000L, handshakeTimeoutMillis),
            new StandardWebSocketClient(), newHandshakeScheduler(), true);
    }

    RealtimeSpeechWebSocketHandler(ConfigService configService,
                                   ObjectMapper objectMapper,
                                   OutboundSecurityService outboundSecurityService,
                                   RealtimeSpeechMetrics metrics,
                                   int maxActiveSessions,
                                   int maxBufferedAudioBytes,
                                   long handshakeTimeoutMillis,
                                   WebSocketClient webSocketClient,
                                   ScheduledExecutorService handshakeScheduler) {
        this(configService, objectMapper, outboundSecurityService, metrics, maxActiveSessions,
            maxBufferedAudioBytes, handshakeTimeoutMillis, handshakeTimeoutMillis,
            Math.max(60000L, handshakeTimeoutMillis), Math.max(15000L, handshakeTimeoutMillis),
            webSocketClient, handshakeScheduler, false);
    }

    RealtimeSpeechWebSocketHandler(ConfigService configService,
                                   ObjectMapper objectMapper,
                                   OutboundSecurityService outboundSecurityService,
                                   RealtimeSpeechMetrics metrics,
                                   int maxActiveSessions,
                                   int maxBufferedAudioBytes,
                                   long handshakeTimeoutMillis,
                                   long taskStartTimeoutMillis,
                                   long idleTimeoutMillis,
                                   long finalTimeoutMillis,
                                   WebSocketClient webSocketClient,
                                   ScheduledExecutorService handshakeScheduler) {
        this(configService, objectMapper, outboundSecurityService, metrics, maxActiveSessions,
            maxBufferedAudioBytes, handshakeTimeoutMillis, taskStartTimeoutMillis, idleTimeoutMillis,
            finalTimeoutMillis, webSocketClient, handshakeScheduler, false);
    }

    private RealtimeSpeechWebSocketHandler(ConfigService configService,
                                           ObjectMapper objectMapper,
                                           OutboundSecurityService outboundSecurityService,
                                           RealtimeSpeechMetrics metrics,
                                           int maxActiveSessions,
                                           int maxBufferedAudioBytes,
                                           long handshakeTimeoutMillis,
                                           long taskStartTimeoutMillis,
                                           long idleTimeoutMillis,
                                           long finalTimeoutMillis,
                                           WebSocketClient webSocketClient,
                                           ScheduledExecutorService handshakeScheduler,
                                           boolean ownsHandshakeScheduler) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.outboundSecurityService = outboundSecurityService;
        this.metrics = metrics;
        this.maxActiveSessions = Math.max(1, maxActiveSessions);
        this.maxBufferedAudioBytes = Math.max(1, maxBufferedAudioBytes);
        this.handshakeTimeoutMillis = Math.max(1L, handshakeTimeoutMillis);
        this.taskStartTimeoutMillis = Math.max(1L, taskStartTimeoutMillis);
        this.idleTimeoutMillis = Math.max(1L, idleTimeoutMillis);
        this.finalTimeoutMillis = Math.max(1L, finalTimeoutMillis);
        this.webSocketClient = webSocketClient;
        this.handshakeScheduler = handshakeScheduler;
        this.ownsHandshakeScheduler = ownsHandshakeScheduler;
    }

    private static ScheduledExecutorService newHandshakeScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "realtime-speech-deadline");
                thread.setDaemon(true);
                return thread;
            }
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @PreDestroy
    public void shutdownHandshakeScheduler() {
        List<RealtimeProxySession> sessions;
        synchronized (lifecycleMonitor) {
            shuttingDown.set(true);
            sessions = new ArrayList<RealtimeProxySession>(activeProxySessions.values());
        }
        for (RealtimeProxySession session : sessions) {
            session.onApplicationShutdown();
        }
        if (ownsHandshakeScheduler) {
            handshakeScheduler.shutdownNow();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (shuttingDown.get()) {
            sendErrorAndClose(session, "实时语音服务正在重启，请稍后重试", SERVICE_RESTARTED);
            return;
        }
        AiDevice device = (AiDevice) session.getAttributes().get(RealtimeSpeechHandshakeInterceptor.DEVICE_ATTRIBUTE);
        if (device == null) {
            log.warn("realtime speech ws: connection rejected, no device attribute. sessionId={}", session.getId());
            sendErrorAndClose(session, "设备令牌无效或已停用");
            return;
        }

        if (!metrics.tryAcquire(maxActiveSessions)) {
            metrics.rejected("capacity");
            log.warn("realtime speech ws: node session bulkhead is full. sessionId={}, maximum={}",
                session.getId(), maxActiveSessions);
            sendErrorAndClose(session, "当前节点实时语音会话已满，请稍后重试", CAPACITY_EXCEEDED);
            return;
        }
        boolean sessionOwnsCapacity = false;
        try {
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
            synchronized (lifecycleMonitor) {
                if (shuttingDown.get()) {
                    sendErrorAndClose(session, "实时语音服务正在重启，请稍后重试", SERVICE_RESTARTED);
                    return;
                }
                activeProxySessions.put(proxySession.taskId, proxySession);
                sessionOwnsCapacity = true;
            }
            proxySession.connect(apiKey);
        } catch (RuntimeException ex) {
            log.error("realtime speech ws: session initialization failed. sessionId={}, error={}",
                session.getId(), ex.getMessage());
            sendErrorAndClose(session, "实时语音连接创建失败：" + ex.getMessage(), UPSTREAM_FAILURE);
        } finally {
            if (!sessionOwnsCapacity) {
                metrics.release();
            }
        }
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
            proxySession.onClientClosed(status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("realtime speech ws: transport error. sessionId={}, error={}", session.getId(), exception.getMessage());
        RealtimeProxySession proxySession = resolveProxySession(session);
        if (proxySession != null) {
            proxySession.onClientTransportError(exception);
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
        sendErrorAndClose(session, message, CloseStatus.SERVER_ERROR);
    }

    private void sendErrorAndClose(WebSocketSession session, String message, CloseStatus closeStatus) {
        try {
            sendJson(session, errorPayload(message));
        } catch (IOException | RuntimeException ex) {
            log.debug("realtime speech ws: failed to send error payload. sessionId={}, error={}", session.getId(), ex.getMessage());
        }
        try {
            if (session.isOpen()) {
                session.close(closeStatus);
            }
        } catch (IOException | RuntimeException ex) {
            log.debug("realtime speech ws: failed to close session. sessionId={}, error={}", session.getId(), ex.getMessage());
        }
    }

    private Map<String, Object> errorPayload(String message) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "error");
        payload.put("message", message);
        return payload;
    }

    private String safeMessage(Throwable error) {
        return error != null && StringUtils.hasText(error.getMessage()) ? error.getMessage() : "未知错误";
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
        private final AtomicBoolean capacityReleased = new AtomicBoolean(false);
        private OutboundCall outboundCall;
        private WebSocketSession upstreamSession;
        private ListenableFuture<WebSocketSession> pendingHandshake;
        private ScheduledFuture<?> handshakeDeadline;
        private ScheduledFuture<?> taskStartDeadline;
        private ScheduledFuture<?> idleDeadline;
        private ScheduledFuture<?> finalDeadline;
        private long idleGeneration;
        private long bufferedAudioBytes;
        private boolean taskStarted;
        private boolean upstreamReady;
        private boolean finishRequested;
        private boolean finishMessageSent;
        private boolean finalSent;
        private boolean closed;
        private boolean clientClosed;

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
                ListenableFuture<WebSocketSession> handshake =
                    webSocketClient.doHandshake(upstreamHandler, headers, outboundCall.getUri());
                synchronized (this) {
                    if (closed) {
                        cancelFutureQuietly(handshake);
                        return;
                    }
                    pendingHandshake = handshake;
                    handshakeDeadline = handshakeScheduler.schedule(
                        this::onHandshakeTimeout,
                        handshakeTimeoutMillis,
                        TimeUnit.MILLISECONDS
                    );
                }
                handshake.addCallback(this::onUpstreamConnected, this::onUpstreamConnectFailed);
            } catch (RuntimeException ex) {
                failAndClose("实时语音连接创建失败：" + ex.getMessage(), ex);
            }
        }

        private synchronized void onUpstreamConnected(WebSocketSession session) {
            completeHandshakeTracking();
            if (closed) {
                closeSessionQuietly(session, CloseStatus.NORMAL);
                return;
            }
            this.upstreamSession = session;
            try {
                sendStartMessage();
                if (!closed) {
                    if (isFunAsr(config)) {
                        scheduleIdleDeadline();
                    } else {
                        scheduleTaskStartDeadline();
                    }
                }
            } catch (RuntimeException | IOException ex) {
                failAndClose("实时语音初始化消息发送失败：" + ex.getMessage(), ex);
            }
        }

        private synchronized void onUpstreamConnectFailed(Throwable error) {
            completeHandshakeTracking();
            if (closed) {
                return;
            }
            failAndClose("实时语音连接失败：" + safeMessage(error), error);
        }

        private synchronized void onHandshakeTimeout() {
            if (closed || pendingHandshake == null) {
                return;
            }
            failAndClose(
                "实时语音上游握手超时，请稍后重试",
                UPSTREAM_FAILURE,
                new TimeoutException("realtime speech upstream handshake timed out")
            );
        }

        private synchronized void forwardAudio(byte[] bytes) {
            if (closed) {
                return;
            }
            touchActivity();
            if (!taskStarted || upstreamSession == null || !isUpstreamOpen()) {
                if (closed) {
                    return;
                }
                long nextBufferedBytes = bufferedAudioBytes + bytes.length;
                if (nextBufferedBytes > maxBufferedAudioBytes) {
                    metrics.rejected("buffer");
                    failAndClose("上游连接尚未就绪，待发送音频超过缓存上限", BUFFER_EXCEEDED, null);
                    return;
                }
                audioBuffer.add(bytes);
                bufferedAudioBytes = nextBufferedBytes;
                return;
            }
            sendUpstreamBinary(bytes);
        }

        private synchronized void finish() {
            if (closed) {
                return;
            }
            if (!finishRequested) {
                finishRequested = true;
                cancelIdleDeadline();
                scheduleFinalDeadline();
            }
            if (taskStarted && upstreamSession != null && isUpstreamOpen()) {
                sendFinishMessage();
            }
        }

        private synchronized void onClientClosed(CloseStatus status) {
            clientClosed = true;
            log.info("realtime speech downstream closed. taskId={}, model={}, status={}",
                taskId, resolveRealtimeModel(config), status);
            closeUpstream();
        }

        private synchronized void onClientTransportError(Throwable error) {
            clientClosed = true;
            log.warn("realtime speech downstream transport error. taskId={}, model={}, error={}",
                taskId, resolveRealtimeModel(config), safeMessage(error));
            closeUpstream();
        }

        private synchronized void closeUpstream() {
            if (closed) {
                cancelAllDeadlines();
                releaseCapacityOnce();
                return;
            }
            closed = true;
            try {
                clearAudioBuffer();
                cancelPendingHandshake();
                cancelAllDeadlines();
                closeSessionQuietly(upstreamSession, CloseStatus.NORMAL);
            } finally {
                releaseCapacityOnce();
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
                failAndClose("实时语音响应解析失败：" + ex.getMessage(), ex);
            }
        }

        private void onDashScopeMessage(JsonNode root) {
            try {
                JsonNode header = root.path("header");
                String event = header.path("event").asText();
                if ("task-started".equals(event)) {
                    taskStarted = true;
                    markUpstreamReady();
                    flushAudioBuffer();
                    if (finishRequested) {
                        sendFinishMessage();
                    }
                    return;
                }
                if ("result-generated".equals(event)) {
                    touchActivity();
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
                    log.info("realtime speech upstream task finished. taskId={}, model={}",
                        taskId, resolveRealtimeModel(config));
                    sendFinal();
                    if (!closed) {
                        closeUpstream();
                        closeClient(CloseStatus.NORMAL);
                    }
                    return;
                }
                if ("task-failed".equals(event)) {
                    String errorCode = header.path("error_code").asText("");
                    String message = header.path("error_message").asText(header.path("message").asText("DashScope 实时语音识别失败"));
                    terminateWithError(message, errorCode, null);
                }
            } catch (Exception ex) {
                terminateWithError("DashScope 实时语音响应解析失败：" + safeMessage(ex), null, ex);
            }
        }

        private void onFunAsrMessage(JsonNode root) {
            FunAsrRealtimeProtocol.Result result = funAsrProtocol.accept(root);
            if (StringUtils.hasText(result.getErrorMessage())) {
                failAndClose(result.getErrorMessage(), new IOException(result.getErrorMessage()));
                return;
            }
            markUpstreamReady();
            touchActivity();
            if (StringUtils.hasText(result.getText())) {
                sendText(result.getText(), result.isSentenceEnd());
            }
            if (!closed && result.isFinalSignal()) {
                sendFinal();
                if (!closed) {
                    closeUpstream();
                    closeClient(CloseStatus.NORMAL);
                }
            }
        }

        private synchronized void onUpstreamClosed(CloseStatus status) {
            log.info("realtime speech upstream closed. taskId={}, model={}, status={}, finishRequested={}, clientClosed={}",
                taskId, resolveRealtimeModel(config), status, finishRequested, clientClosed);
            if (clientClosed || finalSent) {
                return;
            }
            if (finishRequested) {
                sendFinal();
                closeUpstream();
                closeClient(CloseStatus.NORMAL);
                return;
            }
            terminateWithError("实时语音上游连接已关闭，请重新连接后继续识别", null, null);
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
            upstreamSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                buildDashScopeRunTaskPayload(resolveRealtimeModel(config), taskId)
            )));
        }

        private void sendFinishMessage() {
            if (finishMessageSent || closed) {
                return;
            }
            finishMessageSent = true;
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
            } catch (IOException | RuntimeException ex) {
                failAndClose("实时语音结束消息发送失败：" + ex.getMessage(), ex);
            }
        }

        private void flushAudioBuffer() {
            List<byte[]> pendingAudio = new ArrayList<byte[]>(audioBuffer);
            clearAudioBuffer();
            for (byte[] bytes : pendingAudio) {
                if (closed) {
                    break;
                }
                sendUpstreamBinary(bytes);
            }
        }

        private void sendUpstreamBinary(byte[] bytes) {
            try {
                upstreamSession.sendMessage(new BinaryMessage(bytes));
            } catch (IOException | RuntimeException ex) {
                terminateWithError("实时语音音频帧发送失败：" + safeMessage(ex), null, ex);
            }
        }

        private boolean isUpstreamOpen() {
            try {
                return upstreamSession != null && upstreamSession.isOpen();
            } catch (RuntimeException ex) {
                failAndClose("实时语音上游连接状态异常：" + safeMessage(ex), ex);
                return false;
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

        private void failAndClose(String message, Throwable failure) {
            failAndClose(message, UPSTREAM_FAILURE, failure);
        }

        private synchronized void failAndClose(String message,
                                               CloseStatus downstreamStatus,
                                               Throwable failure) {
            if (closed) {
                cancelPendingHandshake();
                cancelAllDeadlines();
                releaseCapacityOnce();
                return;
            }
            closed = true;
            finalSent = true;
            try {
                if (failure != null) {
                    markOutboundFailure(failure);
                }
                clearAudioBuffer();
                cancelPendingHandshake();
                cancelAllDeadlines();
                if (!clientClosed) {
                    sendErrorQuietly(message);
                }
                closeSessionQuietly(upstreamSession, CloseStatus.SERVER_ERROR);
                closeClient(downstreamStatus);
            } finally {
                releaseCapacityOnce();
            }
        }

        private synchronized void terminateWithError(String message, String errorCode, Throwable error) {
            if (finalSent || clientClosed) {
                return;
            }
            log.warn("realtime speech upstream failed. taskId={}, model={}, errorCode={}, message={}",
                taskId, resolveRealtimeModel(config), StringUtils.hasText(errorCode) ? errorCode : "unknown", message);
            failAndClose(message, UPSTREAM_FAILURE, error);
        }

        private void completeHandshakeTracking() {
            pendingHandshake = null;
            cancelHandshakeDeadline();
        }

        private void scheduleTaskStartDeadline() {
            cancelTaskStartDeadline();
            taskStartDeadline = handshakeScheduler.schedule(
                this::onTaskStartTimeout,
                taskStartTimeoutMillis,
                TimeUnit.MILLISECONDS
            );
        }

        private synchronized void onTaskStartTimeout() {
            if (closed || upstreamReady) {
                return;
            }
            failAndClose(
                "实时语音上游任务启动超时，请稍后重试",
                UPSTREAM_FAILURE,
                new TimeoutException("realtime speech upstream task start timed out")
            );
        }

        private void markUpstreamReady() {
            if (upstreamReady || closed) {
                return;
            }
            upstreamReady = true;
            cancelTaskStartDeadline();
            markOutboundSuccess();
            if (!finishRequested) {
                scheduleIdleDeadline();
            }
        }

        private void touchActivity() {
            if (idleWatchdogActive() && !finishRequested && !closed) {
                scheduleIdleDeadline();
            }
        }

        private boolean idleWatchdogActive() {
            return upstreamReady || (isFunAsr(config) && taskStarted);
        }

        private void scheduleIdleDeadline() {
            cancelIdleDeadline();
            final long generation = idleGeneration;
            idleDeadline = handshakeScheduler.schedule(
                () -> onIdleTimeout(generation),
                idleTimeoutMillis,
                TimeUnit.MILLISECONDS
            );
        }

        private synchronized void onIdleTimeout(long generation) {
            if (generation != idleGeneration || closed || finishRequested || !idleWatchdogActive()) {
                return;
            }
            failAndClose(
                "实时语音会话长时间无活动，已自动关闭",
                UPSTREAM_FAILURE,
                new TimeoutException("realtime speech session idle timed out")
            );
        }

        private void scheduleFinalDeadline() {
            cancelFinalDeadline();
            finalDeadline = handshakeScheduler.schedule(
                this::onFinalTimeout,
                finalTimeoutMillis,
                TimeUnit.MILLISECONDS
            );
        }

        private synchronized void onFinalTimeout() {
            if (closed || finalSent || !finishRequested) {
                return;
            }
            failAndClose(
                "实时语音上游结束响应超时，请稍后重试",
                UPSTREAM_FAILURE,
                new TimeoutException("realtime speech upstream final response timed out")
            );
        }

        private void cancelPendingHandshake() {
            ListenableFuture<WebSocketSession> handshake = pendingHandshake;
            pendingHandshake = null;
            cancelHandshakeDeadline();
            cancelFutureQuietly(handshake);
        }

        private void cancelHandshakeDeadline() {
            ScheduledFuture<?> deadline = handshakeDeadline;
            handshakeDeadline = null;
            if (deadline != null) {
                try {
                    deadline.cancel(false);
                } catch (RuntimeException ex) {
                    log.debug("realtime speech ws: failed to cancel handshake deadline. error={}", ex.getMessage());
                }
            }
        }

        private void cancelTaskStartDeadline() {
            taskStartDeadline = cancelDeadline(taskStartDeadline, "task-start");
        }

        private void cancelIdleDeadline() {
            idleGeneration++;
            idleDeadline = cancelDeadline(idleDeadline, "idle");
        }

        private void cancelFinalDeadline() {
            finalDeadline = cancelDeadline(finalDeadline, "final");
        }

        private void cancelAllDeadlines() {
            cancelHandshakeDeadline();
            cancelTaskStartDeadline();
            cancelIdleDeadline();
            cancelFinalDeadline();
        }

        private ScheduledFuture<?> cancelDeadline(ScheduledFuture<?> deadline, String name) {
            if (deadline == null) {
                return null;
            }
            try {
                deadline.cancel(false);
            } catch (RuntimeException ex) {
                log.debug("realtime speech ws: failed to cancel {} deadline. error={}", name, ex.getMessage());
            }
            return null;
        }

        private void cancelFutureQuietly(ListenableFuture<WebSocketSession> handshake) {
            if (handshake == null) {
                return;
            }
            try {
                if (!handshake.isDone()) {
                    handshake.cancel(true);
                }
            } catch (RuntimeException ex) {
                log.debug("realtime speech ws: failed to cancel upstream handshake. error={}", ex.getMessage());
            }
        }

        private void clearAudioBuffer() {
            audioBuffer.clear();
            bufferedAudioBytes = 0L;
        }

        private void releaseCapacityOnce() {
            if (capacityReleased.compareAndSet(false, true)) {
                activeProxySessions.remove(taskId, this);
                metrics.release();
            }
        }

        private synchronized void onApplicationShutdown() {
            if (closed) {
                cancelPendingHandshake();
                cancelAllDeadlines();
                releaseCapacityOnce();
                return;
            }
            closed = true;
            finalSent = true;
            try {
                clearAudioBuffer();
                cancelPendingHandshake();
                cancelAllDeadlines();
                closeSessionQuietly(upstreamSession, SERVICE_RESTARTED);
                closeClient(SERVICE_RESTARTED);
            } finally {
                releaseCapacityOnce();
            }
        }

        private synchronized void closeClient(CloseStatus status) {
            if (clientClosed) {
                return;
            }
            clientClosed = true;
            try {
                if (clientSession.isOpen()) {
                    clientSession.close(status);
                }
            } catch (IOException | RuntimeException ex) {
                log.debug("realtime speech ws: failed to close downstream session. taskId={}, error={}",
                    taskId, ex.getMessage());
            }
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
            if (closed || clientClosed) {
                return;
            }
            try {
                if (!clientSession.isOpen()) {
                    failAndClose("实时语音客户端连接已关闭", UPSTREAM_FAILURE, null);
                    return;
                }
                clientSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } catch (IOException | RuntimeException ex) {
                log.warn("realtime speech ws: downstream send failed. taskId={}, error={}", taskId, safeMessage(ex));
                failAndClose("实时语音结果发送失败，请重新连接后继续识别", UPSTREAM_FAILURE, null);
            }
        }

        private void sendErrorQuietly(String message) {
            try {
                sendJson(clientSession, errorPayload(message));
            } catch (IOException | RuntimeException ex) {
                log.debug("realtime speech ws: failed to send terminal error payload. taskId={}, error={}",
                    taskId, safeMessage(ex));
            }
        }
    }

    private void closeSessionQuietly(WebSocketSession session, CloseStatus status) {
        if (session == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException | RuntimeException ex) {
            log.debug("realtime speech ws: failed to close websocket session. sessionId={}, error={}",
                session.getId(), ex.getMessage());
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
            proxySession.onUpstreamClosed(status);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            proxySession.terminateWithError("实时语音连接异常：" + safeMessage(exception), null, exception);
        }
    }

    Map<String, Object> buildDashScopeRunTaskPayload(String model, String taskId) {
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("format", "pcm");
        parameters.put("sample_rate", 16000);
        parameters.put("heartbeat", true);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("task_group", "audio");
        body.put("task", "asr");
        body.put("function", "recognition");
        body.put("model", model);
        body.put("parameters", parameters);
        body.put("input", Collections.emptyMap());

        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("header", header);
        message.put("payload", body);
        return message;
    }
}
