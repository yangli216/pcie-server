package com.regionalai.floatingball.server.common.config;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RestTemplateConfigTest {

    @Test
    void shouldBuildBoundedReusableConnectionPoolAndRestTemplate() throws Exception {
        AiHttpClientProperties properties = new AiHttpClientProperties();
        properties.setMaxTotal(32);
        properties.setMaxPerRoute(24);
        properties.setConnectionRequestTimeoutMs(250);
        properties.setValidateAfterInactivityMs(1200);
        properties.setIdleEvictSeconds(5);
        RestTemplateConfig config = new RestTemplateConfig();
        PoolingHttpClientConnectionManager manager = config.aiHttpConnectionManager(properties);

        assertThat(manager.getMaxTotal()).isEqualTo(32);
        assertThat(manager.getDefaultMaxPerRoute()).isEqualTo(24);
        assertThat(manager.getValidateAfterInactivity()).isEqualTo(1200);

        try (CloseableHttpClient client = config.aiHttpClient(
            manager, properties, 1000, 2000, false, "", 7890, "", "")) {
            HttpComponentsClientHttpRequestFactory factory = config.aiHttpRequestFactory(
                client, properties, 1000, 2000);
            RestTemplate restTemplate = config.restTemplate(new RestTemplateBuilder(), factory);

            assertThat(restTemplate.getRequestFactory()).isSameAs(factory);
        }
    }

    @Test
    void shouldCapPerRouteAtTotalConnections() {
        AiHttpClientProperties properties = new AiHttpClientProperties();
        properties.setMaxTotal(4);
        properties.setMaxPerRoute(20);

        PoolingHttpClientConnectionManager manager = new RestTemplateConfig()
            .aiHttpConnectionManager(properties);

        assertThat(manager.getMaxTotal()).isEqualTo(4);
        assertThat(manager.getDefaultMaxPerRoute()).isEqualTo(4);
        manager.close();
    }
}
