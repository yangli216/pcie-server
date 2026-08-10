package com.regionalai.floatingball.server.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AiExecutorConfigTest {

    @Test
    void blockingExecutorsUseFixedPoolsAndBoundedQueues() {
        AiBlockingExecutorConfig config = new AiBlockingExecutorConfig();
        ThreadPoolTaskExecutor chat = (ThreadPoolTaskExecutor) config.aiBlockingExecutor(5, 2, 30);
        ThreadPoolTaskExecutor speech = (ThreadPoolTaskExecutor) config.aiSpeechExecutor(3, 1, 30);
        ThreadPoolTaskExecutor reviewer = (ThreadPoolTaskExecutor) config.aiReviewerExecutor(4, 2, 30);
        try {
            assertBounded(chat, 5, 2);
            assertBounded(speech, 3, 1);
            assertBounded(reviewer, 4, 2);
        } finally {
            chat.shutdown();
            speech.shutdown();
            reviewer.shutdown();
        }
    }

    @Test
    void streamExecutorUsesBoundedQueue() {
        AiStreamExecutorConfig config = new AiStreamExecutorConfig();
        AsyncTaskExecutor configured = config.aiStreamExecutor(2, 4, 3, 30);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configured;
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(3);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void zeroQueueCapacityUsesDirectHandoff() {
        AiBlockingExecutorConfig config = new AiBlockingExecutorConfig();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.aiBlockingExecutor(1, 0, 30);
        try {
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
        } finally {
            executor.shutdown();
        }
    }

    private void assertBounded(ThreadPoolTaskExecutor executor, int size, int queueCapacity) {
        assertThat(executor.getCorePoolSize()).isEqualTo(size);
        assertThat(executor.getMaxPoolSize()).isEqualTo(size);
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(queueCapacity);
    }
}
