package com.regionalai.floatingball.server.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementConfigurationTest {

    @Test
    void managementEndpointsStayOnLoopbackAndExposeScaleOutOperations() throws Exception {
        Map<String, Object> application;
        try (InputStream input = new ClassPathResource("application.yml").getInputStream()) {
            application = new Yaml().load(input);
        }

        Map<String, Object> businessServer = section(application, "server");
        Map<String, Object> management = section(application, "management");
        Map<String, Object> managementServer = section(management, "server");
        Map<String, Object> web = section(section(management, "endpoints"), "web");
        Map<String, Object> health = section(section(management, "endpoint"), "health");
        Map<String, Object> readiness = section(section(health, "group"), "readiness");
        Map<String, Object> metricTags = section(section(management, "metrics"), "tags");

        String exposure = String.valueOf(section(web, "exposure").get("include"));
        String readinessMembers = String.valueOf(readiness.get("include"));

        assertEquals("8080", String.valueOf(businessServer.get("port")));
        assertTrue(String.valueOf(managementServer.get("address")).contains("127.0.0.1"));
        assertTrue(String.valueOf(managementServer.get("port")).contains("8081"));
        assertEquals("health,info,prometheus,traffic", exposure);
        assertTrue(readinessMembers.contains("readinessState"));
        assertTrue(readinessMembers.contains("db"));
        assertFalse(readinessMembers.contains("clusterStorage"));
        assertTrue(readinessMembers.contains("nonceStore"));
        assertTrue(readinessMembers.contains("storage"));
        assertEquals("${FB_NODE_ID:${HOSTNAME:standalone}}", String.valueOf(metricTags.get("instance")));
    }

    @Test
    void deploymentDefaultsKeepStandaloneModeUsableWithoutExtraInfrastructure() throws Exception {
        Map<String, Object> application;
        try (InputStream input = new ClassPathResource("application.yml").getInputStream()) {
            application = new Yaml().load(input);
        }

        Map<String, Object> floatingBall = section(application, "floating-ball");
        Map<String, Object> deployment = section(floatingBall, "deployment");
        Map<String, Object> storage = section(floatingBall, "storage");
        Map<String, Object> nonce = section(section(floatingBall, "security"), "nonce");

        assertEquals("${FB_DEPLOYMENT_MODE:standalone}", String.valueOf(deployment.get("mode")));
        assertEquals("${FB_NODE_ID:}", String.valueOf(deployment.get("node-id")));
        assertEquals("${FB_STORAGE_MODE:local}", String.valueOf(storage.get("mode")));
        assertEquals("${FB_SHARED_STORAGE_ID:}", String.valueOf(storage.get("shared-storage-id")));
        assertEquals("${FB_NONCE_STORE:memory}", String.valueOf(nonce.get("store")));
        assertEquals("${FB_NONCE_CLEANUP_INTERVAL_MS:60000}", String.valueOf(nonce.get("cleanup-interval-ms")));
        assertEquals("${FB_NONCE_CLEANUP_GRACE_MS:300000}", String.valueOf(nonce.get("cleanup-grace-ms")));
        assertEquals("${FB_NONCE_DEEP_PROBE_INTERVAL_MS:300000}", String.valueOf(nonce.get("deep-probe-interval-ms")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }
}
