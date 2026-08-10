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
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
        verify(upstream, times(2)).close(CloseStatus.NORMAL);
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
