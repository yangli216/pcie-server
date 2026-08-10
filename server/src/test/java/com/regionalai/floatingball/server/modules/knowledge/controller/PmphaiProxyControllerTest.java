package com.regionalai.floatingball.server.modules.knowledge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.knowledge.dto.PmphaiSearchRequest;
import com.regionalai.floatingball.server.modules.knowledge.service.PmphaiProxyService;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PmphaiProxyControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearDeviceContext() {
        DeviceContextHolder.clear();
    }

    @Test
    void searchStartsAsyncAndPreservesCapturedDeviceAndRequestId() throws Exception {
        PmphaiProxyService service = mock(PmphaiProxyService.class);
        ObjectNode data = objectMapper.createObjectNode().put("answer", "ok");
        when(service.search(any(), any(PmphaiSearchRequest.class))).thenReturn(data);
        ThreadPoolTaskExecutor executor = executor(1, 1);
        MockMvc mockMvc = mockMvc(service, executor, 5000L, 1);
        AiDevice device = new AiDevice();
        device.setIdDevice("device-1");
        DeviceContextHolder.set(device);

        try {
            MvcResult initial = mockMvc.perform(post("/v1/knowledge/pmphai/search")
                    .contentType("application/json")
                    .header("X-Request-Id", "knowledge-request-1")
                    .content("{\"query\":\"hypertension\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
            DeviceContextHolder.clear();

            mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.requestId").value("knowledge-request-1"))
                .andExpect(jsonPath("$.data.answer").value("ok"));
            verify(service).search(same(device), any(PmphaiSearchRequest.class));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void serviceExceptionIsDispatchedThroughGlobalHandler() throws Exception {
        PmphaiProxyService service = mock(PmphaiProxyService.class);
        when(service.search(any(), any(PmphaiSearchRequest.class)))
            .thenThrow(new BusinessException("知识库配置不可用"));
        ThreadPoolTaskExecutor executor = executor(1, 1);
        MockMvc mockMvc = mockMvc(service, executor, 5000L, 1);

        try {
            MvcResult initial = mockMvc.perform(post("/v1/knowledge/pmphai/search")
                    .contentType("application/json")
                    .content("{\"query\":\"hypertension\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

            mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BIZ-001"))
                .andExpect(jsonPath("$.message").value("知识库配置不可用"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectedSearchIsDispatchedAsServiceUnavailable() throws Exception {
        PmphaiProxyService service = mock(PmphaiProxyService.class);
        AsyncTaskExecutor rejecting = new TaskExecutorAdapter(command -> {
            throw new RejectedExecutionException("full");
        });
        MockMvc mockMvc = mockMvc(service, rejecting, 5000L, 2);

        MvcResult initial = mockMvc.perform(post("/v1/knowledge/pmphai/search")
                .contentType("application/json")
                .content("{\"query\":\"hypertension\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "2"))
            .andExpect(jsonPath("$.code").value("AI-BUSY"));
        verifyNoInteractions(service);
    }

    @Test
    void cancellationRemovesQueuedFuture() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 1);
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
            "com.regionalai.floatingball.server.modules.knowledge.controller.PmphaiProxyController$AsyncRequestLifecycle"
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

    private MockMvc mockMvc(PmphaiProxyService service,
                            AsyncTaskExecutor executor,
                            long timeoutMillis,
                            int retryAfterSeconds) {
        PmphaiProxyController controller = new PmphaiProxyController(
            service,
            executor,
            timeoutMillis,
            retryAfterSeconds
        );
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private ThreadPoolTaskExecutor executor(int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("knowledge-test-");
        executor.initialize();
        return executor;
    }
}
