package com.regionalai.floatingball.server.modules.featureevent.health;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

@Component("featureEventSchema")
public class FeatureEventSchemaHealthIndicator implements HealthIndicator, SmartInitializingSingleton {

    static final String SCHEMA_PROBE_SQL =
        "SELECT id_event, id_device, id_org, id_region, id_his_org, na_his_org, feature_code, feature_name, "
            + "event_action, idempotency_key, trace_id, consultation_id, session_id, source_module, scene_code, "
            + "id_doctor, cd_doctor, na_doctor, id_dept, na_dept, event_status, client_version, payload_json, "
            + "event_time, fg_active, insert_time, update_time "
            + "FROM c_ai_feature_event WHERE 1 = 0";

    static final String MIGRATION_MARKER_SQL =
        "SELECT COUNT(1) FROM c_ai_schema_migration "
            + "WHERE migration_key = 'feature_event_minimization_v1'";

    static final String REMEDIATION_MESSAGE =
        "Feature event schema readiness verification failed; run the database-specific "
            + "update_his_org_statistics.sql before starting the service";

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    public FeatureEventSchemaHealthIndicator(
        JdbcTemplate jdbcTemplate,
        @Value("${floating-ball.feature-event.schema-validation.enabled:true}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!enabled) {
            return;
        }
        try {
            verifySchema();
        } catch (RuntimeException ex) {
            throw new IllegalStateException(REMEDIATION_MESSAGE);
        }
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up().withDetail("validation", "disabled").build();
        }
        try {
            verifySchema();
            return Health.up().withDetail("validation", "enabled").build();
        } catch (RuntimeException ex) {
            return Health.down()
                .withDetail("remediation", REMEDIATION_MESSAGE)
                .build();
        }
    }

    private void verifySchema() {
        jdbcTemplate.query(SCHEMA_PROBE_SQL, (ResultSetExtractor<Void>) resultSet -> null);
        Long markerCount = jdbcTemplate.queryForObject(MIGRATION_MARKER_SQL, Long.class);
        if (markerCount == null || markerCount.longValue() != 1L) {
            throw new IllegalStateException(REMEDIATION_MESSAGE);
        }
    }
}
