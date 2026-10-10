package com.regionalai.floatingball.server.modules.knowledge.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "floating-ball.knowledge-retrieval")
public class KnowledgeRetrievalProperties {
    private boolean enabled;
    private String catalogFile = "";
    private int connectTimeoutMs = 10000;
    private int readTimeoutMs = 30000;
    private Map<String, Connection> connections = new LinkedHashMap<String, Connection>();

    @Data
    public static class Connection {
        private String baseUrl;
        private String apiKey;
        @Override public String toString() { return "KnowledgeConnection[credentials redacted]"; }
    }

    @Override public String toString() { return "KnowledgeRetrievalProperties[credentials redacted]"; }
}
