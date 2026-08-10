package com.regionalai.floatingball.server.modules.ai.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.exception.ServiceBusyException;
import com.regionalai.floatingball.server.common.metrics.AiProxyMetrics;
import com.regionalai.floatingball.server.modules.ai.dto.ChatRequest;
import com.regionalai.floatingball.server.modules.ai.dto.ChatResponse;
import com.regionalai.floatingball.server.modules.ai.service.AiProxyService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiProxyControllerTest {

    @Test
    void nonStreamingChatReturnsDeferredApiResponse() {
        AiProxyService service = mock(AiProxyService.class);
        when(service.chat(null, chatRequest(false))).thenReturn("ok");
        AsyncTaskExecutor direct = new TaskExecutorAdapter(Runnable::run);
        AiProxyController controller = new AiProxyController(service, direct, direct, direct, AiProxyMetrics.noop(), 5000L, 1);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getHeader("X-Request-Id")).thenReturn("request-1");
        ChatRequest request = chatRequest(false);
        when(service.chat(null, request)).thenReturn("ok");

        Object response = controller.chat(request, servletRequest);

        assertThat(response).isInstanceOf(DeferredResult.class);
        Object result = ((DeferredResult<?>) response).getResult();
        assertThat(result).isInstanceOf(ApiResponse.class);
        ApiResponse<?> apiResponse = (ApiResponse<?>) result;
        assertThat(apiResponse.getCode()).isEqualTo("0");
        assertThat(apiResponse.getRequestId()).isEqualTo("request-1");
        assertThat(((ChatResponse) apiResponse.getData()).getContent()).isEqualTo("ok");
    }

    @Test
    void rejectedChatFailsFastWithBusyException() {
        AiProxyService service = mock(AiProxyService.class);
        AsyncTaskExecutor rejecting = new TaskExecutorAdapter(command -> {
            throw new RejectedExecutionException("full");
        });
        AsyncTaskExecutor direct = new TaskExecutorAdapter(Runnable::run);
        AiProxyController controller = new AiProxyController(service, rejecting, direct, direct, AiProxyMetrics.noop(), 5000L, 2);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> controller.chat(chatRequest(false), servletRequest))
            .isInstanceOf(ServiceBusyException.class)
            .extracting("code", "retryAfterSeconds")
            .containsExactly("AI-BUSY", 2);
    }

    @Test
    void reviewerChatUsesIsolatedExecutorWhenCorePoolIsBusy() {
        AiProxyService service = mock(AiProxyService.class);
        AsyncTaskExecutor rejectingCore = new TaskExecutorAdapter(command -> {
            throw new RejectedExecutionException("core full");
        });
        AsyncTaskExecutor direct = new TaskExecutorAdapter(Runnable::run);
        AiProxyController controller = new AiProxyController(
            service,
            rejectingCore,
            direct,
            direct,
            AiProxyMetrics.noop(),
            5000L,
            1
        );
        ChatRequest reviewer = chatRequest(false);
        reviewer.setConfigProfile("reviewer");
        when(service.chat(null, reviewer)).thenReturn("review ok");
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);

        DeferredResult<Object> response = controller.chat(reviewer, servletRequest);

        ApiResponse<?> apiResponse = (ApiResponse<?>) response.getResult();
        assertThat(((ChatResponse) apiResponse.getData()).getContent()).isEqualTo("review ok");
    }

    @Test
    void mvcStartsAsyncAndDispatchesNonStreamingJsonWithoutAcceptHeader() throws Exception {
        AiProxyService service = mock(AiProxyService.class);
        when(service.chat(any(), any(ChatRequest.class))).thenReturn("ok");
        MockMvc mockMvc = mockMvc(service);

        MvcResult initial = mockMvc.perform(post("/v1/ai/chat")
                .contentType("application/json")
                .header("X-Request-Id", "request-1")
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.content").value("ok"));
    }

    @Test
    void mvcStartsAsyncAndDispatchesStreamingEmitterWithoutAcceptHeader() throws Exception {
        AiProxyService service = mock(AiProxyService.class);
        SseEmitter emitter = new SseEmitter();
        emitter.send(SseEmitter.event().data("hello"));
        emitter.send(SseEmitter.event().data("[DONE]"));
        emitter.complete();
        when(service.chatStream(any(), any(ChatRequest.class))).thenReturn(emitter);
        MockMvc mockMvc = mockMvc(service);

        MvcResult initial = mockMvc.perform(post("/v1/ai/chat")
                .contentType("application/json")
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data:hello")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data:[DONE]")));
    }

    @Test
    void blockingCancellationRemovesQueuedFuture() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        Future<?> queued = executor.submit(() -> { });
        Class<?> lifecycleClass = Class.forName(
            "com.regionalai.floatingball.server.modules.ai.controller.AiProxyController$AsyncRequestLifecycle"
        );
        Constructor<?> constructor = lifecycleClass.getDeclaredConstructor(AsyncTaskExecutor.class);
        constructor.setAccessible(true);
        Object lifecycle = constructor.newInstance(executor);

        ReflectionTestUtils.invokeMethod(lifecycle, "attach", queued);
        Boolean cancelled = ReflectionTestUtils.invokeMethod(lifecycle, "cancel");

        assertThat(cancelled).isTrue();
        assertThat(queued.isCancelled()).isTrue();
        assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        release.countDown();
        executor.shutdown();
    }

    private ChatRequest chatRequest(boolean stream) {
        ChatRequest request = new ChatRequest();
        request.setStream(stream);
        request.setMessages(Collections.singletonList(Collections.<String, Object>singletonMap("role", "user")));
        return request;
    }

    private MockMvc mockMvc(AiProxyService service) {
        AsyncTaskExecutor direct = new TaskExecutorAdapter(Runnable::run);
        AiProxyController controller = new AiProxyController(
            service,
            direct,
            direct,
            direct,
            AiProxyMetrics.noop(),
            5000L,
            1
        );
        return MockMvcBuilders.standaloneSetup(controller).build();
    }
}
