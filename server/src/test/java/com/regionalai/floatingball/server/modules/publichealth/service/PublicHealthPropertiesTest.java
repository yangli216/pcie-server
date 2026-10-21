package com.regionalai.floatingball.server.modules.publichealth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class PublicHealthPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class);

    @Configuration
    @EnableConfigurationProperties(PublicHealthProperties.class)
    static class Binding { }

    @Test void defaultsKeepRetrievalAddressAndAssetsEmptyAndPublicHealthDisabled() {
        context.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            PublicHealthProperties properties = ctx.getBean(PublicHealthProperties.class);
            assertEquals("", properties.getEmbeddingBaseUrl());
            assertEquals("", properties.getAssetDirectory());
            assertTrue(properties.getOrgAssets().isEmpty());
            assertFalse(properties.isEnabled());
            assertFalse(properties.isRetrievalEnabled());
        });
    }

    @ParameterizedTest @ValueSource(strings = {
            "floating-ball.public-health.embedding-base-url", "FB_PUBLIC_HEALTH_EMBEDDING_BASE_URL"
    })
    void supportsExternalPropertyAndDocumentedEnvironmentVariable(String key) {
        context.withPropertyValues(key + "=http://127.0.0.2:18081/v1").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            PublicHealthProperties properties = ctx.getBean(PublicHealthProperties.class);
            assertEquals("http://127.0.0.2:18081/v1", properties.getEmbeddingBaseUrl());
            assertFalse(properties.isEnabled());
            assertFalse(properties.isRetrievalEnabled());
            assertTrue(properties.getOrgAssets().isEmpty());
        });
    }
}
