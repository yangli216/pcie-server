package com.regionalai.floatingball.server.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AiStreamExecutorConfig {

    @Bean(name = "aiStreamExecutor")
    public ThreadPoolTaskExecutor aiStreamExecutor(@Value("${floating-ball.ai.stream.core-pool-size:16}") int corePoolSize,
                                     @Value("${floating-ball.ai.stream.max-pool-size:16}") int maxPoolSize,
                                     @Value("${floating-ball.ai.stream.queue-capacity:4}") int queueCapacity,
                                     @Value("${floating-ball.ai.stream.keep-alive-seconds:60}") long keepAliveSeconds) {
        int core = Math.max(1, corePoolSize);
        int max = Math.max(core, maxPoolSize);
        int capacity = Math.max(0, queueCapacity);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(capacity);
        executor.setKeepAliveSeconds((int) Math.min(Integer.MAX_VALUE, Math.max(1L, keepAliveSeconds)));
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("ai-chat-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
