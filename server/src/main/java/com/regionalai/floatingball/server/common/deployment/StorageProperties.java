package com.regionalai.floatingball.server.common.deployment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "floating-ball.storage")
public class StorageProperties {

    public static final String LOCAL = "local";
    public static final String SHARED_POSIX = "shared-posix";

    private String mode = LOCAL;
    private String sharedStorageId = "";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getSharedStorageId() {
        return sharedStorageId;
    }

    public void setSharedStorageId(String sharedStorageId) {
        this.sharedStorageId = sharedStorageId;
    }

    public String normalizedMode() {
        return DeploymentProperties.normalize(mode);
    }

    public boolean isSharedPosix() {
        return SHARED_POSIX.equals(normalizedMode());
    }
}
