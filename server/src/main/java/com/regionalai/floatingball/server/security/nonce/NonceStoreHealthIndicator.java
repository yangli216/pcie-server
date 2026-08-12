package com.regionalai.floatingball.server.security.nonce;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component("nonceStore")
public class NonceStoreHealthIndicator implements HealthIndicator, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(NonceStoreHealthIndicator.class);

    static final String LIGHTWEIGHT_PROBE_SQL =
        "SELECT id_device, nonce_hash, expires_at, insert_time "
            + "FROM c_ai_request_nonce WHERE 1 = 0";
    static final String INSERT_PROBE_SQL =
        "INSERT INTO c_ai_request_nonce (id_device, nonce_hash, expires_at, insert_time) "
            + "VALUES (?, ?, ?, ?)";
    static final String DELETE_PROBE_SQL =
        "DELETE FROM c_ai_request_nonce WHERE id_device = ? AND nonce_hash = ?";
    static final String REMEDIATION_MESSAGE =
        "Database nonce store verification failed; create c_ai_request_nonce with primary key "
            + "(id_device, nonce_hash) and grant SELECT, INSERT, DELETE before starting the service";

    private final JdbcTemplate jdbcTemplate;
    private final NonceStoreProperties properties;
    private final DatabaseNonceStoreAvailability availability;
    private final AtomicReference<String> deepProbeFailure = new AtomicReference<String>();
    private final AtomicLong lastDeepProbeEpochMs = new AtomicLong(0L);

    public NonceStoreHealthIndicator(JdbcTemplate jdbcTemplate,
                                     NonceStoreProperties properties,
                                     DatabaseNonceStoreAvailability availability) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.availability = availability;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isDatabase()) {
            return;
        }
        try {
            verifyDatabaseStoreAtStartup();
            markDeepProbeSuccess();
        } catch (RuntimeException ex) {
            markDeepProbeFailure(ex);
            throw new IllegalStateException(REMEDIATION_MESSAGE, ex);
        }
    }

    @Override
    public Health health() {
        if (!properties.isDatabase()) {
            return Health.up().withDetail("mode", "memory").build();
        }
        if (!availability.isTrusted()) {
            String reason = deepProbeFailure.get();
            return Health.down()
                .withDetail("mode", "database")
                .withDetail(
                    "reason",
                    reason == null ? "database nonce store has not passed an integrity probe" : reason
                )
                .withDetail("lastDeepProbeEpochMs", lastDeepProbeEpochMs.get())
                .withDetail("remediation", REMEDIATION_MESSAGE)
                .build();
        }
        try {
            jdbcTemplate.query(
                LIGHTWEIGHT_PROBE_SQL,
                (ResultSetExtractor<Void>) resultSet -> null
            );
            return Health.up()
                .withDetail("mode", "database")
                .withDetail("lastDeepProbeEpochMs", lastDeepProbeEpochMs.get())
                .build();
        } catch (RuntimeException ex) {
            return Health.down()
                .withDetail("mode", "database")
                .withDetail("remediation", REMEDIATION_MESSAGE)
                .build();
        }
    }

    @Scheduled(
        initialDelayString = "${floating-ball.security.nonce.deep-probe-interval-ms:300000}",
        fixedDelayString = "${floating-ball.security.nonce.deep-probe-interval-ms:300000}"
    )
    public void refreshDeepProbe() {
        if (!properties.isDatabase()) {
            return;
        }
        try {
            verifyDatabaseStoreAtStartup();
            markDeepProbeSuccess();
        } catch (RuntimeException ex) {
            markDeepProbeFailure(ex);
            log.error("database nonce deep probe failed; readiness will remain DOWN until a later probe succeeds", ex);
        }
    }

    private void markDeepProbeSuccess() {
        lastDeepProbeEpochMs.set(System.currentTimeMillis());
        deepProbeFailure.set(null);
        availability.markTrusted();
    }

    private void markDeepProbeFailure(RuntimeException ex) {
        String reason = ex.getMessage() == null
            ? ex.getClass().getSimpleName()
            : ex.getMessage();
        availability.markUntrusted();
        lastDeepProbeEpochMs.set(System.currentTimeMillis());
        deepProbeFailure.set(reason);
    }

    private void verifyDatabaseStoreAtStartup() {
        jdbcTemplate.query(
            LIGHTWEIGHT_PROBE_SQL,
            (ResultSetExtractor<Void>) resultSet -> null
        );
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            verifyWriteDeleteAndUniqueConstraint(connection);
            return null;
        });
    }

    private void verifyWriteDeleteAndUniqueConstraint(Connection connection) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        Throwable failure = null;
        if (!originalAutoCommit) {
            throw new SQLException("Nonce startup probe requires an auto-commit connection");
        }
        connection.setAutoCommit(false);
        try {
            String probeId = UUID.randomUUID().toString().replace("-", "");
            String deviceId = "nonce-probe-" + probeId.substring(0, 16);
            String peerDeviceId = "nonce-peer-" + probeId.substring(0, 16);
            String nonceHash = JdbcNonceStore.sha256Hex("probe-" + probeId);
            long now = System.currentTimeMillis();

            insertProbe(connection, deviceId, nonceHash, now + 60_000L, now);
            insertProbe(connection, peerDeviceId, nonceHash, now + 60_000L, now);
            deleteProbe(connection, peerDeviceId, nonceHash);

            boolean duplicateRejected = false;
            try {
                insertProbe(connection, deviceId, nonceHash, now + 120_000L, now + 1L);
            } catch (SQLException ex) {
                if (!isUniqueViolation(ex)) {
                    throw ex;
                }
                duplicateRejected = true;
            }
            if (!duplicateRejected) {
                throw new SQLException("Second nonce write probe was accepted");
            }
        } catch (SQLException ex) {
            failure = ex;
            throw ex;
        } catch (RuntimeException ex) {
            failure = ex;
            throw ex;
        } finally {
            restoreConnection(connection, failure);
        }
    }

    private void insertProbe(Connection connection,
                             String deviceId,
                             String nonceHash,
                             long expiresAt,
                             long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PROBE_SQL)) {
            statement.setString(1, deviceId);
            statement.setString(2, nonceHash);
            statement.setLong(3, expiresAt);
            statement.setTimestamp(4, new Timestamp(now));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Nonce write probe did not insert exactly one row");
            }
        }
    }

    private void deleteProbe(Connection connection,
                             String deviceId,
                             String nonceHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_PROBE_SQL)) {
            statement.setString(1, deviceId);
            statement.setString(2, nonceHash);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Nonce delete probe did not delete exactly one row");
            }
        }
    }

    private boolean isUniqueViolation(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if ("23505".equals(current.getSQLState())) {
                return true;
            }
            if (current.getErrorCode() == 1 || current.getErrorCode() == -6602) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("unique") || normalized.contains("duplicate")
                    || normalized.contains("唯一") || normalized.contains("重复")) {
                    return true;
                }
            }
            current = current.getNextException();
        }
        return false;
    }

    private void restoreConnection(Connection connection,
                                   Throwable originalFailure) throws SQLException {
        SQLException cleanupFailure = null;
        try {
            connection.rollback();
        } catch (SQLException ex) {
            cleanupFailure = ex;
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ex) {
            if (cleanupFailure == null) {
                cleanupFailure = ex;
            } else {
                cleanupFailure.addSuppressed(ex);
            }
        }
        if (cleanupFailure != null) {
            if (originalFailure != null) {
                originalFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }
}
