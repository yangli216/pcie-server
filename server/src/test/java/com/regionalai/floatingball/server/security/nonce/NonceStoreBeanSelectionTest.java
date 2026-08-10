package com.regionalai.floatingball.server.security.nonce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NonceStoreBeanSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(InMemoryNonceStore.class, JdbcNonceStore.class);

    @Test
    void defaultMode_usesInMemoryStore() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NonceStore.class);
            assertThat(context).hasSingleBean(InMemoryNonceStore.class);
            assertThat(context).doesNotHaveBean(JdbcNonceStore.class);
        });
    }

    @Test
    void clusterMode_usesJdbcStore() {
        contextRunner
            .withPropertyValues("floating-ball.cluster.enabled=true")
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .run(context -> {
                assertThat(context).hasSingleBean(NonceStore.class);
                assertThat(context).hasSingleBean(JdbcNonceStore.class);
                assertThat(context).doesNotHaveBean(InMemoryNonceStore.class);
            });
    }

    @Test
    void clusterModeWithoutJdbcTemplate_doesNotFallbackToMemory() {
        contextRunner
            .withPropertyValues("floating-ball.cluster.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("JdbcTemplate");
            });
    }
}
