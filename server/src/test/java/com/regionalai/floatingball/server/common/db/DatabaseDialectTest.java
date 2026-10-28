package com.regionalai.floatingball.server.common.db;

import com.regionalai.floatingball.server.common.config.MybatisPlusDatabaseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseDialectTest {

    @ParameterizedTest
    @ValueSource(strings = {"gbase-postgresql", " GBASE-POSTGRESQL "})
    void gbasePostgresqlShouldSelectPgSqlWithoutInferringInformix(String configured) {
        MybatisPlusDatabaseProperties properties = new MybatisPlusDatabaseProperties();
        properties.setDbType(configured);
        DatabaseDialect dialect = new DatabaseDialect(properties);

        assertEquals(DatabaseDialect.Kind.GBASE_POSTGRESQL, dialect.getKind());
        assertTrue(dialect.isPgCompatible());
        assertEquals("LIMIT 3", dialect.firstRows(3));
        assertEquals("LIMIT 1", dialect.firstRows(0));
        assertEquals("COALESCE(a, b)", dialect.nvl("a", "b"));
        assertEquals("TO_CHAR(event_time::date, 'YYYY-MM-DD')", dialect.dayText("event_time"));
        assertEquals("TO_TIMESTAMP(#{query.dateFrom}, 'YYYY-MM-DD')", dialect.dateStartParameter("query.dateFrom"));
        assertEquals("TO_TIMESTAMP(#{query.dateTo}, 'YYYY-MM-DD') + INTERVAL '1 day'",
            dialect.dateEndExclusiveParameter("query.dateTo"));
        assertEquals("POSITION({0} IN payload_json) > 0", dialect.textContains("payload_json", "{0}"));
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(MybatisPlusDatabaseProperties.class, DatabaseDialect.class);

    @Test
    void shouldResolveGaussdbAliasesAsPgCompatible() {
        MybatisPlusDatabaseProperties properties = new MybatisPlusDatabaseProperties();
        properties.setDbType("gaussdb");

        DatabaseDialect dialect = new DatabaseDialect(properties);

        assertTrue(dialect.isPgCompatible());
        assertEquals("LIMIT 3", dialect.firstRows(3));
        assertEquals("COALESCE(a, b)", dialect.nvl("a", "b"));
        assertEquals("POSITION({0} IN payload_json) > 0", dialect.textContains("payload_json", "{0}"));
        assertTrue(dialect.dayText("insert_time").contains("::date"));
    }

    @Test
    void shouldDefaultToOracleDialect() {
        MybatisPlusDatabaseProperties properties = new MybatisPlusDatabaseProperties();

        DatabaseDialect dialect = new DatabaseDialect(properties);

        assertFalse(dialect.isPgCompatible());
        assertEquals("FETCH FIRST 1 ROWS ONLY", dialect.firstRows(1));
        assertEquals("NVL(a, b)", dialect.nvl("a", "b"));
        assertEquals("DBMS_LOB.INSTR(payload_json, {0}) > 0", dialect.textContains("payload_json", "{0}"));
        assertTrue(dialect.dayText("insert_time").contains("TRUNC"));
    }

    @Test
    void shouldWireDialectBeanFromDatabaseProperties() {
        contextRunner
            .withPropertyValues("mybatis-plus.db-type=opengauss")
            .run(context -> {
                DatabaseDialect dialect = context.getBean(DatabaseDialect.class);

                assertEquals(DatabaseDialect.Kind.OPENGAUSS, dialect.getKind());
                assertTrue(dialect.isPgCompatible());
            });
    }
}
