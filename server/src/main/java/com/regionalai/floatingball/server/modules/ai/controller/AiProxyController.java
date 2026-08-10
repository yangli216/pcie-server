package com.regionalai.floatingball.server.modules.ai.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.exception.ServiceBusyException;
import com.regionalai.floatingball.server.common.metrics.AiProxyMetrics;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.ai.dto.ChatRequest;
import com.regionalai.floatingball.server.modules.ai.dto.ChatResponse;
import com.regionalai.floatingball.server.modules.ai.dto.SpeechRequest;
import com.regionalai.floatingball.server.modules.ai.dto.SpeechResponse;
import com.regionalai.floatingball.server.modules.ai.service.AiProxyService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@RestController
@RequestMapping("/v1/ai")
public class AiProxyController {

    private final AiProxyService aiProxyService;
    private final AsyncTaskExecutor aiBlockingExecutor;
    private final AsyncTaskExecutor aiSpeechExecutor;
    private final AsyncTaskExecutor aiReviewerExecutor;
    private final AiProxyMetrics metrics;
    private final long blockingTimeoutMillis;
    private final int retryAfterSeconds;

    public AiProxyController(AiProxyService aiProxyService,
                             @Qualifier("aiBlockingExecutor") AsyncTaskExecutor aiBlockingExecutor,
                             @Qualifier("aiSpeechExecutor") AsyncTaskExecutor aiSpeechExecutor,
                             @Qualifier("aiReviewerExecutor") AsyncTaskExecutor aiReviewerExecutor,
                             AiProxyMetrics metrics,
                             @Value("${floating-ball.ai.blocking.timeout-ms:130000}") long blockingTimeoutMillis,
                             @Value("${floating-ball.ai.blocking.retry-after-seconds:1}") int retryAfterSeconds) {
        this.aiProxyService = aiProxyService;
        this.aiBlockingExecutor = aiBlockingExecutor;
        this.aiSpeechExecutor = aiSpeechExecutor;
        this.aiReviewerExecutor = aiReviewerExecutor;
        this.metrics = metrics;
        this.blockingTimeoutMillis = Math.max(1000L, blockingTimeoutMillis);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    @PostMapping(value = "/chat", produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE })
    public DeferredResult<Object> chat(@Validated @RequestBody ChatRequest request,
                                       HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        if (Boolean.TRUE.equals(request.getStream())) {
            DeferredResult<Object> streamResult = new DeferredResult<Object>();
            streamResult.setResult(aiProxyService.chatStream(device, request));
            return streamResult;
        }
        String requestId = RequestIdUtils.resolve(httpServletRequest);
        return submitBlocking(
            isReviewerRequest(request) ? aiReviewerExecutor : aiBlockingExecutor,
            isReviewerRequest(request) ? "reviewer" : "chat",
            () -> (Object) ApiResponse.success(
                new ChatResponse(aiProxyService.chat(device, request)),
                requestId
            )
        );
    }

    @PostMapping(value = "/speech/transcribe", produces = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<ApiResponse<SpeechResponse>> transcribe(@Validated @RequestBody SpeechRequest request,
                                                                  HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        String requestId = RequestIdUtils.resolve(httpServletRequest);
        return submitBlocking(
            aiSpeechExecutor,
            "speech",
            () -> ApiResponse.success(
                new SpeechResponse(aiProxyService.transcribe(device, request)),
                requestId
            )
        );
    }

    @PostMapping(value = "/speech/realtime", produces = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<ApiResponse<SpeechResponse>> realtime(@Validated @RequestBody SpeechRequest request,
                                                                HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        String requestId = RequestIdUtils.resolve(httpServletRequest);
        return submitBlocking(
            aiSpeechExecutor,
            "speech",
            () -> ApiResponse.success(
                new SpeechResponse(aiProxyService.realtime(device, request)),
                requestId
            )
        );
    }

    private <T> DeferredResult<T> submitBlocking(AsyncTaskExecutor executor,
                                                 String mode,
                                                 Supplier<T> supplier) {
        DeferredResult<T> deferredResult = new DeferredResult<T>(blockingTimeoutMillis);
        AsyncRequestLifecycle lifecycle = new AsyncRequestLifecycle(executor);

        deferredResult.onTimeout(() -> {
            lifecycle.cancel();
            boolean accepted = deferredResult.setErrorResult(new ServiceBusyException(
                "AI-TIMEOUT",
                "AI请求处理超时，请稍后重试",
                retryAfterSeconds
            ));
            if (accepted) {
                metrics.cancelled(mode);
            }
        });
        deferredResult.onError(error -> {
            if (lifecycle.cancel()) {
                metrics.cancelled(mode);
            }
        });
        deferredResult.onCompletion(() -> {
            if (lifecycle.cancel()) {
                metrics.cancelled(mode);
            }
        });

        try {
            Future<?> future = executor.submit(() -> {
                metrics.started(mode);
                try {
                    T value = supplier.get();
                    lifecycle.finished();
                    if (deferredResult.setResult(value)) {
                        metrics.succeeded(mode);
                    } else {
                        metrics.finishedAfterCancellation(mode);
                    }
                } catch (RuntimeException error) {
                    lifecycle.finished();
                    if (deferredResult.setErrorResult(error)) {
                        metrics.failed(mode);
                    } else {
                        metrics.finishedAfterCancellation(mode);
                    }
                }
            });
            lifecycle.attach(future);
        } catch (RejectedExecutionException ex) {
            metrics.rejected(mode);
            throw new ServiceBusyException("AI请求繁忙，请稍后重试", retryAfterSeconds);
        }
        return deferredResult;
    }

    private boolean isReviewerRequest(ChatRequest request) {
        return request != null && "reviewer".equalsIgnoreCase(request.getConfigProfile());
    }

    private static final class AsyncRequestLifecycle {

        private final AsyncTaskExecutor executor;
        private final AtomicReference<Future<?>> future = new AtomicReference<Future<?>>();
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private AsyncRequestLifecycle(AsyncTaskExecutor executor) {
            this.executor = executor;
        }

        private void attach(Future<?> submittedFuture) {
            future.set(submittedFuture);
            if (cancelled.get()) {
                submittedFuture.cancel(true);
                removeFromQueue(executor, submittedFuture);
            }
        }

        private void finished() {
            finished.set(true);
        }

        private boolean cancel() {
            if (finished.get() || !cancelled.compareAndSet(false, true)) {
                return false;
            }
            Future<?> submittedFuture = future.get();
            if (submittedFuture != null) {
                submittedFuture.cancel(true);
                removeFromQueue(executor, submittedFuture);
            }
            return true;
        }

        private boolean isCancelled() {
            return cancelled.get();
        }

        private static void removeFromQueue(AsyncTaskExecutor executor, Future<?> future) {
            if (executor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
                && future instanceof Runnable) {
                org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor threadPool =
                    (org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) executor;
                threadPool.getThreadPoolExecutor().remove((Runnable) future);
            }
        }
    }
}
