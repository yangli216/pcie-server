package com.regionalai.floatingball.server.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class KnowledgeExecutorConfig {

    @Bean(name = "aiKnowledgeExecutor")
    public ThreadPoolTaskExecutor aiKnowledgeExecutor(
        @Value("${floating-ball.knowledge.blocking.pool-size:16}") int poolSize,
        @Value("${floating-ball.knowledge.blocking.queue-capacity:4}") int queueCapacity) {
        int size = Math.max(1, poolSize);
        int capacity = Math.max(0, queueCapacity);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(capacity);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("ai-knowledge-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
