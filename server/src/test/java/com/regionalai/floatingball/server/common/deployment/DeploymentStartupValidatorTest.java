package com.regionalai.floatingball.server.common.deployment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentStartupValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(DeploymentContractTestConfiguration.class);

    @Test
    void standaloneDefaultsDoNotRequireSharedDependencies() {
        DeploymentProperties deployment = new DeploymentProperties();
        StorageProperties storage = new StorageProperties();

        assertDoesNotThrow(() -> new DeploymentStartupValidator(deployment, storage, "memory").validate());
    }

    @Test
    void aiScaleOutRequiresNodeDatabaseNonceAndSharedStorageIdentity() {
        DeploymentProperties deployment = scaleOut("node-a");
        StorageProperties storage = shared("storage-a");

        assertDoesNotThrow(() -> new DeploymentStartupValidator(deployment, storage, "database").validate());

        assertMessageContains(
            new DeploymentStartupValidator(scaleOut(" "), storage, "database"),
            "node-id"
        );
        assertMessageContains(
            new DeploymentStartupValidator(deployment, storage, "memory"),
            "nonce.store=database"
        );

        StorageProperties local = new StorageProperties();
        assertMessageContains(
            new DeploymentStartupValidator(deployment, local, "database"),
            "storage.mode=shared-posix"
        );
        assertMessageContains(
            new DeploymentStartupValidator(deployment, shared(" "), "database"),
            "shared-storage-id"
        );
    }

    @Test
    void rejectsUnknownDeploymentAndStorageModes() {
        DeploymentProperties deployment = new DeploymentProperties();
        deployment.setMode("cluster");
        assertMessageContains(
            new DeploymentStartupValidator(deployment, new StorageProperties(), "memory"),
            "deployment.mode"
        );

        deployment.setMode(DeploymentProperties.STANDALONE);
        StorageProperties storage = new StorageProperties();
        storage.setMode("nas");
        assertMessageContains(
            new DeploymentStartupValidator(deployment, storage, "memory"),
            "storage.mode"
        );
    }

    @Test
    void realContextKeepsStandaloneDefaultsBootable() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(
                DeploymentProperties.STANDALONE,
                context.getBean(DeploymentProperties.class).normalizedMode()
            );
            assertEquals(
                StorageProperties.LOCAL,
                context.getBean(StorageProperties.class).normalizedMode()
            );
        });
    }

    @Test
    void realContextBindsAndAcceptsCompleteScaleOutContract() {
        contextRunner
            .withPropertyValues(
                "floating-ball.deployment.mode=ai-scale-out",
                "floating-ball.deployment.node-id=node-a",
                "floating-ball.security.nonce.store=database",
                "floating-ball.storage.mode=shared-posix",
                "floating-ball.storage.shared-storage-id=storage-a"
            )
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.getBean(DeploymentProperties.class).isAiScaleOut());
                assertTrue(context.getBean(StorageProperties.class).isSharedPosix());
            });
    }

    @Test
    void realContextRejectsIncompleteScaleOutContractDuringRefresh() {
        contextRunner
            .withPropertyValues("floating-ball.deployment.mode=ai-scale-out")
            .run(context -> {
                Throwable failure = context.getStartupFailure();
                assertTrue(failure != null, "context refresh should fail");
                assertTrue(allMessages(failure).contains("node-id"), allMessages(failure));
            });
    }

    private DeploymentProperties scaleOut(String nodeId) {
        DeploymentProperties properties = new DeploymentProperties();
        properties.setMode(DeploymentProperties.AI_SCALE_OUT);
        properties.setNodeId(nodeId);
        return properties;
    }

    private StorageProperties shared(String storageId) {
        StorageProperties properties = new StorageProperties();
        properties.setMode(StorageProperties.SHARED_POSIX);
        properties.setSharedStorageId(storageId);
        return properties;
    }

    private void assertMessageContains(DeploymentStartupValidator validator, String expected) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }

    private String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    @Configuration
    @EnableConfigurationProperties({DeploymentProperties.class, StorageProperties.class})
    static class DeploymentContractTestConfiguration {

        @Bean
        DeploymentStartupValidator deploymentStartupValidator(
            DeploymentProperties deploymentProperties,
            StorageProperties storageProperties,
            @Value("${floating-ball.security.nonce.store:memory}") String nonceStore
        ) {
            return new DeploymentStartupValidator(deploymentProperties, storageProperties, nonceStore);
        }
    }
}
