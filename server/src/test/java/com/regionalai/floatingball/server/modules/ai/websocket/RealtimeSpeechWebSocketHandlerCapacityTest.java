package com.regionalai.floatingball.server.modules.ai.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.metrics.RealtimeSpeechMetrics;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeSpeechWebSocketHandlerCapacityTest {

    private ScheduledExecutorService handshakeScheduler;

    @BeforeEach
    void setUpScheduler() {
        handshakeScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDownScheduler() {
        handshakeScheduler.shutdownNow();
    }

    @Test
    void rejectsSecondSessionWith1013WhenNodeBulkheadIsFull() throws Exception {
        Fixture fixture = fixture(1, 1024);
        WebSocketSession first = clientSession("first");
        WebSocketSession second = clientSession("second");

        fixture.handler.afterConnectionEstablished(first);
        fixture.handler.afterConnectionEstablished(second);

        assertThat(fixture.metrics.activeSessions()).isEqualTo(1);
        assertThat(fixture.registry.get("pcie.ai.realtime.sessions.active").gauge().value()).isEqualTo(1D);
        assertThat(fixture.registry.get("pcie.ai.realtime.sessions.rejected")
            .tag("reason", "capacity").counter().count()).isEqualTo(1D);
        verify(second).close(closeStatus(1013));
        verify(fixture.configService, times(1)).resolveByDevice(any(AiDevice.class));

        fixture.handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        assertThat(fixture.metrics.activeSessions()).isZero();
    }

    @Test
    void closesWith1009AndClearsBufferedAudioWhenPendingBufferOverflows() throws Exception {
        Fixture fixture = fixture(4, 4);
        WebSocketSession client = clientSession("buffer-overflow");
        fixture.handler.afterConnectionEstablished(client);

        fixture.handler.handleMessage(client, new BinaryMessage(new byte[4]));
        fixture.handler.handleMessage(client, new BinaryMessage(new byte[1]));

        verify(client).close(closeStatus(1009));
        assertThat(fixture.metrics.activeSessions()).isZero();
        assertThat(fixture.registry.get("pcie.ai.realtime.sessions.rejected")
            .tag("reason", "buffer").counter().count()).isEqualTo(1D);
        assertBufferCleared(client);

        fixture.handler.afterConnectionClosed(client, CloseStatus.TOO_BIG_TO_PROCESS);
        assertThat(fixture.metrics.activeSessions()).isZero();
    }

    @Test
    void upstreamConnectionFailureClearsBufferClosesClientAndReleasesOnce() throws Exception {
        Fixture fixture = fixture(4, 1024);
        WebSocketSession client = clientSession("connect-failed");
        fixture.handler.afterConnectionEstablished(client);
        fixture.handler.handleMessage(client, new BinaryMessage(new byte[128]));

        fixture.failHandshake(new IllegalStateException("dial failed"));

        verify(client).close(closeStatus(1011));
        assertThat(fixture.metrics.activeSessions()).isZero();
        assertBufferCleared(client);

        fixture.handler.afterConnectionClosed(client, CloseStatus.SERVER_ERROR);
        fixture.handler.afterConnectionClosed(client, CloseStatus.SERVER_ERROR);
        assertThat(fixture.metrics.activeSessions()).isZero();
    }

    @Test
    void normalDownstreamCloseClosesUpstreamAndReleasesOnlyOnce() throws Exception {
        Fixture fixture = fixture(4, 1024);
        WebSocketSession client = clientSession("normal");
        WebSocketSession upstream = websocketSession("upstream");
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);
        assertThat(fixture.metrics.activeSessions()).isEqualTo(1);

        fixture.handler.afterConnectionClosed(client, CloseStatus.NORMAL);
        fixture.handler.afterConnectionClosed(client, CloseStatus.NORMAL);

        assertThat(fixture.metrics.activeSessions()).isZero();
        assertThat(fixture.registry.get("pcie.ai.realtime.sessions.active").gauge().value()).isZero();
        verify(upstream, times(1)).close(CloseStatus.NORMAL);
    }

    @Test
    void downstreamCloseCancelsPendingUpstreamHandshake() throws Exception {
        Fixture fixture = fixture(4, 1024);
        WebSocketSession client = clientSession("pending-cancel");
        fixture.handler.afterConnectionEstablished(client);

        fixture.handler.afterConnectionClosed(client, CloseStatus.NORMAL);

        assertThat(fixture.handshake.isCancelled()).isTrue();
        assertThat(fixture.metrics.activeSessions()).isZero();
    }

    @Test
    void handshakeDeadlineCancelsPendingConnectClosesClientAndReleases() throws Exception {
        Fixture fixture = fixture(4, 1024, 30L, new SettableListenableFuture<WebSocketSession>());
        WebSocketSession client = clientSession("handshake-timeout");
        fixture.handler.afterConnectionEstablished(client);

        verify(client, timeout(1000)).close(closeStatus(1011));

        assertThat(fixture.handshake.isCancelled()).isTrue();
        assertThat(fixture.metrics.activeSessions()).isZero();
    }

    @Test
    void upstreamConnectionThatArrivesAfterCancellationIsClosedWithoutReacquiringCapacity() throws Exception {
        LateCompletingFuture handshake = new LateCompletingFuture();
        Fixture fixture = fixture(4, 1024, 5000L, handshake);
        WebSocketSession client = clientSession("late-upstream");
        WebSocketSession upstream = websocketSession("late-upstream-server");
        fixture.handler.afterConnectionEstablished(client);

        fixture.handler.afterConnectionClosed(client, CloseStatus.NORMAL);
        assertThat(handshake.isCancelled()).isTrue();
        handshake.set(upstream);

        verify(upstream).close(CloseStatus.NORMAL);
        assertThat(fixture.metrics.activeSessions()).isZero();
    }

    @Test
    void repeatedFailureAndLateSuccessCallbacksReleaseCapacityOnlyOnce() throws Exception {
        LateCompletingFuture handshake = new LateCompletingFuture();
        Fixture fixture = fixture(4, 1024, 5000L, handshake);
        WebSocketSession client = clientSession("callback-race");
        WebSocketSession upstream = websocketSession("callback-race-upstream");
        fixture.handler.afterConnectionEstablished(client);
        Object proxySession = client.getAttributes().get("realtimeSpeechProxySession");

        fixture.handler.afterConnectionClosed(client, CloseStatus.NORMAL);
        ReflectionTestUtils.invokeMethod(proxySession, "onUpstreamConnectFailed", new IllegalStateException("late failure"));
        ReflectionTestUtils.invokeMethod(proxySession, "onUpstreamConnectFailed", new IllegalStateException("duplicate failure"));
        handshake.set(upstream);

        assertThat(fixture.metrics.activeSessions()).isZero();
        verify(upstream).close(CloseStatus.NORMAL);
    }

    @Test
    void runtimeFailureWhileSendingOverflowErrorStillClosesAndReleasesResources() throws Exception {
        Fixture fixture = fixture(4, 4);
        WebSocketSession client = clientSession("send-runtime");
        doThrow(new IllegalStateException("session is already closing"))
            .when(client).sendMessage(any());
        fixture.handler.afterConnectionEstablished(client);

        fixture.handler.handleMessage(client, new BinaryMessage(new byte[5]));

        verify(client).close(closeStatus(1009));
        assertThat(fixture.handshake.isCancelled()).isTrue();
        assertThat(fixture.metrics.activeSessions()).isZero();
        assertBufferCleared(client);
    }

    @Test
    void runtimeFailureWhileClosingUpstreamStillReleasesCapacity() throws Exception {
        Fixture fixture = fixture(4, 1024);
        WebSocketSession client = clientSession("close-runtime");
        WebSocketSession upstream = websocketSession("close-runtime-upstream");
        doThrow(new IllegalStateException("container already stopped"))
            .when(upstream).close(any(CloseStatus.class));
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);

        fixture.handler.afterConnectionClosed(client, CloseStatus.NORMAL);

        assertThat(fixture.metrics.activeSessions()).isZero();
        verify(upstream, times(1)).close(CloseStatus.NORMAL);
    }

    @Test
    void runtimeFailureSendingUpstreamInitializationClosesBothSidesAndReleases() throws Exception {
        Fixture fixture = fixture(4, 1024);
        WebSocketSession client = clientSession("init-send-runtime");
        WebSocketSession upstream = websocketSession("init-send-runtime-upstream");
        doThrow(new IllegalStateException("upstream is not writable"))
            .when(upstream).sendMessage(any());
        fixture.handler.afterConnectionEstablished(client);

        fixture.completeHandshake(upstream);

        verify(client).close(closeStatus(1011));
        verify(upstream).close(CloseStatus.SERVER_ERROR);
        assertThat(fixture.metrics.activeSessions()).isZero();
    }

    @Test
    void runtimeFailureSendingUpstreamAudioClosesBothSidesAndReleases() throws Exception {
        Fixture fixture = fixture(4, 1024);
        WebSocketSession client = clientSession("audio-send-runtime");
        WebSocketSession upstream = websocketSession("audio-send-runtime-upstream");
        doAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof BinaryMessage) {
                throw new IllegalStateException("upstream audio send rejected");
            }
            return null;
        }).when(upstream).sendMessage(any());
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);
        assertThat(fixture.metrics.activeSessions()).isEqualTo(1);

        fixture.handler.handleMessage(client, new BinaryMessage(new byte[16]));

        verify(client).close(closeStatus(1011));
        verify(upstream).close(CloseStatus.SERVER_ERROR);
        assertThat(fixture.metrics.activeSessions()).isZero();
    }

    @Test
    void downstreamSendFailureTerminatesBothSidesAndReleasesOnlyOnce() throws Exception {
        Fixture fixture = fixture(4, 1024);
        WebSocketSession client = clientSession("downstream-send-failed");
        WebSocketSession upstream = websocketSession("downstream-send-failed-upstream");
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);
        doThrow(new IOException("downstream is gone")).when(client).sendMessage(any());

        Object proxySession = proxySession(client);
        ReflectionTestUtils.invokeMethod(
            proxySession,
            "onUpstreamMessage",
            "{\"mode\":\"2pass-online\",\"text\":\"发热\",\"is_final\":false}"
        );

        verify(upstream).close(closeStatus(1011));
        verify(client).close(closeStatus(1011));
        assertThat(fixture.metrics.activeSessions()).isZero();

        fixture.handler.afterConnectionClosed(client, CloseStatus.SERVER_ERROR);
        ReflectionTestUtils.invokeMethod(proxySession, "onUpstreamClosed", CloseStatus.SERVER_ERROR);
        assertThat(fixture.metrics.activeSessions()).isZero();
        verify(upstream, atMost(1)).close(any(CloseStatus.class));
    }

    @Test
    void concurrentTerminalSignalsReleaseCapacityAndCloseUpstreamOnlyOnce() throws Exception {
        Fixture fixture = fixture(4, 1024);
        WebSocketSession client = clientSession("terminal-race");
        WebSocketSession upstream = websocketSession("terminal-race-upstream");
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);
        doThrow(new IOException("downstream is gone")).when(client).sendMessage(any());
        Object proxySession = proxySession(client);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> sendFailure = executor.submit(() -> {
                await(start);
                ReflectionTestUtils.invokeMethod(
                    proxySession,
                    "onUpstreamMessage",
                    "{\"mode\":\"2pass-online\",\"text\":\"咳嗽\",\"is_final\":false}"
                );
            });
            Future<?> transportFailure = executor.submit(() -> {
                await(start);
                fixture.handler.handleTransportError(client, new IOException("client transport failed"));
            });
            Future<?> upstreamFailure = executor.submit(() -> {
                await(start);
                ReflectionTestUtils.invokeMethod(
                    proxySession,
                    "terminateWithError",
                    "upstream failed",
                    "500",
                    new IOException("upstream failed")
                );
            });
            start.countDown();
            sendFailure.get(2, TimeUnit.SECONDS);
            transportFailure.get(2, TimeUnit.SECONDS);
            upstreamFailure.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(fixture.metrics.activeSessions()).isZero();
        verify(upstream, atMost(1)).close(any(CloseStatus.class));
    }

    @Test
    void shutdownClosesPendingProxySessionCancelsDeadlineAndReleasesCapacity() throws Exception {
        Fixture fixture = fixture(4, 1024);
        WebSocketSession client = clientSession("shutdown");
        fixture.handler.afterConnectionEstablished(client);

        fixture.handler.shutdownHandshakeScheduler();

        verify(client).close(closeStatus(1012));
        assertThat(fixture.handshake.isCancelled()).isTrue();
        assertThat(fixture.metrics.activeSessions()).isZero();
        assertThat(activeProxySessions(fixture.handler)).isEmpty();
        assertAllDeadlinesCleared(client);
    }

    @Test
    void shutdownClosesConnectedUpstreamAndDownstreamSessions() throws Exception {
        Fixture fixture = fixture(4, 1024);
        WebSocketSession client = clientSession("shutdown-connected");
        WebSocketSession upstream = websocketSession("shutdown-connected-upstream");
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);

        fixture.handler.shutdownHandshakeScheduler();

        verify(upstream).close(closeStatus(1012));
        verify(client).close(closeStatus(1012));
        assertThat(fixture.metrics.activeSessions()).isZero();
        assertThat(activeProxySessions(fixture.handler)).isEmpty();
        assertAllDeadlinesCleared(client);
    }

    @Test
    void taskStartDeadlineClosesConnectedSessionThatNeverBecomesReady() throws Exception {
        Fixture fixture = fixture(4, 1024, 5000L, 30L, 5000L, 5000L);
        when(fixture.configService.resolveByDevice(any(AiDevice.class))).thenReturn(dashScopeConfig());
        WebSocketSession client = clientSession("task-start-timeout");
        WebSocketSession upstream = websocketSession("task-start-timeout-upstream");
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);

        verify(client, timeout(1000)).close(closeStatus(1011));
        verify(upstream).close(closeStatus(1011));
        assertThat(fixture.metrics.activeSessions()).isZero();
        assertAllDeadlinesCleared(client);
    }

    @Test
    void funAsrUsesIdleWatchdogInsteadOfTaskStartDeadlineBeforeFirstResult() throws Exception {
        Fixture fixture = fixture(4, 1024, 5000L, 30L, 5000L, 5000L);
        WebSocketSession client = clientSession("funasr-silent-start");
        WebSocketSession upstream = websocketSession("funasr-silent-start-upstream");
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);

        Object proxySession = proxySession(client);
        assertThat(ReflectionTestUtils.getField(proxySession, "taskStartDeadline")).isNull();
        assertThat(ReflectionTestUtils.getField(proxySession, "idleDeadline")).isNotNull();
        fixture.handler.handleMessage(client, new BinaryMessage(new byte[320]));
        assertThat(fixture.metrics.activeSessions()).isEqualTo(1);

        fixture.handler.afterConnectionClosed(client, CloseStatus.NORMAL);
        assertThat(fixture.metrics.activeSessions()).isZero();
    }

    @Test
    void idleDeadlineStartsAfterFirstValidFunAsrResponse() throws Exception {
        Fixture fixture = fixture(4, 1024, 5000L, 5000L, 30L, 5000L);
        WebSocketSession client = clientSession("idle-timeout");
        WebSocketSession upstream = websocketSession("idle-timeout-upstream");
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);

        Object proxySession = proxySession(client);
        Object outboundCall = ReflectionTestUtils.getField(proxySession, "outboundCall");
        assertThat(ReflectionTestUtils.getField(outboundCall, "completed")).isEqualTo(false);
        ReflectionTestUtils.invokeMethod(
            proxySession,
            "onUpstreamMessage",
            "{\"mode\":\"2pass-online\",\"text\":\"\",\"is_final\":false}"
        );
        assertThat(ReflectionTestUtils.getField(outboundCall, "completed")).isEqualTo(true);

        verify(client, timeout(1000)).close(closeStatus(1011));
        verify(upstream).close(closeStatus(1011));
        assertThat(fixture.metrics.activeSessions()).isZero();
        assertAllDeadlinesCleared(client);
    }

    @Test
    void staleIdleCallbackWaitingForSessionLockCannotCloseRenewedSession() throws Exception {
        Fixture fixture = fixture(4, 1024, 5000L, 5000L, 5000L, 5000L);
        WebSocketSession client = clientSession("idle-generation-race");
        WebSocketSession upstream = websocketSession("idle-generation-race-upstream");
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);
        Object proxySession = proxySession(client);
        long expiredGeneration = (Long) ReflectionTestUtils.getField(proxySession, "idleGeneration");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        AtomicReference<Thread> callbackThread = new AtomicReference<Thread>();
        Future<?> staleCallback;
        try {
            synchronized (proxySession) {
                staleCallback = executor.submit(() -> {
                    callbackThread.set(Thread.currentThread());
                    callbackStarted.countDown();
                    ReflectionTestUtils.invokeMethod(proxySession, "onIdleTimeout", expiredGeneration);
                });
                await(callbackStarted);
                awaitBlocked(callbackThread.get());

                fixture.handler.handleMessage(client, new BinaryMessage(new byte[16]));
                assertThat((Long) ReflectionTestUtils.getField(proxySession, "idleGeneration"))
                    .isGreaterThan(expiredGeneration);
            }
            staleCallback.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        verify(client, never()).close(any(CloseStatus.class));
        verify(upstream, never()).close(any(CloseStatus.class));
        assertThat(fixture.metrics.activeSessions()).isEqualTo(1);

        fixture.handler.afterConnectionClosed(client, CloseStatus.NORMAL);
        assertThat(fixture.metrics.activeSessions()).isZero();
    }

    @Test
    void finalDeadlineClosesSessionWhenUpstreamNeverFinishes() throws Exception {
        Fixture fixture = fixture(4, 1024, 5000L, 5000L, 5000L, 30L);
        WebSocketSession client = clientSession("final-timeout");
        WebSocketSession upstream = websocketSession("final-timeout-upstream");
        fixture.handler.afterConnectionEstablished(client);
        fixture.completeHandshake(upstream);
        Object proxySession = proxySession(client);
        ReflectionTestUtils.invokeMethod(
            proxySession,
            "onUpstreamMessage",
            "{\"mode\":\"2pass-online\",\"text\":\"\",\"is_final\":false}"
        );

        fixture.handler.handleMessage(client, new TextMessage("{\"type\":\"finish\"}"));

        verify(client, timeout(1000)).close(closeStatus(1011));
        verify(upstream).close(closeStatus(1011));
        assertThat(fixture.metrics.activeSessions()).isZero();
        assertAllDeadlinesCleared(client);
    }

    private Fixture fixture(int maximumSessions, int maximumBufferedBytes) {
        return fixture(
            maximumSessions,
            maximumBufferedBytes,
            5000L,
            new SettableListenableFuture<WebSocketSession>()
        );
    }

    private Fixture fixture(int maximumSessions,
                            int maximumBufferedBytes,
                            long handshakeTimeoutMillis,
                            ListenableFuture<WebSocketSession> handshake) {
        return fixture(
            maximumSessions,
            maximumBufferedBytes,
            handshakeTimeoutMillis,
            handshakeTimeoutMillis,
            60000L,
            15000L,
            handshake
        );
    }

    private Fixture fixture(int maximumSessions,
                            int maximumBufferedBytes,
                            long handshakeTimeoutMillis,
                            long taskStartTimeoutMillis,
                            long idleTimeoutMillis,
                            long finalTimeoutMillis) {
        return fixture(
            maximumSessions,
            maximumBufferedBytes,
            handshakeTimeoutMillis,
            taskStartTimeoutMillis,
            idleTimeoutMillis,
            finalTimeoutMillis,
            new SettableListenableFuture<WebSocketSession>()
        );
    }

    private Fixture fixture(int maximumSessions,
                            int maximumBufferedBytes,
                            long handshakeTimeoutMillis,
                            long taskStartTimeoutMillis,
                            long idleTimeoutMillis,
                            long finalTimeoutMillis,
                            ListenableFuture<WebSocketSession> handshake) {
        ConfigService configService = mock(ConfigService.class);
        when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(funAsrConfig());
        WebSocketClient webSocketClient = mock(WebSocketClient.class);
        when(webSocketClient.doHandshake(
            any(WebSocketHandler.class),
            any(WebSocketHttpHeaders.class),
            any(URI.class)
        )).thenReturn(handshake);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RealtimeSpeechMetrics metrics = new RealtimeSpeechMetrics(registry);
        RealtimeSpeechWebSocketHandler handler = new RealtimeSpeechWebSocketHandler(
            configService,
            new ObjectMapper(),
            new OutboundSecurityService(new OutboundSecurityProperties()),
            metrics,
            maximumSessions,
            maximumBufferedBytes,
            handshakeTimeoutMillis,
            taskStartTimeoutMillis,
            idleTimeoutMillis,
            finalTimeoutMillis,
            webSocketClient,
            handshakeScheduler
        );
        return new Fixture(handler, configService, metrics, registry, handshake);
    }

    private ResolvedAiConfig funAsrConfig() {
        ResolvedAiConfig config = new ResolvedAiConfig();
        config.setSpeechProvider("funasr-websocket");
        config.setSpeechModel("funasr-2pass");
        config.setSpeechRealtimeUrl("ws://127.0.0.1:10095");
        return config;
    }

    private ResolvedAiConfig dashScopeConfig() {
        ResolvedAiConfig config = new ResolvedAiConfig();
        config.setSpeechProvider("aliyun-dashscope");
        config.setSpeechModel("qwen-audio-3.0-asr-flash-streaming");
        config.setSpeechRealtimeUrl("wss://dashscope.aliyuncs.com/api-ws/v1/inference");
        config.setAudioApiKey("test-api-key");
        return config;
    }

    private WebSocketSession clientSession(String id) throws Exception {
        WebSocketSession session = websocketSession(id);
        AiDevice device = new AiDevice();
        device.setIdDevice("device-" + id);
        session.getAttributes().put(RealtimeSpeechHandshakeInterceptor.DEVICE_ATTRIBUTE, device);
        return session;
    }

    private WebSocketSession websocketSession(String id) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();
        AtomicBoolean open = new AtomicBoolean(true);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(invocation -> {
            open.set(false);
            return null;
        }).when(session).close(any(CloseStatus.class));
        return session;
    }

    @SuppressWarnings("unchecked")
    private void assertBufferCleared(WebSocketSession client) {
        Object proxySession = client.getAttributes().get("realtimeSpeechProxySession");
        assertThat(proxySession).isNotNull();
        List<byte[]> buffer = (List<byte[]>) ReflectionTestUtils.getField(proxySession, "audioBuffer");
        assertThat(buffer).isEmpty();
        assertThat(ReflectionTestUtils.getField(proxySession, "bufferedAudioBytes")).isEqualTo(0L);
    }

    private CloseStatus closeStatus(int code) {
        return org.mockito.ArgumentMatchers.argThat(status -> status != null && status.getCode() == code);
    }

    private Object proxySession(WebSocketSession client) {
        return client.getAttributes().get("realtimeSpeechProxySession");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> activeProxySessions(RealtimeSpeechWebSocketHandler handler) {
        return (Map<String, Object>) ReflectionTestUtils.getField(handler, "activeProxySessions");
    }

    private void assertAllDeadlinesCleared(WebSocketSession client) {
        Object proxySession = proxySession(client);
        assertThat(ReflectionTestUtils.getField(proxySession, "handshakeDeadline")).isNull();
        assertThat(ReflectionTestUtils.getField(proxySession, "taskStartDeadline")).isNull();
        assertThat(ReflectionTestUtils.getField(proxySession, "idleDeadline")).isNull();
        assertThat(ReflectionTestUtils.getField(proxySession, "finalDeadline")).isNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("terminal race did not start in time");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("idle callback did not block on the proxy session monitor");
    }

    private static final class Fixture {

        private final RealtimeSpeechWebSocketHandler handler;
        private final ConfigService configService;
        private final RealtimeSpeechMetrics metrics;
        private final SimpleMeterRegistry registry;
        private final ListenableFuture<WebSocketSession> handshake;

        private Fixture(RealtimeSpeechWebSocketHandler handler,
                        ConfigService configService,
                        RealtimeSpeechMetrics metrics,
                        SimpleMeterRegistry registry,
                        ListenableFuture<WebSocketSession> handshake) {
            this.handler = handler;
            this.configService = configService;
            this.metrics = metrics;
            this.registry = registry;
            this.handshake = handshake;
        }

        @SuppressWarnings("unchecked")
        private void completeHandshake(WebSocketSession session) {
            ((SettableListenableFuture<WebSocketSession>) handshake).set(session);
        }

        @SuppressWarnings("unchecked")
        private void failHandshake(Throwable error) {
            ((SettableListenableFuture<WebSocketSession>) handshake).setException(error);
        }
    }

    private static final class LateCompletingFuture extends SettableListenableFuture<WebSocketSession> {

        private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancellationRequested.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancellationRequested.get();
        }
    }
}
