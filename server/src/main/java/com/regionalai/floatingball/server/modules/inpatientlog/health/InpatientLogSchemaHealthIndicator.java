package com.regionalai.floatingball.server.modules.inpatientlog.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

@Component("inpatientLogSchema")
public class InpatientLogSchemaHealthIndicator implements HealthIndicator {

    static final String ROUND_SCHEMA_PROBE_SQL =
        "SELECT id_log, id_device, id_org, generation_id, admission_id, template_id, template_name, "
            + "template_hash, his_org_id, org_name, doctor_id, doctor_name, patient_id, patient_name, "
            + "patient_gender, patient_age, record_time, generation_time, status, latest_sequence, "
            + "snapshot_json, fg_active, insert_time, update_time FROM c_ai_inpatient_emr_log WHERE 1 = 0";

    static final String EVENT_SCHEMA_PROBE_SQL =
        "SELECT id_event, id_log, id_device, event_id, event_sequence, event_type, status, occurred_at, "
            + "message, body_hash, snapshot_json FROM c_ai_inpatient_emr_log_event WHERE 1 = 0";

    static final String REMEDIATION_MESSAGE =
        "Inpatient log schema is not ready; verify both inpatient log tables and required columns "
            + "using docs/inpatient-emr-logs-migration.md";

    private final JdbcTemplate jdbcTemplate;

    public InpatientLogSchemaHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            jdbcTemplate.query(ROUND_SCHEMA_PROBE_SQL, (ResultSetExtractor<Void>) resultSet -> null);
            jdbcTemplate.query(EVENT_SCHEMA_PROBE_SQL, (ResultSetExtractor<Void>) resultSet -> null);
            return Health.up().build();
        } catch (RuntimeException ex) {
            return Health.down().withDetail("remediation", REMEDIATION_MESSAGE).build();
        }
    }
}
