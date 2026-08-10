package com.regionalai.floatingball.server.modules.knowledge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.exception.ServiceBusyException;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.knowledge.dto.PmphaiClipRequest;
import com.regionalai.floatingball.server.modules.knowledge.dto.PmphaiListRequest;
import com.regionalai.floatingball.server.modules.knowledge.dto.PmphaiPageUrlRequest;
import com.regionalai.floatingball.server.modules.knowledge.dto.PmphaiPageUrlResponse;
import com.regionalai.floatingball.server.modules.knowledge.dto.PmphaiSearchRequest;
import com.regionalai.floatingball.server.modules.knowledge.service.PmphaiProxyService;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@RestController
@RequestMapping("/v1/knowledge/pmphai")
public class PmphaiProxyController {

    private final PmphaiProxyService pmphaiProxyService;
    private final AsyncTaskExecutor aiKnowledgeExecutor;
    private final long timeoutMillis;
    private final int retryAfterSeconds;

    public PmphaiProxyController(PmphaiProxyService pmphaiProxyService,
                                 @Qualifier("aiKnowledgeExecutor") AsyncTaskExecutor aiKnowledgeExecutor,
                                 @Value("${floating-ball.knowledge.blocking.timeout-ms:30000}") long timeoutMillis,
                                 @Value("${floating-ball.knowledge.blocking.retry-after-seconds:1}") int retryAfterSeconds) {
        this.pmphaiProxyService = pmphaiProxyService;
        this.aiKnowledgeExecutor = aiKnowledgeExecutor;
        this.timeoutMillis = Math.max(1000L, timeoutMillis);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    @PostMapping("/search")
    public DeferredResult<ApiResponse<JsonNode>> search(@Validated @RequestBody PmphaiSearchRequest request,
                                                        HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        String requestId = RequestIdUtils.resolve(httpServletRequest);
        return submitKnowledge(() -> ApiResponse.success(pmphaiProxyService.search(device, request), requestId));
    }

    @PostMapping("/clip")
    public DeferredResult<ApiResponse<JsonNode>> clip(@Validated @RequestBody PmphaiClipRequest request,
                                                      HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        String requestId = RequestIdUtils.resolve(httpServletRequest);
        return submitKnowledge(() -> ApiResponse.success(pmphaiProxyService.clip(device, request), requestId));
    }

    @PostMapping("/list")
    public DeferredResult<ApiResponse<JsonNode>> list(@RequestBody PmphaiListRequest request,
                                                      HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        String requestId = RequestIdUtils.resolve(httpServletRequest);
        return submitKnowledge(() -> ApiResponse.success(pmphaiProxyService.list(device, request), requestId));
    }

    @PostMapping("/page-url")
    public ApiResponse<PmphaiPageUrlResponse> pageUrl(@Validated @RequestBody PmphaiPageUrlRequest request,
                                                      HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        return ApiResponse.success(new PmphaiPageUrlResponse(pmphaiProxyService.generatePageUrl(device, request)), RequestIdUtils.resolve(httpServletRequest));
    }

    @GetMapping("/kgbases")
    public DeferredResult<ApiResponse<JsonNode>> knowledgeBases(
        @RequestParam(value = "kgBaseId", required = false) String kgBaseId,
        HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        String requestId = RequestIdUtils.resolve(httpServletRequest);
        return submitKnowledge(() -> ApiResponse.success(pmphaiProxyService.knowledgeBases(device, kgBaseId), requestId));
    }

    @GetMapping("/categories")
    public DeferredResult<ApiResponse<JsonNode>> categories(@RequestParam("kgBaseId") String kgBaseId,
                                                            HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        String requestId = RequestIdUtils.resolve(httpServletRequest);
        return submitKnowledge(() -> ApiResponse.success(pmphaiProxyService.categories(device, kgBaseId), requestId));
    }

    private <T> DeferredResult<T> submitKnowledge(Supplier<T> supplier) {
        DeferredResult<T> deferredResult = new DeferredResult<T>(timeoutMillis);
        AsyncRequestLifecycle lifecycle = new AsyncRequestLifecycle(aiKnowledgeExecutor);

        deferredResult.onTimeout(() -> {
            lifecycle.cancel();
            deferredResult.setErrorResult(new ServiceBusyException(
                "AI-TIMEOUT",
                "知识检索请求处理超时，请稍后重试",
                retryAfterSeconds
            ));
        });
        deferredResult.onError(error -> lifecycle.cancel());
        deferredResult.onCompletion(lifecycle::cancel);

        try {
            Future<?> future = aiKnowledgeExecutor.submit(() -> {
                try {
                    T value = supplier.get();
                    lifecycle.finished();
                    if (!lifecycle.isCancelled()) {
                        deferredResult.setResult(value);
                    }
                } catch (RuntimeException error) {
                    lifecycle.finished();
                    if (!lifecycle.isCancelled()) {
                        deferredResult.setErrorResult(error);
                    }
                }
            });
            lifecycle.attach(future);
        } catch (RejectedExecutionException ex) {
            deferredResult.setErrorResult(new ServiceBusyException(
                "知识检索服务繁忙，请稍后重试",
                retryAfterSeconds
            ));
        }
        return deferredResult;
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
