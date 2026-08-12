package com.regionalai.floatingball.server.common.capacity;

import com.regionalai.floatingball.server.common.config.AiBlockingExecutorConfig;
import com.regionalai.floatingball.server.common.config.AiHttpClientProperties;
import com.regionalai.floatingball.server.common.config.RestTemplateConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in local capacity regression for the standalone deployment baseline.
 *
 * <p>This test deliberately exercises the real bounded Spring executor and Apache
 * HTTP connection pool against a delayed loopback upstream. It is not an
 * end-to-end substitute for signed requests, a production database, Nginx, or
 * host-level memory/FD measurements.</p>
 */
class AiCapacitySimulationTest {

    private static final int STANDALONE_POOL_SIZE = 80;
    private static final int STANDALONE_QUEUE_CAPACITY = 8;
    private static final int STANDALONE_HTTP_MAX_TOTAL = 160;
    private static final int STANDALONE_HTTP_MAX_PER_ROUTE = 144;
    private static final int STANDALONE_HTTP_READ_TIMEOUT_MS = 120_000;
    private static final int HEALTHY_ATTEMPTS = STANDALONE_POOL_SIZE;
    private static final int OVERLOAD_ATTEMPTS = 160;
    private static final long DEFAULT_UPSTREAM_DELAY_MS = 2_000L;
    private static final long DEFAULT_HEALTHY_WAVE_SPREAD_MS = 1_000L;
    private final List<ch.qos.logback.classic.Logger> quietLoggers = new ArrayList<>();
    private final List<ch.qos.logback.classic.Level> previousLogLevels = new ArrayList<>();

    @BeforeEach
    void requireExplicitOptIn() {
        assumeTrue(Boolean.getBoolean("pcie.capacity.simulation"),
            "Enable with -Dpcie.capacity.simulation=true");
        silenceLogger("org.springframework.web.client");
        silenceLogger("org.springframework.web.HttpLogging");
        silenceLogger("org.springframework.scheduling.concurrent");
        silenceLogger("org.apache.http");
    }

    @AfterEach
    void restoreLogging() {
        for (int index = 0; index < quietLoggers.size(); index++) {
            quietLoggers.get(index).setLevel(previousLogLevels.get(index));
        }
        quietLoggers.clear();
        previousLogLevels.clear();
    }

