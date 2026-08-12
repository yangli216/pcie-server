package com.regionalai.floatingball.server.security.nonce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NonceStoreHealthIndicatorTest {

    @Test
    void memoryModeIsUpWithoutDatabaseProbe() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        NonceStoreHealthIndicator indicator = new NonceStoreHealthIndicator(
            jdbcTemplate,
            properties(NonceStoreProperties.Store.MEMORY),
            new DatabaseNonceStoreAvailability()
        );

        indicator.afterSingletonsInstantiated();
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("memory", health.getDetails().get("mode"));
        verify(jdbcTemplate, never()).query(
            anyString(),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<Void>>any()
        );
    }

    @Test
    void databaseModeLightweightProbeControlsReadiness() {
        ProbeJdbcTemplate jdbcTemplate = new ProbeJdbcTemplate(null);
        DatabaseNonceStoreAvailability availability = new DatabaseNonceStoreAvailability();
        NonceStoreHealthIndicator indicator = new NonceStoreHealthIndicator(
            jdbcTemplate,
            properties(NonceStoreProperties.Store.DATABASE),
            availability
        );

        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertEquals(0, jdbcTemplate.queryCount);

        availability.markTrusted();
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("database", health.getDetails().get("mode"));
        assertEquals(1, jdbcTemplate.queryCount);

        jdbcTemplate.queryFailure = new CannotGetJdbcConnectionException("database down");
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void databaseStartupProbeVerifiesWriteDeleteAndUniqueConflictWithoutPersisting() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        PreparedStatement delete = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(NonceStoreHealthIndicator.INSERT_PROBE_SQL)).thenReturn(insert);
        when(connection.prepareStatement(NonceStoreHealthIndicator.DELETE_PROBE_SQL)).thenReturn(delete);
        when(insert.executeUpdate())
            .thenReturn(1, 1)
            .thenThrow(new SQLException("duplicate", "23505"));
        when(delete.executeUpdate()).thenReturn(1);
        DatabaseNonceStoreAvailability availability = new DatabaseNonceStoreAvailability();
        NonceStoreHealthIndicator indicator = new NonceStoreHealthIndicator(
            new ProbeJdbcTemplate(connection),
            properties(NonceStoreProperties.Store.DATABASE),
            availability
        );

        indicator.afterSingletonsInstantiated();

        assertTrue(availability.isTrusted());
        verify(insert, times(3)).executeUpdate();
        verify(delete).executeUpdate();
        verify(connection).rollback();
        verify(connection).setAutoCommit(false);
        verify(connection).setAutoCommit(true);
    }

    @Test
    void databaseStartupProbeRejectsMissingUniqueConstraint() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        PreparedStatement delete = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(NonceStoreHealthIndicator.INSERT_PROBE_SQL)).thenReturn(insert);
        when(connection.prepareStatement(NonceStoreHealthIndicator.DELETE_PROBE_SQL)).thenReturn(delete);
        when(insert.executeUpdate()).thenReturn(1);
        when(delete.executeUpdate()).thenReturn(1);
        DatabaseNonceStoreAvailability availability = new DatabaseNonceStoreAvailability();
        NonceStoreHealthIndicator indicator = new NonceStoreHealthIndicator(
            new ProbeJdbcTemplate(connection),
            properties(NonceStoreProperties.Store.DATABASE),
            availability
        );

        assertThrows(IllegalStateException.class, indicator::afterSingletonsInstantiated);
        assertFalse(availability.isTrusted());
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void cachedDeepProbeFailureControlsReadinessUntilAProbeRecovers() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        PreparedStatement delete = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(NonceStoreHealthIndicator.INSERT_PROBE_SQL)).thenReturn(insert);
        when(connection.prepareStatement(NonceStoreHealthIndicator.DELETE_PROBE_SQL)).thenReturn(delete);
        when(insert.executeUpdate()).thenReturn(1);
        when(delete.executeUpdate()).thenReturn(1);
        ProbeJdbcTemplate jdbcTemplate = new ProbeJdbcTemplate(connection);
        DatabaseNonceStoreAvailability availability = new DatabaseNonceStoreAvailability();
        NonceStoreHealthIndicator indicator = new NonceStoreHealthIndicator(
            jdbcTemplate,
            properties(NonceStoreProperties.Store.DATABASE),
            availability
        );
        JdbcNonceStore store = new JdbcNonceStore(
            jdbcTemplate,
            transactionManager(),
            availability
        );

        indicator.refreshDeepProbe();
        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertFalse(availability.isTrusted());
        assertThrows(
            NonceStoreUnavailableException.class,
            () -> store.claim("DEV001", "nonce-1", 123456789L)
        );
        assertEquals(0, jdbcTemplate.updateCount);

        reset(insert);
        when(insert.executeUpdate())
            .thenReturn(1, 1)
            .thenThrow(new SQLException("duplicate", "23505"));
        indicator.refreshDeepProbe();

        assertEquals(Status.UP, indicator.health().getStatus());
        assertTrue(availability.isTrusted());
        assertTrue(store.claim("DEV001", "nonce-2", 123456790L));
        assertEquals(1, jdbcTemplate.updateCount);
    }

    private NonceStoreProperties properties(NonceStoreProperties.Store store) {
        NonceStoreProperties properties = new NonceStoreProperties();
        properties.setStore(store);
        return properties;
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(mock(TransactionStatus.class));
        return transactionManager;
    }

    private static final class ProbeJdbcTemplate extends JdbcTemplate {
        private final Connection connection;
        private RuntimeException queryFailure;
        private int queryCount;
        private int updateCount;

        private ProbeJdbcTemplate(Connection connection) {
            this.connection = connection;
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> extractor) {
            queryCount++;
            if (queryFailure != null) {
                throw queryFailure;
            }
            return null;
        }

        @Override
        public <T> T execute(ConnectionCallback<T> action) {
            try {
                return action.doInConnection(connection);
            } catch (SQLException ex) {
                throw new CannotGetJdbcConnectionException("probe failed", ex);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            updateCount++;
            return 1;
        }
    }
}
