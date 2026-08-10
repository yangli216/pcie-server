package com.regionalai.floatingball.server.security.nonce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NonceStoreHealthIndicatorTest {

    @Test
    void health_standaloneModeIsUpWithoutDatabaseProbe() throws SQLException {
        Connection connection = mock(Connection.class);
        JdbcTemplate jdbcTemplate = new CallbackJdbcTemplate(connection);
        NonceStoreHealthIndicator indicator = new NonceStoreHealthIndicator(jdbcTemplate, false);

        indicator.afterSingletonsInstantiated();
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("standalone", health.getDetails().get("mode"));
        verify(connection, never()).getMetaData();
    }

    @Test
    void h2ExactCompositePrimaryKey_passesStartupAndReadinessWithoutPersistingProbeRows() {
        JdbcTemplate jdbcTemplate = h2Template("pk", "sa", "");
        createNonceTable(jdbcTemplate,
            "CONSTRAINT pk_nonce PRIMARY KEY (id_device, nonce_value)");
        NonceStoreHealthIndicator indicator = new NonceStoreHealthIndicator(jdbcTemplate, true);

        indicator.afterSingletonsInstantiated();
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("cluster", health.getDetails().get("mode"));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM c_security_request_nonce", Integer.class));
    }

    @Test
    void h2ExactCompositeUniqueIndex_isAccepted() {
        JdbcTemplate jdbcTemplate = h2Template("unique", "sa", "");
        createNonceTable(jdbcTemplate, "");
        jdbcTemplate.execute(
            "CREATE UNIQUE INDEX uk_nonce ON c_security_request_nonce (id_device, nonce_value)");

        Health health = new NonceStoreHealthIndicator(jdbcTemplate, true).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM c_security_request_nonce", Integer.class));
    }

    @Test
    void h2ConstraintWithExtraColumn_isRejectedAndFailsStartupClosed() {
        JdbcTemplate jdbcTemplate = h2Template("extra", "sa", "");
        createNonceTable(jdbcTemplate,
            "CONSTRAINT uk_nonce UNIQUE (id_device, nonce_value, expires_at)");
        NonceStoreHealthIndicator indicator = new NonceStoreHealthIndicator(jdbcTemplate, true);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertThrows(IllegalStateException.class, indicator::afterSingletonsInstantiated);
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM c_security_request_nonce", Integer.class));
    }

    @Test
    void h2ClusterUserWithoutInsertPermission_isDownAndFailsStartupClosed() {
        String database = "permission_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = h2Template(database, "sa", "");
        createNonceTable(admin,
            "CONSTRAINT pk_nonce PRIMARY KEY (id_device, nonce_value)");
        admin.execute("CREATE USER nonce_reader PASSWORD 'secret'");
        admin.execute("GRANT SELECT ON c_security_request_nonce TO nonce_reader");
        JdbcTemplate readOnly = h2Template(database, "nonce_reader", "secret");
        NonceStoreHealthIndicator indicator = new NonceStoreHealthIndicator(readOnly, true);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertThrows(IllegalStateException.class, indicator::afterSingletonsInstantiated);
        assertEquals(0, admin.queryForObject(
            "SELECT COUNT(1) FROM c_security_request_nonce", Integer.class));
    }

    @Test
    void metadataClaimWithoutActualConflict_isDownAndRollsBack() throws SQLException {
        Connection connection = mockProbeConnection();
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(any(String.class))).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        Health health = new NonceStoreHealthIndicator(
            new CallbackJdbcTemplate(connection), true).health();

        assertEquals(Status.DOWN, health.getStatus());
        verify(statement, times(2)).executeUpdate();
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).commit();
    }

    @Test
    void oracleGaussAndDamengUniqueViolations_areRecognized() throws SQLException {
        List<SQLException> vendorFailures = Arrays.asList(
            new SQLException("ORA-00001", null, 1),
            new SQLException("duplicate key value violates unique constraint", "23505"),
            new SQLException("违反唯一性约束", null, -6602)
        );

        for (SQLException vendorFailure : vendorFailures) {
            Connection connection = mockProbeConnection();
            PreparedStatement first = mock(PreparedStatement.class);
            PreparedStatement second = mock(PreparedStatement.class);
            when(connection.prepareStatement(any(String.class))).thenReturn(first, second);
            when(first.executeUpdate()).thenReturn(1);
            when(second.executeUpdate()).thenThrow(vendorFailure);

            Health health = new NonceStoreHealthIndicator(
                new CallbackJdbcTemplate(connection), true).health();

            assertEquals(Status.UP, health.getStatus());
            verify(connection).rollback();
            verify(connection).setAutoCommit(true);
            verify(connection, never()).commit();
        }
    }

    @Test
    void h2StyleUniqueViolation_butRollbackFailureIsDown() throws SQLException {
        Connection connection = mockProbeConnection();
        PreparedStatement first = mock(PreparedStatement.class);
        PreparedStatement second = mock(PreparedStatement.class);
        when(connection.prepareStatement(any(String.class))).thenReturn(first, second);
        when(first.executeUpdate()).thenReturn(1);
        when(second.executeUpdate()).thenThrow(
            new SQLIntegrityConstraintViolationException("unique", "23505"));
        SQLException rollbackFailure = new SQLException("rollback unavailable");
        org.mockito.Mockito.doThrow(rollbackFailure).when(connection).rollback();

        Health health = new NonceStoreHealthIndicator(
            new CallbackJdbcTemplate(connection), true).health();

        assertEquals(Status.DOWN, health.getStatus());
        verify(connection).setAutoCommit(true);
    }

    private JdbcTemplate h2Template(String database, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return new JdbcTemplate(dataSource);
    }

    private void createNonceTable(JdbcTemplate jdbcTemplate, String constraint) {
        String separator = constraint.isEmpty() ? "" : ", ";
        jdbcTemplate.execute(
            "CREATE TABLE c_security_request_nonce ("
                + "id_device VARCHAR(32) NOT NULL, "
                + "nonce_value VARCHAR(64) NOT NULL, "
                + "expires_at TIMESTAMP NOT NULL, "
                + "insert_time TIMESTAMP NOT NULL"
                + separator + constraint + ")");
    }

    private Connection mockProbeConnection() throws SQLException {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn(null);
        when(connection.getSchema()).thenReturn("PUBLIC");
        when(connection.getAutoCommit()).thenReturn(true);
        when(metadata.getUserName()).thenReturn("SA");
        when(metadata.getPrimaryKeys(
            nullable(String.class), nullable(String.class), any(String.class)))
            .thenAnswer(invocation -> {
                String schema = invocation.getArgument(1);
                String table = invocation.getArgument(2);
                if ("PUBLIC".equals(schema) && "C_SECURITY_REQUEST_NONCE".equals(table)) {
                    return primaryKeyRows();
                }
                return resultSet(Collections.<MetadataRow>emptyList());
            });
        when(metadata.getIndexInfo(
            nullable(String.class), nullable(String.class), any(String.class),
            anyBoolean(), anyBoolean()))
            .thenAnswer(invocation -> resultSet(Collections.<MetadataRow>emptyList()));
        return connection;
    }

    private ResultSet primaryKeyRows() throws SQLException {
        return resultSet(Arrays.asList(
            new MetadataRow("PK_NONCE", "ID_DEVICE", 1),
            new MetadataRow("PK_NONCE", "NONCE_VALUE", 2)
        ));
    }

    private ResultSet resultSet(List<MetadataRow> rows) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(resultSet.next()).thenAnswer(invocation -> index.incrementAndGet() < rows.size());
        when(resultSet.getString(any(String.class))).thenAnswer(invocation -> {
            MetadataRow row = rows.get(index.get());
            String column = invocation.getArgument(0);
            if ("PK_NAME".equals(column) || "INDEX_NAME".equals(column)) {
                return row.name;
            }
            if ("COLUMN_NAME".equals(column)) {
                return row.column;
            }
            return null;
        });
        when(resultSet.getInt(any(String.class)))
            .thenAnswer(invocation -> rows.get(index.get()).position);
        when(resultSet.getBoolean(any(String.class))).thenReturn(false);
        return resultSet;
    }

    private static final class MetadataRow {
        private final String name;
        private final String column;
        private final int position;

        private MetadataRow(String name, String column, int position) {
            this.name = name;
            this.column = column;
            this.position = position;
        }
    }

    private static final class CallbackJdbcTemplate extends JdbcTemplate {
        private final Connection connection;

        private CallbackJdbcTemplate(Connection connection) {
            this.connection = connection;
        }

        @Override
        public <T> T execute(ConnectionCallback<T> action) {
            try {
                return action.doInConnection(connection);
            } catch (SQLException ex) {
                throw new org.springframework.jdbc.CannotGetJdbcConnectionException(
                    "probe failed", ex);
            }
        }
    }
}
