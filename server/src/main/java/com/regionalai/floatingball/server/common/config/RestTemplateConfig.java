package com.regionalai.floatingball.server.common.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(AiHttpClientProperties.class)
public class RestTemplateConfig {

    @Bean
    public PoolingHttpClientConnectionManager aiHttpConnectionManager(AiHttpClientProperties properties) {
        PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
        int maxTotal = Math.max(1, properties.getMaxTotal());
        manager.setMaxTotal(maxTotal);
        manager.setDefaultMaxPerRoute(Math.max(1, Math.min(maxTotal, properties.getMaxPerRoute())));
        manager.setValidateAfterInactivity(Math.max(0, properties.getValidateAfterInactivityMs()));
        return manager;
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient aiHttpClient(PoolingHttpClientConnectionManager connectionManager,
                                            AiHttpClientProperties properties,
                                            @Value("${floating-ball.ai.connect-timeout-ms}") int connectTimeoutMs,
                                            @Value("${floating-ball.ai.read-timeout-ms}") int readTimeoutMs,
                                            @Value("${floating-ball.ai.proxy.enabled:false}") boolean proxyEnabled,
                                            @Value("${floating-ball.ai.proxy.host:}") String proxyHost,
                                            @Value("${floating-ball.ai.proxy.port:7890}") int proxyPort,
                                            @Value("${floating-ball.ai.proxy.username:}") String proxyUsername,
                                            @Value("${floating-ball.ai.proxy.password:}") String proxyPassword) {
        RequestConfig.Builder requestConfig = RequestConfig.custom()
            .setConnectTimeout(Math.max(1, connectTimeoutMs))
            .setSocketTimeout(Math.max(1, readTimeoutMs))
            .setConnectionRequestTimeout(Math.max(1, properties.getConnectionRequestTimeoutMs()));

        org.apache.http.impl.client.HttpClientBuilder builder = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig.build())
            .disableAutomaticRetries()
            .evictExpiredConnections()
            .evictIdleConnections(Math.max(1, properties.getIdleEvictSeconds()), TimeUnit.SECONDS);

        if (proxyEnabled && StringUtils.hasText(proxyHost)) {
            HttpHost proxy = new HttpHost(proxyHost.trim(), proxyPort);
            requestConfig.setProxy(proxy);
            builder.setDefaultRequestConfig(requestConfig.build());
            if (StringUtils.hasText(proxyUsername)) {
                CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                    new AuthScope(proxyHost.trim(), proxyPort),
                    new UsernamePasswordCredentials(proxyUsername.trim(), proxyPassword == null ? "" : proxyPassword)
                );
                builder.setDefaultCredentialsProvider(credentialsProvider);
            }
        }
        return builder.build();
    }

    @Bean
    public HttpComponentsClientHttpRequestFactory aiHttpRequestFactory(
        CloseableHttpClient aiHttpClient,
        AiHttpClientProperties properties,
        @Value("${floating-ball.ai.connect-timeout-ms}") int connectTimeoutMs,
        @Value("${floating-ball.ai.read-timeout-ms}") int readTimeoutMs) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(aiHttpClient);
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        factory.setConnectionRequestTimeout(Math.max(1, properties.getConnectionRequestTimeoutMs()));
        return factory;
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder,
                                     HttpComponentsClientHttpRequestFactory aiHttpRequestFactory) {
        return builder.requestFactory(() -> aiHttpRequestFactory).build();
    }
}
