package com.regionalai.floatingball.server.security.nonce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NonceStoreBeanSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultModeSelectsMemoryStore() {
        runner.run(context -> {
            assertTrue(context.getBean(NonceStore.class) instanceof InMemoryNonceStore);
            assertTrue(context.getBeansOfType(JdbcNonceStore.class).isEmpty());
        });
    }

    @Test
    void databaseModeSelectsJdbcStore() {
        runner.withPropertyValues("floating-ball.security.nonce.store=database")
            .run(context -> {
                assertTrue(context.getBean(NonceStore.class) instanceof JdbcNonceStore);
                assertTrue(context.getBeansOfType(InMemoryNonceStore.class).isEmpty());
            });
    }

    @Test
    void invalidModeDoesNotFallBackToMemory() {
        runner.withPropertyValues("floating-ball.security.nonce.store=redis")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void cleanupGraceBelowReplaySafetyFloorFailsBinding() {
        runner.withPropertyValues("floating-ball.security.nonce.cleanup-grace-ms=299999")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void deepProbeIntervalBelowSafetyFloorFailsBinding() {
        runner.withPropertyValues("floating-ball.security.nonce.deep-probe-interval-ms=59999")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Configuration
    @EnableConfigurationProperties(NonceStoreProperties.class)
    @Import({InMemoryNonceStore.class, JdbcNonceStore.class, DatabaseNonceStoreAvailability.class})
    static class TestConfiguration {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }
}
