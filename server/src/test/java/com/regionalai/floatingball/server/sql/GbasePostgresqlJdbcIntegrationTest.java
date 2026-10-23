package com.regionalai.floatingball.server.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit, read-only product/driver check; no application context or business rows. */
@EnabledIfEnvironmentVariable(named = "PCIE_GBASE_JDBC_TEST", matches = "true")
class GbasePostgresqlJdbcIntegrationTest {
    @Test
    void suppliedVendorDriverShouldIdentifyTheActualGbaseProduct() throws Exception {
        String url = required("FB_DB_URL");
        assertFalse(url.toLowerCase(Locale.ROOT).contains("password="), "Pass password separately from JDBC URL");
        Class.forName(required("FB_DB_DRIVER"));
        Properties credentials = new Properties();
        credentials.setProperty("user", required("FB_DB_USERNAME"));
        credentials.setProperty("password", required("FB_DB_PASSWORD"));
        credentials.setProperty("connectTimeout", "10");
        credentials.setProperty("socketTimeout", "15");
        DriverManager.setLoginTimeout(10);

        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(metadata.supportsTransactions(), "Transactions are required");
            System.out.println("JDBC product=" + metadata.getDatabaseProductName()
                + "; productVersion=" + metadata.getDatabaseProductVersion()
                + "; driver=" + metadata.getDriverName() + "; driverVersion=" + metadata.getDriverVersion());
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                try (ResultSet result = statement.executeQuery("SELECT version(), current_database(), current_schema()")) {
                    assertTrue(result.next());
                    String version = result.getString(1);
                    System.out.println("Database version=" + version + "; database=" + result.getString(2)
                        + "; schema=" + result.getString(3));
                    assertTrue((metadata.getDatabaseProductName() + " " + version).toLowerCase(Locale.ROOT)
                        .contains("gbase"), "Generic PostgreSQL/openGauss is not GBase product verification");
                }
                try (ResultSet result = statement.executeQuery(
                    "SELECT setting FROM pg_settings WHERE name = 'sql_compatibility'")) {
                    System.out.println("sql_compatibility=" + (result.next() ? result.getString(1) : "not exposed"));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
