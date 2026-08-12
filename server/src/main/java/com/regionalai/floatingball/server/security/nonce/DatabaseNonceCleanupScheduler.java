package com.regionalai.floatingball.server.security.nonce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "floating-ball.security.nonce.store",
    havingValue = "database"
)
public class DatabaseNonceCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(DatabaseNonceCleanupScheduler.class);

    private final JdbcNonceStore nonceStore;
    private final NonceStoreProperties properties;

    public DatabaseNonceCleanupScheduler(JdbcNonceStore nonceStore,
                                         NonceStoreProperties properties) {
        this.nonceStore = nonceStore;
        this.properties = properties;
    }

    @Scheduled(
        initialDelayString = "${floating-ball.security.nonce.cleanup-interval-ms:60000}",
        fixedDelayString = "${floating-ball.security.nonce.cleanup-interval-ms:60000}"
    )
    public void cleanupExpired() {
        long cutoff = System.currentTimeMillis() - properties.getCleanupGraceMs();
        try {
            nonceStore.cleanupExpired(cutoff);
        } catch (NonceStoreUnavailableException ex) {
            log.warn("request nonce cleanup failed; claim remains fail-closed: {}", ex.getMessage());
        }
    }
}
