package com.regionalai.floatingball.server.security.nonce;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component("nonceStore")
public class NonceStoreHealthIndicator implements HealthIndicator, SmartInitializingSingleton {

    private static final String TABLE_NAME = "c_security_request_nonce";
    private static final List<String> UNIQUE_COLUMNS =
        Collections.unmodifiableList(Arrays.asList("ID_DEVICE", "NONCE_VALUE"));
    private static final String PROBE_SQL =
        "INSERT INTO c_security_request_nonce "
            + "(id_device, nonce_value, expires_at, insert_time) VALUES (?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final boolean clusterEnabled;

    public NonceStoreHealthIndicator(
        JdbcTemplate jdbcTemplate,
        @Value("${floating-ball.cluster.enabled:false}") boolean clusterEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.clusterEnabled = clusterEnabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!clusterEnabled) {
            return;
        }
        try {
            verifyClusterStore();
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Cluster nonce store readiness verification failed; refusing to start", ex);
        }
    }

    @Override
    public Health health() {
        if (!clusterEnabled) {
            return Health.up().withDetail("mode", "standalone").build();
        }
        try {
            verifyClusterStore();
            return Health.up().withDetail("mode", "cluster").build();
        } catch (Exception ex) {
            return Health.down(ex).withDetail("mode", "cluster").build();
        }
    }

    private void verifyClusterStore() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            verifyExactUniqueConstraint(connection);
            verifyRollbackWriteProbe(connection);
            return null;
        });
    }

    private void verifyExactUniqueConstraint(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (TableRef table : candidateTables(connection, metadata)) {
            if (hasExactPrimaryKey(metadata, table) || hasExactUniqueIndex(metadata, table)) {
                return;
            }
        }
        throw new SQLException(
            "Missing exact primary key or unique constraint on c_security_request_nonce "
                + "(id_device, nonce_value)");
    }

    private Set<TableRef> candidateTables(Connection connection, DatabaseMetaData metadata)
        throws SQLException {
        Set<String> catalogs = new LinkedHashSet<String>();
        catalogs.add(connection.getCatalog());
        catalogs.add(null);

        Set<String> schemas = new LinkedHashSet<String>();
        String currentSchema = currentSchema(connection);
        addCaseVariants(schemas, currentSchema);
        addCaseVariants(schemas, metadata.getUserName());
        schemas.add(null);

        Set<String> tableNames = new LinkedHashSet<String>();
        addCaseVariants(tableNames, TABLE_NAME);

        Set<TableRef> candidates = new LinkedHashSet<TableRef>();
        for (String catalog : catalogs) {
            for (String schema : schemas) {
                for (String tableName : tableNames) {
                    candidates.add(new TableRef(catalog, schema, tableName));
                }
            }
        }
        return candidates;
    }

    private String currentSchema(Connection connection) throws SQLException {
        try {
            return connection.getSchema();
        } catch (SQLFeatureNotSupportedException ex) {
            return null;
        } catch (UnsupportedOperationException ex) {
            return null;
        } catch (AbstractMethodError ex) {
            return null;
        }
    }

    private void addCaseVariants(Set<String> values, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        values.add(value);
        values.add(value.toUpperCase(Locale.ROOT));
        values.add(value.toLowerCase(Locale.ROOT));
    }

    private boolean hasExactPrimaryKey(DatabaseMetaData metadata, TableRef table) throws SQLException {
        Map<String, Map<Integer, String>> keys = new LinkedHashMap<String, Map<Integer, String>>();
        try (ResultSet rows = metadata.getPrimaryKeys(table.catalog, table.schema, table.tableName)) {
            while (rows.next()) {
                String name = rows.getString("PK_NAME");
                if (name == null) {
                    name = "<unnamed-primary-key>";
                }
                addColumn(keys, name, rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME"));
            }
        }
        return containsExactColumns(keys);
    }

    private boolean hasExactUniqueIndex(DatabaseMetaData metadata, TableRef table) throws SQLException {
        Map<String, Map<Integer, String>> indexes = new LinkedHashMap<String, Map<Integer, String>>();
        try (ResultSet rows = metadata.getIndexInfo(
            table.catalog, table.schema, table.tableName, true, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name == null || column == null || rows.getBoolean("NON_UNIQUE")) {
                    continue;
                }
                addColumn(indexes, name, rows.getInt("ORDINAL_POSITION"), column);
            }
        }
        return containsExactColumns(indexes);
    }

    private void addColumn(
        Map<String, Map<Integer, String>> constraints,
        String constraintName,
        int position,
        String column
    ) {
        Map<Integer, String> columns = constraints.get(constraintName);
        if (columns == null) {
            columns = new LinkedHashMap<Integer, String>();
            constraints.put(constraintName, columns);
        }
        columns.put(position, column == null ? null : column.toUpperCase(Locale.ROOT));
    }

    private boolean containsExactColumns(Map<String, Map<Integer, String>> constraints) {
        for (Map<Integer, String> indexedColumns : constraints.values()) {
            List<Integer> positions = new ArrayList<Integer>(indexedColumns.keySet());
            Collections.sort(positions);
            List<String> columns = new ArrayList<String>();
            for (Integer position : positions) {
                columns.add(indexedColumns.get(position));
            }
            if (UNIQUE_COLUMNS.equals(columns)) {
                return true;
            }
        }
        return false;
    }

    private void verifyRollbackWriteProbe(Connection connection) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (originalAutoCommit) {
            connection.setAutoCommit(false);
        } else {
            savepoint = connection.setSavepoint();
        }

        try {
            String probeId = UUID.randomUUID().toString().replace("-", "");
            String deviceId = "nonce-probe-" + probeId.substring(0, 16);
            String nonce = "probe-" + probeId;
            Timestamp now = new Timestamp(System.currentTimeMillis());
            Timestamp expiresAt = new Timestamp(now.getTime() + 60_000L);

            insertProbe(connection, deviceId, nonce, expiresAt, now);
            boolean duplicateRejected = false;
            try {
                insertProbe(connection, deviceId, nonce, expiresAt, now);
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
            rollbackAndRestore(connection, originalAutoCommit, savepoint, ex);
            throw ex;
        } catch (RuntimeException ex) {
            rollbackAndRestore(connection, originalAutoCommit, savepoint, ex);
            throw ex;
        }

        rollbackAndRestore(connection, originalAutoCommit, savepoint, null);
    }

    private void insertProbe(
        Connection connection,
        String deviceId,
        String nonce,
        Timestamp expiresAt,
        Timestamp now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(PROBE_SQL)) {
            statement.setString(1, deviceId);
            statement.setString(2, nonce);
            statement.setTimestamp(3, expiresAt);
            statement.setTimestamp(4, now);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Nonce write probe did not insert exactly one row");
            }
        }
    }

    private boolean isUniqueViolation(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            String sqlState = current.getSQLState();
            if (sqlState != null && sqlState.startsWith("23")) {
                return true;
            }
            if (current.getErrorCode() == 1) {
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

    private void rollbackAndRestore(
        Connection connection,
        boolean originalAutoCommit,
        Savepoint savepoint,
        Throwable originalFailure
    ) throws SQLException {
        SQLException cleanupFailure = null;
        try {
            if (originalAutoCommit) {
                connection.rollback();
            } else {
                connection.rollback(savepoint);
            }
        } catch (SQLException ex) {
            cleanupFailure = ex;
        }

        if (originalAutoCommit) {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                if (cleanupFailure == null) {
                    cleanupFailure = ex;
                } else {
                    cleanupFailure.addSuppressed(ex);
                }
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

    private static final class TableRef {
        private final String catalog;
        private final String schema;
        private final String tableName;

        private TableRef(String catalog, String schema, String tableName) {
            this.catalog = catalog;
            this.schema = schema;
            this.tableName = tableName;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TableRef)) {
                return false;
            }
            TableRef that = (TableRef) other;
            return equalsNullable(catalog, that.catalog)
                && equalsNullable(schema, that.schema)
                && tableName.equals(that.tableName);
        }

        @Override
        public int hashCode() {
            int result = catalog == null ? 0 : catalog.hashCode();
            result = 31 * result + (schema == null ? 0 : schema.hashCode());
            result = 31 * result + tableName.hashCode();
            return result;
        }

        private static boolean equalsNullable(String left, String right) {
            return left == null ? right == null : left.equals(right);
        }
    }
}
