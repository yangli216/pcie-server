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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
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
}
