package com.regionalai.floatingball.server.common.cluster;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterManagementConfigurationTest {

    @Test
    void challengeEndpointShouldBeExposedOnlyThroughLoopbackManagementConfiguration() throws Exception {
        Map<String, Object> application;
        try (InputStream input = new ClassPathResource("application.yml").getInputStream()) {
            application = new Yaml().load(input);
        }

        Map<String, Object> management = section(application, "management");
        Map<String, Object> managementServer = section(management, "server");
        Map<String, Object> endpoints = section(management, "endpoints");
        Map<String, Object> web = section(endpoints, "web");
        Map<String, Object> exposureConfig = section(web, "exposure");
        Map<String, Object> businessServer = section(application, "server");

        String exposure = String.valueOf(exposureConfig.get("include"));
        String managementAddress = String.valueOf(managementServer.get("address"));
        String managementPort = String.valueOf(managementServer.get("port"));
        String businessPort = String.valueOf(businessServer.get("port"));

        assertTrue(exposure.contains("clusterStorageChallenge"));
        assertTrue(managementAddress.contains("127.0.0.1"));
        assertTrue(managementPort.contains("8081"));
        assertEquals("8080", businessPort);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }
}
