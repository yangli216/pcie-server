package com.regionalai.floatingball.server.modules.emrqc.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmrQcPropertiesTest {

    @Test
    void shouldEnableOutpatientEmrQcByDefault() {
        EmrQcProperties properties = new EmrQcProperties();

        assertTrue(properties.isEnabled());
    }

    @Test
    void shouldAllowExplicitEmergencyDisable() {
        EmrQcProperties properties = new EmrQcProperties();

        properties.setEnabled(false);

        assertFalse(properties.isEnabled());
    }

    @Test
    void applicationConfigurationShouldDefaultEnvironmentSwitchToEnabled() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
            "application.yml",
            new ClassPathResource("application.yml")
        );

        Object configuredValue = sources.stream()
            .map(source -> source.getProperty("floating-ball.emr-qc.enabled"))
            .filter(value -> value != null)
            .findFirst()
            .orElse(null);

        assertEquals("${FB_EMR_QC_ENABLED:true}", configuredValue);
    }
}
