package com.regionalai.floatingball.server.security.nonce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "floating-ball.cluster.enabled", havingValue = "true")
public class JdbcNonceStore implements NonceStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcNonceStore.class);
    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    private static final long DEFAULT_CLEANUP_GRACE_MS = 300_000L;

    private static final String INSERT_SQL =
        "INSERT INTO c_security_request_nonce (id_device, nonce_value, expires_at, insert_time) "
            + "VALUES (?, ?, ?, ?)";
    private static final String DELETE_EXPIRED_KEY_SQL =
        "DELETE FROM c_security_request_nonce WHERE id_device = ? AND nonce_value = ? AND expires_at <= ?";
    private static final String DELETE_EXPIRED_SQL =
        "DELETE FROM c_security_request_nonce WHERE expires_at <= ?";
    private static final String COUNT_KEY_SQL =
        "SELECT COUNT(1) FROM c_security_request_nonce WHERE id_device = ? AND nonce_value = ?";

    private final JdbcTemplate jdbcTemplate;
    private final long cleanupGraceMs;
    private final AtomicLong nextCleanupAt = new AtomicLong(0L);

    public JdbcNonceStore(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, DEFAULT_CLEANUP_GRACE_MS);
    }

    @Autowired
    public JdbcNonceStore(
        JdbcTemplate jdbcTemplate,
        @Value("${floating-ball.security.nonce-cleanup-grace-ms:300000}") long cleanupGraceMs
    ) {
        if (cleanupGraceMs < 0L) {
            throw new IllegalArgumentException("floating-ball.security.nonce-cleanup-grace-ms must be >= 0");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.cleanupGraceMs = cleanupGraceMs;
    }

    @Override
    public boolean claim(String deviceId, String nonce, long expiresAtEpochMs) {
        long now = System.currentTimeMillis();
        cleanupExpiredBestEffort(now);

        try {
            insert(deviceId, nonce, expiresAtEpochMs, now);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return replaceExpiredClaim(deviceId, nonce, expiresAtEpochMs, now);
        } catch (DataAccessException ex) {
            return resolveUnknownClaimFailure(deviceId, nonce, ex);
        }
    }

    private boolean replaceExpiredClaim(String deviceId,
                                        String nonce,
                                        long expiresAtEpochMs,
                                        long now) {
        try {
            int deleted = jdbcTemplate.update(
                DELETE_EXPIRED_KEY_SQL,
                deviceId,
                nonce,
                cleanupCutoff(now)
            );
            if (deleted == 0) {
                return false;
            }
            try {
                insert(deviceId, nonce, expiresAtEpochMs, now);
                return true;
            } catch (DuplicateKeyException concurrentClaim) {
                return false;
            } catch (DataAccessException ex) {
                return resolveUnknownClaimFailure(deviceId, nonce, ex);
            }
        } catch (DataAccessException ex) {
            throw unavailable(ex);
        }
    }

    private boolean resolveUnknownClaimFailure(String deviceId,
                                               String nonce,
                                               DataAccessException claimFailure) {
        try {
            Integer count = jdbcTemplate.queryForObject(COUNT_KEY_SQL, Integer.class, deviceId, nonce);
            if (count != null && count.intValue() > 0) {
                return false;
            }
        } catch (DataAccessException lookupFailure) {
            claimFailure.addSuppressed(lookupFailure);
        }
        throw unavailable(claimFailure);
    }

    private void insert(String deviceId, String nonce, long expiresAtEpochMs, long now) {
        jdbcTemplate.update(
            INSERT_SQL,
            deviceId,
            nonce,
            new Timestamp(expiresAtEpochMs),
            new Timestamp(now)
        );
    }

    private void cleanupExpiredBestEffort(long now) {
        long scheduledAt = nextCleanupAt.get();
        if (now < scheduledAt || !nextCleanupAt.compareAndSet(scheduledAt, now + CLEANUP_INTERVAL_MS)) {
            return;
        }
        try {
            jdbcTemplate.update(DELETE_EXPIRED_SQL, cleanupCutoff(now));
        } catch (DataAccessException ex) {
            log.warn("request nonce cleanup failed; replay protection remains fail-closed on claim: {}", ex.getMessage());
        }
    }

    private NonceStoreUnavailableException unavailable(DataAccessException cause) {
        return new NonceStoreUnavailableException("request nonce store unavailable", cause);
    }

    private Timestamp cleanupCutoff(long now) {
        return new Timestamp(now - cleanupGraceMs);
    }
}
