package com.regionalai.floatingball.server.modules.inpatientlog.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InpatientLogSchemaHealthIndicatorTest {

    @Test
    void completeSchemaIsUpAfterTwoQueriesThatCannotReadPatientRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        Health health = new InpatientLogSchemaHealthIndicator(jdbc).health();

        assertEquals(Status.UP, health.getStatus());
        assertTrue(health.getDetails().isEmpty());
        for (String sql : new String[] {
            InpatientLogSchemaHealthIndicator.ROUND_SCHEMA_PROBE_SQL,
            InpatientLogSchemaHealthIndicator.EVENT_SCHEMA_PROBE_SQL
        }) {
            assertTrue(sql.startsWith("SELECT "));
            assertTrue(sql.endsWith("WHERE 1 = 0"));
            verify(jdbc).query(eq(sql), any(ResultSetExtractor.class));
        }
        verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = { "missing table", "missing required column" })
    void missingRoundTableOrColumnIsDown(String databaseFailure) {
        JdbcTemplate jdbc = failingQuery(InpatientLogSchemaHealthIndicator.ROUND_SCHEMA_PROBE_SQL, databaseFailure);

        Health health = new InpatientLogSchemaHealthIndicator(jdbc).health();

        assertUnavailableWithoutDatabaseDetails(health, databaseFailure);
        verify(jdbc).query(eq(InpatientLogSchemaHealthIndicator.ROUND_SCHEMA_PROBE_SQL), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = { "missing table", "missing required column" })
    void missingEventTableOrColumnIsDownEvenWhenRoundTableExists(String databaseFailure) {
        JdbcTemplate jdbc = failingQuery(InpatientLogSchemaHealthIndicator.EVENT_SCHEMA_PROBE_SQL, databaseFailure);

        Health health = new InpatientLogSchemaHealthIndicator(jdbc).health();

        assertUnavailableWithoutDatabaseDetails(health, databaseFailure);
        verify(jdbc).query(eq(InpatientLogSchemaHealthIndicator.ROUND_SCHEMA_PROBE_SQL), any(ResultSetExtractor.class));
        verify(jdbc).query(eq(InpatientLogSchemaHealthIndicator.EVENT_SCHEMA_PROBE_SQL), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectionFailureDoesNotExposeExceptionCauseOrConnectionDetails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String sensitive = "jdbc:gbase://private-host/database?password=SECRET";
        when(jdbc.query(eq(InpatientLogSchemaHealthIndicator.ROUND_SCHEMA_PROBE_SQL), any(ResultSetExtractor.class)))
            .thenThrow(new DataAccessResourceFailureException(sensitive, new SQLException("PRIVATE-SQL-DETAILS")));

        Health health = new InpatientLogSchemaHealthIndicator(jdbc).health();

        assertUnavailableWithoutDatabaseDetails(health, sensitive);
        assertFalse(health.toString().contains("PRIVATE-SQL-DETAILS"));
        assertFalse(health.getDetails().containsKey("error"));
    }

    @Test
    void creatingTheIndicatorDoesNotProbeOrPreventStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertDoesNotThrow(() -> new InpatientLogSchemaHealthIndicator(jdbc));

        verifyNoInteractions(jdbc);
    }

    @Test
    void readinessIncludesInpatientSchemaAndRetainsExistingFeatureEventGate() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String include = yaml.getObject().getProperty("management.endpoint.health.group.readiness.include");

        assertNotNull(include);
        assertTrue(Arrays.asList(include.split(",")).containsAll(
            Arrays.asList("readinessState", "db", "featureEventSchema", "inpatientLogSchema")));
    }

    @SuppressWarnings("unchecked")
    private JdbcTemplate failingQuery(String sql, String failure) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(sql), any(ResultSetExtractor.class)))
            .thenThrow(new BadSqlGrammarException("schema probe", sql, new SQLException(failure)));
        return jdbc;
    }

    private void assertUnavailableWithoutDatabaseDetails(Health health, String failure) {
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(Collections.singletonMap("remediation", InpatientLogSchemaHealthIndicator.REMEDIATION_MESSAGE),
            health.getDetails());
        assertFalse(health.toString().contains(failure));
    }
}
