package com.regionalai.floatingball.server.common.deployment;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

@Component
public class DeploymentStartupValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DeploymentStartupValidator.class);

    private final DeploymentProperties deploymentProperties;
    private final StorageProperties storageProperties;
    private final String nonceStore;

    public DeploymentStartupValidator(
        DeploymentProperties deploymentProperties,
        StorageProperties storageProperties,
        @Value("${floating-ball.security.nonce.store:memory}") String nonceStore
    ) {
        this.deploymentProperties = deploymentProperties;
        this.storageProperties = storageProperties;
        this.nonceStore = nonceStore;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    void validate() {
        requireOneOf(
            "floating-ball.deployment.mode",
            deploymentProperties.normalizedMode(),
            DeploymentProperties.STANDALONE,
            DeploymentProperties.AI_SCALE_OUT
        );
        requireOneOf(
            "floating-ball.storage.mode",
            storageProperties.normalizedMode(),
            StorageProperties.LOCAL,
            StorageProperties.SHARED_POSIX
        );

        if (!deploymentProperties.isAiScaleOut()) {
            log.info(
                "deployment startup validation passed. mode={}, storageMode={}, nonceStore={}",
                deploymentProperties.normalizedMode(),
                storageProperties.normalizedMode(),
                DeploymentProperties.normalize(nonceStore)
            );
            return;
        }
        requireText("floating-ball.deployment.node-id", deploymentProperties.getNodeId());
        if (!"database".equals(DeploymentProperties.normalize(nonceStore))) {
            throw new IllegalStateException(
                "ai-scale-out requires floating-ball.security.nonce.store=database"
            );
        }
        if (!storageProperties.isSharedPosix()) {
            throw new IllegalStateException(
                "ai-scale-out requires floating-ball.storage.mode=shared-posix"
            );
        }
        requireText("floating-ball.storage.shared-storage-id", storageProperties.getSharedStorageId());
        log.info(
            "deployment startup validation passed. mode={}, nodeId={}, storageMode={}, sharedStorageId={}, nonceStore={}",
            deploymentProperties.normalizedMode(),
            deploymentProperties.getNodeId().trim(),
            storageProperties.normalizedMode(),
            storageProperties.getSharedStorageId().trim(),
            DeploymentProperties.normalize(nonceStore)
        );
    }

    private void requireOneOf(String property, String actual, String... accepted) {
        if (!Arrays.asList(accepted).contains(actual)) {
            throw new IllegalStateException(
                property + " must be one of " + Arrays.toString(accepted) + ", but was '" + actual + "'"
            );
        }
    }

    private void requireText(String property, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(property + " must not be blank in ai-scale-out mode");
        }
    }
}
