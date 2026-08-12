package com.regionalai.floatingball.server.modules.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.metrics.AiProxyMetrics;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.ai.dto.ChatRequest;
import com.regionalai.floatingball.server.modules.audit.service.AuditService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiProxyStreamCancellationIntegrationTest {

    @Test
    void completionBeforeResponseHeadersAbortsRequestAndReleasesCapacity() throws Exception {
        assertDelayedResponseHeaderRequestCancelled("completionCallback");
    }

    @Test
    void timeoutBeforeResponseHeadersAbortsRequestAndReleasesCapacity() throws Exception {
        assertDelayedResponseHeaderRequestCancelled("timeoutCallback");
    }

    @Test
    void downstreamSendFailureAbortsUpstreamWithoutOpeningCircuit() throws Exception {
        CountDownLatch frameWritten = new CountDownLatch(1);
        CountDownLatch releaseUpstream = new CountDownLatch(1);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/v1/chat/completions", exchange -> streamingBody(
            exchange,
            frameWritten,
            releaseUpstream
        ));
        server.start();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(1);
        connectionManager.setDefaultMaxPerRoute(1);
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(1000)
            .setConnectionRequestTimeout(500)
            .setSocketTimeout(30000)
            .build();
        CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .build();
        ThreadPoolTaskExecutor streamExecutor = new ThreadPoolTaskExecutor();
        streamExecutor.setCorePoolSize(1);
        streamExecutor.setMaxPoolSize(1);
        streamExecutor.setQueueCapacity(0);
        streamExecutor.initialize();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            String endpoint = baseUrl + "/chat/completions";
            ConfigService configService = mock(ConfigService.class);
            AuditService auditService = mock(AuditService.class);
            ResolvedAiConfig resolved = new ResolvedAiConfig();
            resolved.setBaseUrl(baseUrl);
            resolved.setApiKey("test-key");
            resolved.setModel("test-model");
            when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(resolved);
            OutboundSecurityProperties outboundProperties = new OutboundSecurityProperties();
            outboundProperties.setCircuitFailureThreshold(1);
            outboundProperties.setCircuitOpenMs(60000L);
            OutboundSecurityService outboundSecurity = new OutboundSecurityService(outboundProperties);

            AiProxyService service = new AiProxyService(
                configService,
                auditService,
                new RestTemplate(),
                new ObjectMapper(),
                outboundSecurity,
                streamExecutor,
                AiProxyMetrics.noop(),
                httpClient
            );
            ChatRequest request = new ChatRequest();
            request.setStream(Boolean.TRUE);
            request.setMessages(Collections.emptyList());
            AiDevice device = new AiDevice();
            Object upstreamConfig = ReflectionTestUtils.invokeMethod(service, "resolveChatConfig", device, request);
            Object lifecycle = newStreamLifecycle(streamExecutor);

            Boolean succeeded = ReflectionTestUtils.invokeMethod(
                service,
                "streamChat",
                device,
                request,
                upstreamConfig,
                new DisconnectingSseEmitter(),
                lifecycle
            );

            assertThat(frameWritten.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(succeeded).isFalse();
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(lifecycle, "isCancelled")).isTrue();
            assertThat(awaitCondition(() -> connectionManager.getTotalStats().getLeased() == 0, 2000L)).isTrue();
            assertThat(outboundSecurity.acquireHttp(endpoint, "ai-chat-stream")).isNotNull();
            verifyNoInteractions(auditService);
        } finally {
            releaseUpstream.countDown();
            streamExecutor.shutdown();
            httpClient.close();
            connectionManager.close();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    private void assertDelayedResponseHeaderRequestCancelled(String callbackField) throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch allowResponseHeaders = new CountDownLatch(1);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/v1/chat/completions", exchange -> delayedHeaders(
            exchange,
            requestArrived,
            allowResponseHeaders
        ));
        server.createContext("/fast", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(1);
        connectionManager.setDefaultMaxPerRoute(1);
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(1000)
            .setConnectionRequestTimeout(500)
            .setSocketTimeout(30000)
            .build();
        CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .build();
        ThreadPoolTaskExecutor streamExecutor = new ThreadPoolTaskExecutor();
        streamExecutor.setCorePoolSize(1);
        streamExecutor.setMaxPoolSize(1);
        streamExecutor.setQueueCapacity(0);
        streamExecutor.setThreadNamePrefix("stream-cancel-it-");
        streamExecutor.initialize();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ConfigService configService = mock(ConfigService.class);
            ResolvedAiConfig resolved = new ResolvedAiConfig();
            resolved.setBaseUrl(baseUrl);
            resolved.setApiKey("test-key");
            resolved.setModel("test-model");
            when(configService.resolveByDevice(any(AiDevice.class))).thenReturn(resolved);

            AiProxyService service = new AiProxyService(
                configService,
                mock(AuditService.class),
                new RestTemplate(),
                new ObjectMapper(),
                new OutboundSecurityService(new OutboundSecurityProperties()),
                streamExecutor,
                AiProxyMetrics.noop(),
                httpClient
            );
            ChatRequest request = new ChatRequest();
            request.setStream(Boolean.TRUE);
            request.setMessages(Collections.emptyList());

            SseEmitter emitter = service.chatStream(new AiDevice(), request);
            assertThat(requestArrived.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(connectionManager.getTotalStats().getLeased()).isEqualTo(1);
            assertThat(streamExecutor.getActiveCount()).isEqualTo(1);

            Runnable callback = (Runnable) ReflectionTestUtils.getField(emitter, callbackField);
            assertThat(callback).isNotNull();
            callback.run();

            assertThat(awaitCondition(() -> connectionManager.getTotalStats().getLeased() == 0
                && streamExecutor.getActiveCount() == 0, 2000L)).isTrue();

            try (CloseableHttpResponse response = httpClient.execute(
                new HttpGet("http://127.0.0.1:" + server.getAddress().getPort() + "/fast")
            )) {
                assertThat(response.getStatusLine().getStatusCode()).isEqualTo(204);
            }
        } finally {
            allowResponseHeaders.countDown();
            streamExecutor.shutdown();
            httpClient.close();
            connectionManager.close();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    private void delayedHeaders(HttpExchange exchange,
                                CountDownLatch requestArrived,
                                CountDownLatch allowResponseHeaders) throws IOException {
        try {
            drain(exchange.getRequestBody());
            requestArrived.countDown();
            try {
                allowResponseHeaders.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private void streamingBody(HttpExchange exchange,
                               CountDownLatch frameWritten,
                               CountDownLatch releaseUpstream) throws IOException {
        try {
            drain(exchange.getRequestBody());
            byte[] firstFrame = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(firstFrame);
            exchange.getResponseBody().flush();
            frameWritten.countDown();
            try {
                releaseUpstream.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        } finally {
            exchange.close();
        }
    }

    private Object newStreamLifecycle(AsyncTaskExecutor executor) throws Exception {
        Class<?> lifecycleClass = Class.forName(
            "com.regionalai.floatingball.server.modules.ai.service.AiProxyService$StreamLifecycle"
        );
        Constructor<?> constructor = lifecycleClass.getDeclaredConstructor(AsyncTaskExecutor.class);
        constructor.setAccessible(true);
        return constructor.newInstance(executor);
    }

    private void drain(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[512];
        while (inputStream.read(buffer) >= 0) {
            // Consume the small request body so the handler is definitely waiting on response headers.
        }
    }

    private boolean awaitCondition(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }

    private static final class DisconnectingSseEmitter extends SseEmitter {

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("client disconnected");
        }
    }
}
