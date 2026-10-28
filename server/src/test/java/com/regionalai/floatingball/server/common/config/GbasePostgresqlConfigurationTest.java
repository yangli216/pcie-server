package com.regionalai.floatingball.server.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbasePostgresqlConfigurationTest {
    @Test
    void profileShouldKeepConsoleAndFileLoggingEnabled() throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ClassPathResource("logback-spring.xml").getInputStream());
        NodeList profiles = document.getElementsByTagName("springProfile");
        boolean enabled = false;
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            if (!profile.getAttribute("name").contains("gbase-postgresql")) {
                continue;
            }
            NodeList appenders = profile.getElementsByTagName("appender-ref");
            boolean console = false;
            boolean file = false;
            for (int j = 0; j < appenders.getLength(); j++) {
                String name = ((Element) appenders.item(j)).getAttribute("ref");
                console |= "CONSOLE".equals(name);
                file |= "FILE".equals(name);
            }
            enabled = console && file;
        }
        assertTrue(enabled, "GBase profile must retain application startup and failure logs");
    }

    @Test
    void profileShouldRequireExternalConnectionAndKeepInitializationDisabled() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-gbase-postgresql.yml"));
        Properties properties = yaml.getObject();

        assertEquals("${FB_DB_DRIVER:cn.gbase.Driver}", properties.getProperty("spring.datasource.driver-class-name"));
        assertEquals("${FB_DB_URL}", properties.getProperty("spring.datasource.url"));
        assertEquals("${FB_DB_USERNAME}", properties.getProperty("spring.datasource.username"));
        assertEquals("${FB_DB_PASSWORD}", properties.getProperty("spring.datasource.password"));
        assertEquals("${FB_DB_TYPE:gbase-postgresql}", properties.getProperty("mybatis-plus.db-type"));
        assertEquals("never", properties.getProperty("spring.sql.init.mode"));
        assertEquals("${FB_SERVER_PORT:18090}", properties.getProperty("server.port"));
    }
}
