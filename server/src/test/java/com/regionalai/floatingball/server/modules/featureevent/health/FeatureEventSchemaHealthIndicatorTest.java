package com.regionalai.floatingball.server.modules.featureevent.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class FeatureEventSchemaHealthIndicatorTest {

    @Test
    void health_validSchemaIsUpAndUsesOnlyReadOnlyQuery() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
            FeatureEventSchemaHealthIndicator.MIGRATION_MARKER_SQL,
            Long.class
        )).thenReturn(1L);
        FeatureEventSchemaHealthIndicator indicator =
            new FeatureEventSchemaHealthIndicator(jdbcTemplate, true);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("enabled", health.getDetails().get("validation"));
        assertTrue(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL.startsWith("SELECT "));
        assertTrue(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL.endsWith("WHERE 1 = 0"));
        assertTrue(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL.contains("id_event"));
        assertTrue(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL.contains("idempotency_key"));
        assertTrue(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL.contains("payload_json"));
        assertTrue(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL.contains("client_version"));
        assertTrue(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL.contains("feature_name"));
        assertTrue(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL.contains("insert_time"));
        assertTrue(FeatureEventSchemaHealthIndicator.MIGRATION_MARKER_SQL.startsWith("SELECT "));
        verify(jdbcTemplate).query(
            eq(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL),
            any(ResultSetExtractor.class)
        );
        verify(jdbcTemplate).queryForObject(
            FeatureEventSchemaHealthIndicator.MIGRATION_MARKER_SQL,
            Long.class
        );
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void health_missingMigrationMarkerIsDown() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
            FeatureEventSchemaHealthIndicator.MIGRATION_MARKER_SQL,
            Long.class
        )).thenReturn(0L);
        FeatureEventSchemaHealthIndicator indicator =
            new FeatureEventSchemaHealthIndicator(jdbcTemplate, true);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(
            FeatureEventSchemaHealthIndicator.REMEDIATION_MESSAGE,
            health.getDetails().get("remediation")
        );
        verify(jdbcTemplate).query(
            eq(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL),
            any(ResultSetExtractor.class)
        );
        verify(jdbcTemplate).queryForObject(
            FeatureEventSchemaHealthIndicator.MIGRATION_MARKER_SQL,
            Long.class
        );
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void startup_missingMigrationMarkerRefusesToStart() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
            FeatureEventSchemaHealthIndicator.MIGRATION_MARKER_SQL,
            Long.class
        )).thenReturn(0L);
        FeatureEventSchemaHealthIndicator indicator =
            new FeatureEventSchemaHealthIndicator(jdbcTemplate, true);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            indicator::afterSingletonsInstantiated
        );

        assertEquals(FeatureEventSchemaHealthIndicator.REMEDIATION_MESSAGE, failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void health_sqlFailureIsDownWithoutLeakingDatabaseFailure() {
        JdbcTemplate jdbcTemplate = failingJdbcTemplate();
        FeatureEventSchemaHealthIndicator indicator =
            new FeatureEventSchemaHealthIndicator(jdbcTemplate, true);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(
            FeatureEventSchemaHealthIndicator.REMEDIATION_MESSAGE,
            health.getDetails().get("remediation")
        );
        assertFalse(health.getDetails().toString().contains("connection-details"));
        verify(jdbcTemplate).query(
            eq(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL),
            any(ResultSetExtractor.class)
        );
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void startup_sqlFailureRefusesToStartWithRemediationOnly() {
        JdbcTemplate jdbcTemplate = failingJdbcTemplate();
        FeatureEventSchemaHealthIndicator indicator =
            new FeatureEventSchemaHealthIndicator(jdbcTemplate, true);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            indicator::afterSingletonsInstantiated
        );

        assertEquals(FeatureEventSchemaHealthIndicator.REMEDIATION_MESSAGE, failure.getMessage());
        assertNull(failure.getCause());
        verify(jdbcTemplate).query(
            eq(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL),
            any(ResultSetExtractor.class)
        );
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void disabled_skipsStartupAndReadinessDatabaseProbe() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        FeatureEventSchemaHealthIndicator indicator =
            new FeatureEventSchemaHealthIndicator(jdbcTemplate, false);

        indicator.afterSingletonsInstantiated();
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("disabled", health.getDetails().get("validation"));
        verifyNoInteractions(jdbcTemplate);
    }

    @SuppressWarnings("unchecked")
    private JdbcTemplate failingJdbcTemplate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(
            eq(FeatureEventSchemaHealthIndicator.SCHEMA_PROBE_SQL),
            any(ResultSetExtractor.class)
        )).thenThrow(new DataAccessResourceFailureException("connection-details"));
        return jdbcTemplate;
    }
}
