package com.regionalai.floatingball.server.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeExecutorConfigTest {

    @Test
    void knowledgeExecutorUsesFixedPoolAndBoundedQueue() {
        KnowledgeExecutorConfig config = new KnowledgeExecutorConfig();
        ThreadPoolTaskExecutor executor = config.aiKnowledgeExecutor(6, 3);
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(6);
            assertThat(executor.getMaxPoolSize()).isEqualTo(6);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("ai-knowledge-");
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(3);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void zeroQueueCapacityUsesDirectHandoff() {
        KnowledgeExecutorConfig config = new KnowledgeExecutorConfig();
        ThreadPoolTaskExecutor executor = config.aiKnowledgeExecutor(1, 0);
        try {
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
        } finally {
            executor.shutdown();
        }
    }
}
