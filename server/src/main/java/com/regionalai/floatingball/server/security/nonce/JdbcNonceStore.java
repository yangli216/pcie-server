package com.regionalai.floatingball.server.security.nonce;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;

@Component
@ConditionalOnProperty(
    name = "floating-ball.security.nonce.store",
    havingValue = "database"
)
public class JdbcNonceStore implements NonceStore {

    static final String INSERT_SQL =
        "INSERT INTO c_ai_request_nonce (id_device, nonce_hash, expires_at, insert_time) "
            + "VALUES (?, ?, ?, ?)";
    static final String COUNT_KEY_SQL =
        "SELECT COUNT(1) FROM c_ai_request_nonce WHERE id_device = ? AND nonce_hash = ?";
    static final String DELETE_EXPIRED_SQL =
        "DELETE FROM c_ai_request_nonce WHERE expires_at <= ?";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final DatabaseNonceStoreAvailability availability;

    public JdbcNonceStore(JdbcTemplate jdbcTemplate,
                          PlatformTransactionManager transactionManager,
                          DatabaseNonceStoreAvailability availability) {
        this.jdbcTemplate = jdbcTemplate;
        this.availability = availability;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean claim(String deviceId, String nonce, long expiresAtEpochMs) {
        availability.requireTrusted();
        String nonceHash = sha256Hex(nonce);
        try {
            Boolean inserted = transactionTemplate.execute(status -> {
                jdbcTemplate.update(
                    INSERT_SQL,
                    deviceId,
                    nonceHash,
                    expiresAtEpochMs,
                    new Timestamp(System.currentTimeMillis())
                );
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(inserted);
        } catch (DuplicateKeyException duplicate) {
            return false;
        } catch (NonceStoreUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException claimFailure) {
            return resolveUnknownClaimFailure(deviceId, nonceHash, claimFailure);
        }
    }

    int cleanupExpired(long cutoffEpochMs) {
        try {
            Integer deleted = transactionTemplate.execute(status ->
                jdbcTemplate.update(DELETE_EXPIRED_SQL, cutoffEpochMs)
            );
            return deleted == null ? 0 : deleted.intValue();
        } catch (RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    private boolean resolveUnknownClaimFailure(String deviceId,
                                               String nonceHash,
                                               RuntimeException claimFailure) {
        try {
            Integer count = transactionTemplate.execute(status ->
                jdbcTemplate.queryForObject(
                    COUNT_KEY_SQL,
                    Integer.class,
                    deviceId,
                    nonceHash
                )
            );
            if (count != null && count.intValue() > 0) {
                return false;
            }
        } catch (RuntimeException lookupFailure) {
            claimFailure.addSuppressed(lookupFailure);
        }
        throw unavailable(claimFailure);
    }

    static String sha256Hex(String nonce) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(nonce.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                value.append(String.format("%02x", item));
            }
            return value.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 digest unavailable", ex);
        }
    }

    private NonceStoreUnavailableException unavailable(RuntimeException cause) {
        return new NonceStoreUnavailableException("request nonce store unavailable", cause);
    }
}
