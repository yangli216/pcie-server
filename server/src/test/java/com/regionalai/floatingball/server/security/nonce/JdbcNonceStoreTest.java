package com.regionalai.floatingball.server.security.nonce;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcNonceStoreTest {

    @Test
    void claim_insertsNonceHashInRequiresNewTransaction() {
        AtomicReference<Object[]> insertedArgs = new AtomicReference<Object[]>();
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate(
            (sql, args) -> {
                insertedArgs.set(args);
                return 1;
            },
            (sql, args) -> 0
        );
        PlatformTransactionManager transactionManager = transactionManager();

        boolean claimed = store(jdbcTemplate, transactionManager)
            .claim("DEV001", "nonce-1", 123456789L);

        assertTrue(claimed);
        assertEquals("DEV001", insertedArgs.get()[0]);
        assertNotEquals("nonce-1", insertedArgs.get()[1]);
        assertEquals(64, String.valueOf(insertedArgs.get()[1]).length());
        assertEquals(123456789L, insertedArgs.get()[2]);
        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
    }

    @Test
    void claim_duplicateKeyReturnsReplay() {
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate(
            (sql, args) -> { throw new DuplicateKeyException("duplicate"); },
            (sql, args) -> 0
        );

        assertFalse(store(jdbcTemplate, transactionManager())
            .claim("DEV001", "nonce-1", 123456789L));
    }

    @Test
    void claim_ambiguousFailureWithPersistedHashFailsClosedAsReplay() {
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate(
            (sql, args) -> { throw new DataAccessResourceFailureException("commit unknown"); },
            (sql, args) -> 1
        );

        assertFalse(store(jdbcTemplate, transactionManager())
            .claim("DEV001", "nonce-1", 123456789L));
    }

    @Test
    void claim_databaseFailureWithoutPersistedHashIsUnavailable() {
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate(
            (sql, args) -> { throw new DataAccessResourceFailureException("database down"); },
            (sql, args) -> 0
        );

        assertThrows(
            NonceStoreUnavailableException.class,
            () -> store(jdbcTemplate, transactionManager())
                .claim("DEV001", "nonce-1", 123456789L)
        );
    }

    @Test
    void cleanupExpiredUsesEpochMillisecondCutoff() {
        AtomicReference<Object> cutoff = new AtomicReference<Object>();
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate(
            (sql, args) -> {
                cutoff.set(args[0]);
                return 7;
            },
            (sql, args) -> 0
        );

        int deleted = store(jdbcTemplate, transactionManager())
            .cleanupExpired(987654321L);

        assertEquals(7, deleted);
        assertEquals(987654321L, cutoff.get());
    }

    @Test
    void claim_untrustedStoreFailsBeforeStartingTransactionOrInsert() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = transactionManager();
        DatabaseNonceStoreAvailability availability = new DatabaseNonceStoreAvailability();
        JdbcNonceStore store = new JdbcNonceStore(jdbcTemplate, transactionManager, availability);

        assertThrows(
            NonceStoreUnavailableException.class,
            () -> store.claim("DEV001", "nonce-1", 123456789L)
        );

        verify(transactionManager, never()).getTransaction(any(TransactionDefinition.class));
        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }

    private JdbcNonceStore store(JdbcTemplate jdbcTemplate,
                                 PlatformTransactionManager transactionManager) {
        DatabaseNonceStoreAvailability availability = new DatabaseNonceStoreAvailability();
        availability.markTrusted();
        return new JdbcNonceStore(jdbcTemplate, transactionManager, availability);
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(mock(TransactionStatus.class));
        return transactionManager;
    }

    private interface UpdateOperation {
        int execute(String sql, Object[] args);
    }

    private interface QueryOperation {
        Integer execute(String sql, Object[] args);
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final UpdateOperation update;
        private final QueryOperation query;

        private StubJdbcTemplate(UpdateOperation update, QueryOperation query) {
            this.update = update;
            this.query = query;
        }

        @Override
        public int update(String sql, Object... args) {
            return update.execute(sql, args);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(query.execute(sql, args));
        }
    }
}
