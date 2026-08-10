package com.regionalai.floatingball.server.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AiBlockingExecutorConfig {

    @Bean(name = "aiBlockingExecutor")
    public ThreadPoolTaskExecutor aiBlockingExecutor(
        @Value("${floating-ball.ai.blocking.pool-size:80}") int poolSize,
        @Value("${floating-ball.ai.blocking.queue-capacity:8}") int queueCapacity,
        @Value("${floating-ball.ai.blocking.keep-alive-seconds:60}") long keepAliveSeconds) {
        return buildExecutor("ai-blocking-", poolSize, queueCapacity, keepAliveSeconds);
    }

    @Bean(name = "aiSpeechExecutor")
    public ThreadPoolTaskExecutor aiSpeechExecutor(
        @Value("${floating-ball.ai.blocking.speech-pool-size:8}") int poolSize,
        @Value("${floating-ball.ai.blocking.speech-queue-capacity:4}") int queueCapacity,
        @Value("${floating-ball.ai.blocking.keep-alive-seconds:60}") long keepAliveSeconds) {
        return buildExecutor("ai-speech-", poolSize, queueCapacity, keepAliveSeconds);
    }

    @Bean(name = "aiReviewerExecutor")
    public ThreadPoolTaskExecutor aiReviewerExecutor(
        @Value("${floating-ball.ai.blocking.reviewer-pool-size:16}") int poolSize,
        @Value("${floating-ball.ai.blocking.reviewer-queue-capacity:4}") int queueCapacity,
        @Value("${floating-ball.ai.blocking.keep-alive-seconds:60}") long keepAliveSeconds) {
        return buildExecutor("ai-reviewer-", poolSize, queueCapacity, keepAliveSeconds);
    }

    private ThreadPoolTaskExecutor buildExecutor(String threadNamePrefix,
                                                 int poolSize,
                                                 int queueCapacity,
                                                 long keepAliveSeconds) {
        int size = Math.max(1, poolSize);
        int capacity = Math.max(0, queueCapacity);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(capacity);
        executor.setKeepAliveSeconds((int) Math.min(Integer.MAX_VALUE, Math.max(1L, keepAliveSeconds)));
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