    private void silenceLogger(String name) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
        quietLoggers.add(logger);
        previousLogLevels.add(logger.getLevel());
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
    }

    @Test
    void singleNode_completesWorkWithinBoundedCapacityWithRealHttpPool() throws Exception {
        long delayMillis = configuredDelayMillis();
        try (DelayedUpstream upstream = new DelayedUpstream(delayMillis);
             NodeHarness node = new NodeHarness(upstream.url())) {
            ScenarioResult result = runScenario(
                "single-node-healthy",
                node,
                upstream,
                new int[]{HEALTHY_ATTEMPTS},
                new long[]{0L},
                configuredHealthyWaveSpreadMillis()
            );

            assertHealthy(result, HEALTHY_ATTEMPTS);
            assertThat(result.peakActive).isLessThanOrEqualTo(STANDALONE_POOL_SIZE);
            assertThat(result.peakLeased).isLessThanOrEqualTo(STANDALONE_POOL_SIZE);
            assertThat(result.peakPending).isZero();
            assertReleased(result);
        }
    }

    @Test
    void singleNode_rejectsOverflowFastAndRecovers() throws Exception {
        long delayMillis = Math.max(configuredDelayMillis(), 5_000L);
        long waveSpreadMillis = configuredHealthyWaveSpreadMillis();
        assertThat(waveSpreadMillis)
            .as("single-node overload wave must finish before delayed tasks can complete")
            .isLessThan(delayMillis);
        try (DelayedUpstream upstream = new DelayedUpstream(delayMillis);
             NodeHarness node = new NodeHarness(upstream.url())) {
            ScenarioResult overloaded = runScenario(
                "single-node-overload",
                node,
                upstream,
                new int[]{OVERLOAD_ATTEMPTS},
                new long[]{0L},
                waveSpreadMillis
            );

            assertThat(overloaded.accepted).isEqualTo(STANDALONE_POOL_SIZE + STANDALONE_QUEUE_CAPACITY);
            assertThat(overloaded.completed).isEqualTo(STANDALONE_POOL_SIZE + STANDALONE_QUEUE_CAPACITY);
            assertThat(overloaded.rejected).isEqualTo(
                OVERLOAD_ATTEMPTS - STANDALONE_POOL_SIZE - STANDALONE_QUEUE_CAPACITY);
            assertThat(overloaded.failed).isZero();
            assertThat(overloaded.submissionMillis).isLessThan(delayMillis);
            assertThat(overloaded.peakActive).isEqualTo(STANDALONE_POOL_SIZE);
            assertThat(overloaded.peakQueue).isEqualTo(STANDALONE_QUEUE_CAPACITY);
            assertThat(overloaded.peakPending).isZero();
            assertReleased(overloaded);

            ScenarioResult recovered = runScenario(
                "single-node-recovery",
                node,
                upstream,
                new int[]{STANDALONE_POOL_SIZE},
                new long[]{0L},
                waveSpreadMillis
            );
            assertHealthy(recovered, STANDALONE_POOL_SIZE);
            assertReleased(recovered);
        }
    }

    @Test
    void singleNode_releasesResourcesAt120SecondReadTimeout() throws Exception {
        assumeTrue(Boolean.getBoolean("pcie.capacity.timeout-boundary-simulation"),
            "Enable the long boundary run with -Dpcie.capacity.timeout-boundary-simulation=true");
        long upstreamDelayMillis = STANDALONE_HTTP_READ_TIMEOUT_MS + 5_000L;
        try (DelayedUpstream upstream = new DelayedUpstream(upstreamDelayMillis);
             NodeHarness node = new NodeHarness(upstream.url())) {
            ScenarioResult timedOut = runScenario(
                "single-node-read-timeout-120s",
                node,
                upstream,
                new int[]{HEALTHY_ATTEMPTS},
                new long[]{0L},
                configuredHealthyWaveSpreadMillis()
            );

            assertThat(timedOut.accepted).isEqualTo(HEALTHY_ATTEMPTS);
            assertThat(timedOut.completed).isZero();
            assertThat(timedOut.rejected).isZero();
            assertThat(timedOut.failed).isEqualTo(HEALTHY_ATTEMPTS);
            assertThat(timedOut.p95Millis).isBetween(115_000L, 130_000L);
            assertThat(timedOut.peakActive).isLessThanOrEqualTo(STANDALONE_POOL_SIZE);
            assertThat(timedOut.peakLeased).isLessThanOrEqualTo(STANDALONE_POOL_SIZE);
            assertThat(timedOut.peakPending).isZero();
            assertReleased(timedOut);
        }
    }

    private static long configuredDelayMillis() {
        return Math.max(100L, Long.getLong(
            "pcie.capacity.upstream-delay-ms", DEFAULT_UPSTREAM_DELAY_MS));
    }

    private static long configuredHealthyWaveSpreadMillis() {
        return Math.max(0L, Long.getLong(
            "pcie.capacity.healthy-wave-spread-ms", DEFAULT_HEALTHY_WAVE_SPREAD_MS));
    }

    private static ScenarioResult runScenario(String name,
                                              NodeHarness node,
                                              DelayedUpstream upstream,
                                              int[] waveSizes,
                                              long[] pausesBeforeWaveMillis,
                                              long waveSpreadMillis) throws Exception {
        assertThat(waveSizes).hasSameSizeAs(pausesBeforeWaveMillis);
        node.resetPeaks();
        upstream.resetPeak();
        node.printState("before-" + name);

        int attempts = Arrays.stream(waveSizes).sum();
        CountDownLatch outcomes = new CountDownLatch(attempts);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong maxSubmitMicros = new AtomicLong();
        ConcurrentLinkedQueue<Long> endToEndLatenciesMillis = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> serviceLatenciesMillis = new ConcurrentLinkedQueue<>();
        long completedTasksBefore = node.completedTaskCount();

        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(
            daemonThreadFactory("capacity-monitor-"));
        monitor.scheduleAtFixedRate(node::observePool, 0L, 5L, TimeUnit.MILLISECONDS);

        long submissionStarted = System.nanoTime();
        try {
            for (int wave = 0; wave < waveSizes.length; wave++) {
                if (pausesBeforeWaveMillis[wave] > 0L) {
                    Thread.sleep(pausesBeforeWaveMillis[wave]);
                }
                long waveStarted = System.nanoTime();
                for (int request = 0; request < waveSizes[wave]; request++) {
                    long submitStarted = System.nanoTime();
                    long requestSubmitted = System.nanoTime();
                    try {
                        node.execute(() -> {
                            long requestStarted = System.nanoTime();
                            try {
                                node.restTemplate.postForObject(node.upstreamUrl, "{}", String.class);
                                completed.incrementAndGet();
                            } catch (RuntimeException ex) {
                                failed.incrementAndGet();
                            } finally {
                                serviceLatenciesMillis.add(elapsedMillis(requestStarted));
                                endToEndLatenciesMillis.add(elapsedMillis(requestSubmitted));
                                outcomes.countDown();
                            }
                        });
                        accepted.incrementAndGet();
                    } catch (TaskRejectedException ex) {
                        if (rejected.incrementAndGet() == 1) {
                            System.out.printf(Locale.ROOT,
                                "CAPACITY_REJECTION {\"scenario\":\"%s\",\"message\":\"%s\",\"state\":\"%s\"}%n",
                                name,
                                String.valueOf(ex.getMessage()).replace("\"", "'"),
                                node.state().replace("\"", "'"));
                        }
                        outcomes.countDown();
                    } finally {
                        updateMax(maxSubmitMicros,
                            TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - submitStarted));
                    }
                    paceWave(waveStarted, request + 1, waveSizes[wave], waveSpreadMillis);
                }
            }
        } catch (Exception ex) {
            monitor.shutdownNow();
            throw ex;
        } catch (Error error) {
            monitor.shutdownNow();
            throw error;
        } finally {
            node.observePool();
        }
        long submissionMillis = elapsedMillis(submissionStarted);
        long completionTimeoutMillis = Math.max(30_000L, upstream.delayMillis * 2L + 10_000L);
        boolean finished;
        try {
            finished = outcomes.await(completionTimeoutMillis, TimeUnit.MILLISECONDS);
        } finally {
            monitor.shutdownNow();
        }
        assertThat(finished).as("all simulated requests must reach a terminal outcome").isTrue();
        awaitExecutorBookkeeping(node, completedTasksBefore, accepted.get(), 5_000L);
        awaitReleased(node, 5_000L);
        node.observePool();

        ScenarioResult result = new ScenarioResult(
            name,
            attempts,
            accepted.get(),
            completed.get(),
            rejected.get(),
            failed.get(),
            submissionMillis,
            maxSubmitMicros.get(),
            percentile95(endToEndLatenciesMillis),
            percentile95(serviceLatenciesMillis),
            upstream.peakActive.get(),
            node.peakActive.get(),
            node.peakQueue.get(),
            node.peakLeased.get(),
            node.peakPending.get(),
            node.active.get(),
            node.queuedNow(),
            node.leasedNow(),
            node.pendingNow(),
            node.availableNow()
        );
        result.print();
        return result;
    }

    private static void paceWave(long waveStartedNanos,
                                 int dispatched,
                                 int waveSize,
                                 long waveSpreadMillis) {
        if (waveSpreadMillis <= 0L || waveSize <= 0) {
            return;
        }
        long targetOffset = TimeUnit.MILLISECONDS.toNanos(waveSpreadMillis) * dispatched / waveSize;
        long remaining = waveStartedNanos + targetOffset - System.nanoTime();
        if (remaining > 0L) {
            LockSupport.parkNanos(remaining);
        }
    }

    private static void assertHealthy(ScenarioResult result, int expected) {
        assertThat(result.attempts).isEqualTo(expected);
        assertThat(result.accepted).isEqualTo(expected);
        assertThat(result.completed).isEqualTo(expected);
        assertThat(result.rejected).isZero();
        assertThat(result.failed).isZero();
        assertThat(result.availableAfter).isGreaterThan(0);
    }

    private static void assertReleased(ScenarioResult result) {
        assertThat(result.activeAfter).isZero();
        assertThat(result.queuedAfter).isZero();
        assertThat(result.leasedAfter).isZero();
        assertThat(result.pendingAfter).isZero();
    }

    private static void awaitReleased(NodeHarness node, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (node.isReleased()) {
                return;
            }
            Thread.sleep(20L);
        }
    }

    private static void awaitExecutorBookkeeping(NodeHarness node,
                                                 long completedBefore,
                                                 int accepted,
                                                 long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (node.completedTaskCount() >= completedBefore + accepted) {
                return;
            }
            Thread.sleep(20L);
        }
        assertThat(node.completedTaskCount())
            .as("executor completed-task counters must include every accepted request")
            .isGreaterThanOrEqualTo(completedBefore + accepted);
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static long percentile95(ConcurrentLinkedQueue<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95D) - 1);
        return sorted.get(index);
    }

    private static void updateMax(AtomicInteger target, int candidate) {
        int current = target.get();
        while (candidate > current && !target.compareAndSet(current, candidate)) {
            current = target.get();
        }
    }

    private static void updateMax(AtomicLong target, long candidate) {
        long current = target.get();
        while (candidate > current && !target.compareAndSet(current, candidate)) {
            current = target.get();
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class NodeHarness implements AutoCloseable {
        private final String upstreamUrl;
        private final ThreadPoolTaskExecutor executor;
        private final PoolingHttpClientConnectionManager connectionManager;
        private final CloseableHttpClient httpClient;
        private final RestTemplate restTemplate;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peakActive = new AtomicInteger();
        private final AtomicInteger peakQueue = new AtomicInteger();
        private final AtomicInteger peakLeased = new AtomicInteger();
        private final AtomicInteger peakPending = new AtomicInteger();

        private NodeHarness(String upstreamUrl) {
            this.upstreamUrl = upstreamUrl;
            AiHttpClientProperties properties = new AiHttpClientProperties();
            properties.setMaxTotal(STANDALONE_HTTP_MAX_TOTAL);
            properties.setMaxPerRoute(STANDALONE_HTTP_MAX_PER_ROUTE);
            properties.setConnectionRequestTimeoutMs(500);
            properties.setValidateAfterInactivityMs(5_000);
            properties.setIdleEvictSeconds(30);

            RestTemplateConfig restTemplateConfig = new RestTemplateConfig();
            this.connectionManager = restTemplateConfig.aiHttpConnectionManager(properties);
            assertThat(connectionManager.getMaxTotal()).isEqualTo(STANDALONE_HTTP_MAX_TOTAL);
            assertThat(connectionManager.getDefaultMaxPerRoute())
                .isEqualTo(STANDALONE_HTTP_MAX_PER_ROUTE);
            this.httpClient = restTemplateConfig.aiHttpClient(
                connectionManager, properties, 5_000, STANDALONE_HTTP_READ_TIMEOUT_MS,
                false, "", 7_890, "", "");
            HttpComponentsClientHttpRequestFactory requestFactory =
                restTemplateConfig.aiHttpRequestFactory(
                    httpClient, properties, 5_000, STANDALONE_HTTP_READ_TIMEOUT_MS);
            this.restTemplate = restTemplateConfig.restTemplate(new RestTemplateBuilder(), requestFactory);
            this.executor = new AiBlockingExecutorConfig().aiBlockingExecutor(
                STANDALONE_POOL_SIZE, STANDALONE_QUEUE_CAPACITY, 60L);
            this.executor.setThreadNamePrefix("capacity-single-node-");
            java.util.concurrent.ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            assertThat(pool.getCorePoolSize()).isEqualTo(STANDALONE_POOL_SIZE);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(STANDALONE_POOL_SIZE);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(STANDALONE_QUEUE_CAPACITY);
        }

        void execute(Runnable task) {
            executor.execute(() -> {
                int activeNow = active.incrementAndGet();
                updateMax(peakActive, activeNow);
                observePool();
                try {
                    task.run();
                } finally {
                    active.decrementAndGet();
                    observePool();
                }
            });
            observePool();
        }

        void observePool() {
            updateMax(peakQueue, queuedNow());
            updateMax(peakLeased, leasedNow());
            updateMax(peakPending, pendingNow());
        }

        void resetPeaks() {
            peakActive.set(active.get());
            peakQueue.set(queuedNow());
            peakLeased.set(leasedNow());
            peakPending.set(pendingNow());
        }

        int queuedNow() {
            return executor.getThreadPoolExecutor().getQueue().size();
        }

        int executorActiveNow() {
            return executor.getActiveCount();
        }

        long completedTaskCount() {
            return executor.getThreadPoolExecutor().getCompletedTaskCount();
        }

        void printState(String label) {
            System.out.printf(Locale.ROOT,
                "CAPACITY_EXECUTOR_STATE {\"label\":\"%s\",\"state\":\"%s\"}%n",
                label, state().replace("\"", "'"));
        }

        boolean isReleased() {
            return active.get() == 0 && executorActiveNow() == 0 && queuedNow() == 0
                && leasedNow() == 0 && pendingNow() == 0;
        }

        String state() {
            java.util.concurrent.ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            return "pool=" + pool.getPoolSize()
                + ",active=" + pool.getActiveCount()
                + ",queued=" + pool.getQueue().size()
                + ",completed=" + pool.getCompletedTaskCount()
                + ",largest=" + pool.getLargestPoolSize()
                + ",core=" + pool.getCorePoolSize()
                + ",max=" + pool.getMaximumPoolSize()
                + ",keepAliveMs=" + pool.getKeepAliveTime(TimeUnit.MILLISECONDS)
                + ",coreTimeout=" + pool.allowsCoreThreadTimeOut()
                + ",httpMaxTotal=" + connectionManager.getMaxTotal()
                + ",httpMaxPerRoute=" + connectionManager.getDefaultMaxPerRoute()
                + ",shutdown=" + pool.isShutdown()
                + ",terminating=" + pool.isTerminating()
                + ",terminated=" + pool.isTerminated();
        }

        int leasedNow() {
            return connectionManager.getTotalStats().getLeased();
        }

        int pendingNow() {
            return connectionManager.getTotalStats().getPending();
        }

        int availableNow() {
            return connectionManager.getTotalStats().getAvailable();
        }

        @Override
        public void close() throws Exception {
            executor.shutdown();
            httpClient.close();
            connectionManager.close();
        }
    }

    private static final class DelayedUpstream implements AutoCloseable {
        private static final byte[] RESPONSE = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

        private final HttpServer server;
        private final ExecutorService executor;
        private final long delayMillis;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peakActive = new AtomicInteger();

        private DelayedUpstream(long delayMillis) throws IOException {
            this.delayMillis = delayMillis;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
            this.executor = Executors.newFixedThreadPool(256, daemonThreadFactory("capacity-upstream-"));
            server.createContext("/v1/chat/completions", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
        }

        private void handle(HttpExchange exchange) throws IOException {
            int activeNow = active.incrementAndGet();
            updateMax(peakActive, activeNow);
            try {
                drain(exchange.getRequestBody());
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, RESPONSE.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(RESPONSE);
                }
            } finally {
                active.decrementAndGet();
                exchange.close();
            }
        }

        private void resetPeak() {
            peakActive.set(active.get());
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        private static void drain(InputStream input) throws IOException {
            try (InputStream source = input) {
                byte[] buffer = new byte[256];
                while (source.read(buffer) >= 0) {
                    // Drain the request body so the HTTP connection remains reusable.
                }
            }
        }
    }

    private static final class ScenarioResult {
        private final String name;
        private final int attempts;
        private final int accepted;
        private final int completed;
        private final int rejected;
        private final int failed;
        private final long submissionMillis;
        private final long maxSubmitMicros;
        private final long p95Millis;
        private final long serviceP95Millis;
        private final int upstreamPeakActive;
        private final int peakActive;
        private final int peakQueue;
        private final int peakLeased;
        private final int peakPending;
        private final int activeAfter;
        private final int queuedAfter;
        private final int leasedAfter;
        private final int pendingAfter;
        private final int availableAfter;

        private ScenarioResult(String name,
                               int attempts,
                               int accepted,
                               int completed,
                               int rejected,
                               int failed,
                               long submissionMillis,
                               long maxSubmitMicros,
                               long p95Millis,
                               long serviceP95Millis,
                               int upstreamPeakActive,
                               int peakActive,
                               int peakQueue,
                               int peakLeased,
                               int peakPending,
                               int activeAfter,
                               int queuedAfter,
                               int leasedAfter,
                               int pendingAfter,
                               int availableAfter) {
            this.name = name;
            this.attempts = attempts;
            this.accepted = accepted;
            this.completed = completed;
            this.rejected = rejected;
            this.failed = failed;
            this.submissionMillis = submissionMillis;
            this.maxSubmitMicros = maxSubmitMicros;
            this.p95Millis = p95Millis;
            this.serviceP95Millis = serviceP95Millis;
            this.upstreamPeakActive = upstreamPeakActive;
            this.peakActive = peakActive;
            this.peakQueue = peakQueue;
            this.peakLeased = peakLeased;
            this.peakPending = peakPending;
            this.activeAfter = activeAfter;
            this.queuedAfter = queuedAfter;
            this.leasedAfter = leasedAfter;
            this.pendingAfter = pendingAfter;
            this.availableAfter = availableAfter;
        }

        void print() {
            System.out.printf(Locale.ROOT,
                "CAPACITY_RESULT {\"scenario\":\"%s\",\"attempts\":%d,\"accepted\":%d,"
                    + "\"completed\":%d,\"rejected\":%d,\"failed\":%d,\"submissionMs\":%d,"
                    + "\"maxSubmitMicros\":%d,\"endToEndP95Ms\":%d,\"serviceP95Ms\":%d,"
                    + "\"upstreamPeakActive\":%d,"
                    + "\"peakActive\":%d,\"peakQueue\":%d,\"peakLeased\":%d,"
                    + "\"peakPending\":%d,\"activeAfter\":%d,\"queuedAfter\":%d,"
                    + "\"leasedAfter\":%d,\"pendingAfter\":%d,\"availableAfter\":%d}%n",
                name, attempts, accepted, completed, rejected, failed, submissionMillis,
                maxSubmitMicros, p95Millis, serviceP95Millis, upstreamPeakActive,
                peakActive, peakQueue, peakLeased, peakPending, activeAfter, queuedAfter,
                leasedAfter, pendingAfter, availableAfter);
        }
    }
}
