package com.regionalai.floatingball.server.security.nonce;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(NonceStoreProperties.class)
public class NonceStoreConfiguration {
}
