package com.regionalai.floatingball.server.security.nonce;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseNonceCleanupSchedulerTest {

    @Test
    void cleanupUsesConfiguredGraceAndDoesNotPropagateDatabaseFailure() {
        JdbcNonceStore store = mock(JdbcNonceStore.class);
        NonceStoreProperties properties = new NonceStoreProperties();
        properties.setStore(NonceStoreProperties.Store.DATABASE);
        properties.setCleanupGraceMs(300_000L);
        long before = System.currentTimeMillis() - properties.getCleanupGraceMs();
        when(store.cleanupExpired(org.mockito.ArgumentMatchers.anyLong()))
            .thenThrow(new NonceStoreUnavailableException("down", new IllegalStateException("db down")));

        new DatabaseNonceCleanupScheduler(store, properties).cleanupExpired();

        long after = System.currentTimeMillis() - properties.getCleanupGraceMs();
        verify(store).cleanupExpired(longThat(cutoff -> cutoff >= before && cutoff <= after));
    }
}
