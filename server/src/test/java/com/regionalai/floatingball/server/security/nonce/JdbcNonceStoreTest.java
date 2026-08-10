package com.regionalai.floatingball.server.security.nonce;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcNonceStoreTest {

    @Test
    void claim_insertSucceeds_returnsTrue() {
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate((sql, args) -> 1);

        assertTrue(new JdbcNonceStore(jdbcTemplate).claim(
            "DEV001", "nonce-1", System.currentTimeMillis() + 60_000L));
    }

    @Test
    void claim_duplicateUnexpiredPair_returnsFalse() {
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate((sql, args) -> {
            if (sql.startsWith("INSERT")) {
                throw new DuplicateKeyException("pk_c_security_req_nonce");
            }
            return 0;
        });

        assertFalse(new JdbcNonceStore(jdbcTemplate).claim(
            "DEV001", "nonce-1", System.currentTimeMillis() + 60_000L));
    }

    @Test
    void claim_expiredPairDeleted_retriesAtomicInsert() {
        AtomicInteger insertAttempts = new AtomicInteger();
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate((sql, args) -> {
            if (sql.startsWith("INSERT") && insertAttempts.getAndIncrement() == 0) {
                throw new DuplicateKeyException("pk_c_security_req_nonce");
            }
            if (sql.contains("id_device = ?")) {
                return 1;
            }
            return 1;
        });

        assertTrue(new JdbcNonceStore(jdbcTemplate).claim(
            "DEV001", "nonce-1", System.currentTimeMillis() + 60_000L));
    }

    @Test
    void claim_databaseFailure_failsClosed() {
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate((sql, args) -> {
            if (sql.startsWith("INSERT")) {
                throw new DataAccessResourceFailureException("database down");
            }
            return 0;
        });

        assertThrows(
            NonceStoreUnavailableException.class,
            () -> new JdbcNonceStore(jdbcTemplate).claim(
                "DEV001", "nonce-1", System.currentTimeMillis() + 60_000L)
        );
    }

    @Test
    void claim_nonStandardIntegrityFailureWithExistingKey_isReplay() {
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate(
            (sql, args) -> {
                if (sql.startsWith("INSERT")) {
                    throw new DataAccessResourceFailureException("dm8 untranslated constraint failure");
                }
                return 0;
            },
            (sql, args) -> 1
        );

        assertFalse(new JdbcNonceStore(jdbcTemplate).claim(
            "DEV001", "nonce-1", System.currentTimeMillis() + 60_000L));
    }

    @Test
    void claim_nonStandardFailureAndLookupFailure_failsClosed() {
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate(
            (sql, args) -> {
                if (sql.startsWith("INSERT")) {
                    throw new DataAccessResourceFailureException("claim failed");
                }
                return 0;
            },
            (sql, args) -> {
                throw new DataAccessResourceFailureException("lookup failed");
            }
        );

        assertThrows(
            NonceStoreUnavailableException.class,
            () -> new JdbcNonceStore(jdbcTemplate).claim(
                "DEV001", "nonce-1", System.currentTimeMillis() + 60_000L)
        );
    }

    @Test
    void claim_opportunisticCleanupKeepsConfiguredClockSkewGrace() {
        long graceMs = 123_000L;
        AtomicReference<Timestamp> cleanupCutoff = new AtomicReference<Timestamp>();
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate((sql, args) -> {
            if (sql.equals("DELETE FROM c_security_request_nonce WHERE expires_at <= ?")) {
                cleanupCutoff.set((Timestamp) args[0]);
            }
            return 1;
        });
        long startedAt = System.currentTimeMillis();

        assertTrue(new JdbcNonceStore(jdbcTemplate, graceMs).claim(
            "DEV001", "nonce-1", System.currentTimeMillis() + 60_000L));

        long finishedAt = System.currentTimeMillis();
        assertNotNull(cleanupCutoff.get());
        assertTrue(cleanupCutoff.get().getTime() >= startedAt - graceMs);
        assertTrue(cleanupCutoff.get().getTime() <= finishedAt - graceMs);
    }

    @Test
    void constructor_negativeCleanupGraceIsRejected() {
        JdbcTemplate jdbcTemplate = new StubJdbcTemplate((sql, args) -> 1);

        assertThrows(IllegalArgumentException.class, () -> new JdbcNonceStore(jdbcTemplate, -1L));
    }

    private interface SqlUpdate {
        int execute(String sql, Object[] args);
    }

    private interface SqlQuery {
        Integer execute(String sql, Object[] args);
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final SqlUpdate update;
        private final SqlQuery query;

        private StubJdbcTemplate(SqlUpdate update) {
            this(update, (sql, args) -> 0);
        }

        private StubJdbcTemplate(SqlUpdate update, SqlQuery query) {
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
