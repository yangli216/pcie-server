package com.regionalai.floatingball.server.sql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSchemaScriptTest {

    private static final Path ORACLE_SQL_DIR = Paths.get("src/main/resources/sql/oracle");
    private static final Path GAUSSDB_SQL_DIR = Paths.get("src/main/resources/sql/gaussdb");
    private static final Path DAMENG_SQL_DIR = Paths.get("src/main/resources/sql/dameng");

    @Test
    void oracleDeliveryShouldIncludeExplicitUpdateScript() throws IOException {
        Set<String> actualSqlFiles = new HashSet<String>();
        try (DirectoryStream<Path> sqlScripts = Files.newDirectoryStream(ORACLE_SQL_DIR, "*.sql")) {
            for (Path sqlScript : sqlScripts) {
                actualSqlFiles.add(sqlScript.getFileName().toString());
            }
        }

        Set<String> expectedSqlFiles = new HashSet<String>(Arrays.asList(
            "bootstrap.sql", "init.sql", "update_his_org_statistics.sql",
            "update_chronic_disease_followup.sql",
            "update_chronic_disease_artifact.sql",
            "update_shared_nonce.sql"
        ));
        assertTrue(actualSqlFiles.equals(expectedSqlFiles),
            "oracle delivery should include all explicit update scripts");

        try (DirectoryStream<Path> upgradeScripts = Files.newDirectoryStream(ORACLE_SQL_DIR, "upgrade_*.sql")) {
            assertFalse(upgradeScripts.iterator().hasNext(), "upgrade scripts should be folded into init.sql");
        }
    }

    @Test
    void initSqlShouldContainFoldedUpgradeSchema() throws IOException {
        String initSql = readSql(ORACLE_SQL_DIR.resolve("init.sql"));

        assertContains(initSql, "device_public_key    VARCHAR2(1000)");
        assertContains(initSql, "register_ip          VARCHAR2(64)");
        assertContains(initSql, "last_seen_ip         VARCHAR2(64)");
        assertContains(initSql, "cd_org               VARCHAR2(64) NOT NULL");
        assertContains(initSql, "COMMENT ON COLUMN c_ai_device.device_public_key");
        assertContains(initSql, "COMMENT ON COLUMN c_ai_device.register_ip");
        assertContains(initSql, "COMMENT ON COLUMN c_ai_device.last_seen_ip");

        assertContains(initSql, "fast_model_name          VARCHAR2(128)");
        assertContains(initSql, "enable_thinking          CHAR(1) DEFAULT '0' NOT NULL");
        assertContains(initSql, "audio_api_key_encrypted  VARCHAR2(1000)");
        assertContains(initSql, "speech_realtime_url      VARCHAR2(500)");
        assertContains(initSql, "pmphai_enabled           CHAR(1) DEFAULT '0' NOT NULL");
        assertContains(initSql, "reviewer_check_examination_enabled CHAR(1) DEFAULT '1' NOT NULL");
        assertContains(initSql, "'qwen-audio-3.0-asr-flash-streaming'");

        assertContains(initSql, "CREATE TABLE c_ai_symptom_template");
        assertContains(initSql, "CREATE TABLE c_ai_symptom_template_change_log");
        assertContains(initSql, "CREATE TABLE c_ai_schema_migration");
        assertContains(initSql, "CREATE TABLE c_ai_feature_event");
        assertContains(initSql, "CREATE TABLE c_ai_rec_pref_event");
        assertContains(initSql, "CREATE TABLE c_ai_rec_pref_agg");
        assertContains(initSql, "CREATE TABLE c_security_rejection_log");
        assertContains(initSql, "CREATE TABLE c_ai_request_nonce");
        assertContains(initSql, "CREATE TABLE c_ai_inpatient_emr_tpl_cache");
        assertContains(initSql, "CREATE TABLE c_ai_patient_memory");
        assertContains(initSql, "CREATE TABLE c_ai_patient_memory_obs");
        assertContains(initSql, "CREATE TABLE c_ai_patient_memory_fact");
        assertContains(initSql, "CREATE TABLE c_ai_patient_memory_audit");
        assertContains(initSql, "CREATE TABLE c_ai_chronic_followup");
        assertContains(initSql, "CREATE TABLE c_ai_chronic_artifact");
        assertContains(initSql, "id_phr                   VARCHAR2(64) NOT NULL");
        assertContains(initSql, "sd_visit_kind            VARCHAR2(8) NOT NULL");
        assertContains(initSql, "form_data_json           CLOB NOT NULL");
        assertContains(initSql, "management_source        VARCHAR2(32) NOT NULL");
        assertContains(initSql, "accepted_items_json      CLOB NOT NULL");

        assertContains(initSql, "op_action            VARCHAR2(256)");
        assertContains(initSql, "op_title             VARCHAR2(500)");
        assertContains(initSql, "source_module        VARCHAR2(128)");
        assertContains(initSql, "scene_code           VARCHAR2(256)");
        assertContains(initSql, "trace_id             VARCHAR2(64)");
        assertContains(initSql, "audio_file_path      VARCHAR2(1000)");
        assertContains(initSql, "consultation_id      VARCHAR2(64)");
        assertContains(initSql, "na_his_org           VARCHAR2(255)");

        assertContains(initSql, "speech_text          CLOB");
        assertContains(initSql, "audio_file_name      VARCHAR2(255)");
        assertContains(initSql, "id_his_org           VARCHAR2(64)");
        assertContains(initSql, "cd_doctor            VARCHAR2(64)");
        assertContains(initSql, "COMMENT ON COLUMN c_ai_user_consultation_log.cd_doctor");
        assertContains(initSql, "COMMENT ON COLUMN c_ai_feature_event.cd_doctor");
        assertContains(initSql, "COMMENT ON COLUMN c_ai_feature_event.client_version");
        assertContains(initSql, "feature_event_minimization_v1");
        assertContains(initSql, "change_summary_json  CLOB");
        assertContains(initSql, "total_changes        NUMBER(5)");

        assertContains(initSql, "kind                  VARCHAR2(32) DEFAULT 'general'");
        assertContains(initSql, "severity              VARCHAR2(16) DEFAULT 'medium'");
        assertContains(initSql, "revision_no           NUMBER(10) DEFAULT 1");
        assertContains(initSql, "fg_latest             CHAR(1) DEFAULT '1' NOT NULL");
        assertContains(initSql, "template_id          VARCHAR2(128) NOT NULL");
        assertContains(initSql, "template_hash        VARCHAR2(128) NOT NULL");
        assertContains(initSql, "COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.template_id");

        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_org_code_active");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_device_code_org_active");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_device_token_active");
        assertContains(initSql, "CREATE INDEX idx_c_ai_device_register_ip");
        assertContains(initSql, "CREATE INDEX idx_c_ai_device_last_seen_ip");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_feature_event_idem");
        assertContains(initSql, "CREATE INDEX idx_c_ai_op_log_his_org");
        assertContains(initSql, "CREATE INDEX idx_c_ai_user_log_his_org");
        assertContains(initSql, "CREATE INDEX idx_c_ai_feature_event_his_org");
        assertContains(initSql, "CREATE INDEX idx_c_ai_feature_event_usage");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_rec_pref_event_idem");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_rec_pref_agg_scope");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_feedback_latest_scope");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_user_log_round_active");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_user_code_active");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_role_code_active");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_user_role_active");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_patient_memory_scope");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_patient_memory_obs_idem");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_patient_memory_fact_key");
        assertContains(initSql, "CREATE INDEX idx_c_ai_patient_memory_audit_time");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_chronic_fu_req");
        assertContains(initSql, "CREATE INDEX idx_c_ai_chronic_fu_patient");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_chronic_art_req");
        assertContains(initSql, "CREATE INDEX idx_c_ai_chronic_art_pat");

        assertContains(initSql, "CREATE INDEX idx_c_security_rej_time");
        assertContains(initSql, "CREATE INDEX idx_c_security_rej_type");
        assertContains(initSql, "CREATE INDEX idx_c_security_rej_ip");
        assertContains(initSql, "CREATE INDEX idx_c_security_rej_device");
        assertContains(initSql, "CREATE INDEX idx_c_security_rej_path");
        assertContains(initSql, "nonce_hash           VARCHAR2(64) NOT NULL");
        assertContains(initSql, "expires_at           NUMBER(19) NOT NULL");
        assertContains(initSql, "CONSTRAINT pk_c_ai_request_nonce PRIMARY KEY (id_device, nonce_hash)");
        assertContains(initSql, "CREATE INDEX idx_c_ai_request_nonce_exp");
        assertContains(initSql, "CREATE INDEX idx_c_ai_inemr_tpl_id");
        assertContains(initSql, "CREATE INDEX idx_c_ai_inemr_tpl_hash");
        assertContains(initSql, "CREATE INDEX idx_c_ai_inemr_tpl_status");
    }

    @Test
    void gaussdbDeliveryShouldOnlyKeepInitScript() throws IOException {
        Set<String> actualSqlFiles = new HashSet<String>();
        try (DirectoryStream<Path> sqlScripts = Files.newDirectoryStream(GAUSSDB_SQL_DIR, "*.sql")) {
            for (Path sqlScript : sqlScripts) {
                actualSqlFiles.add(sqlScript.getFileName().toString());
            }
        }

        Set<String> expectedSqlFiles = new HashSet<String>(Arrays.asList(
            "init.sql", "update_his_org_statistics.sql",
            "update_chronic_disease_followup.sql",
            "update_chronic_disease_artifact.sql",
            "update_shared_nonce.sql"
        ));
        assertTrue(actualSqlFiles.equals(expectedSqlFiles),
            "gaussdb delivery should include all explicit update scripts");

        try (DirectoryStream<Path> upgradeScripts = Files.newDirectoryStream(GAUSSDB_SQL_DIR, "upgrade_*.sql")) {
            assertFalse(upgradeScripts.iterator().hasNext(), "gaussdb upgrade scripts should be folded into init.sql");
        }
    }

    @Test
    void gaussdbInitSqlShouldMirrorBusinessSchemaWithoutOracleTypes() throws IOException {
        String initSql = readSql(GAUSSDB_SQL_DIR.resolve("init.sql"));

        assertContains(initSql, "cd_org               VARCHAR(64) NOT NULL");
        assertContains(initSql, "features_json            TEXT");
        assertContains(initSql, "speech_realtime_url      VARCHAR(500)");
        assertContains(initSql, "'qwen-audio-3.0-asr-flash-streaming'");
        assertContains(initSql, "CREATE TABLE c_ai_schema_migration");
        assertContains(initSql, "CREATE TABLE c_ai_rec_pref_event");
        assertContains(initSql, "CREATE TABLE c_ai_rec_pref_agg");
        assertContains(initSql, "CREATE TABLE c_ai_request_nonce");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_rec_pref_event_idem");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_rec_pref_agg_scope");
        assertContains(initSql, "id_his_org           VARCHAR(64)");
        assertContains(initSql, "cd_doctor            VARCHAR(64)");
        assertContains(initSql, "COMMENT ON COLUMN c_ai_user_consultation_log.cd_doctor");
        assertContains(initSql, "COMMENT ON COLUMN c_ai_feature_event.cd_doctor");
        assertContains(initSql, "COMMENT ON COLUMN c_ai_feature_event.client_version");
        assertContains(initSql, "feature_event_minimization_v1");
        assertContains(initSql, "na_his_org           VARCHAR(255)");
        assertContains(initSql, "CREATE INDEX idx_c_ai_op_log_his_org");
        assertContains(initSql, "CREATE INDEX idx_c_ai_user_log_his_org");
        assertContains(initSql, "CREATE INDEX idx_c_ai_feature_event_his_org");
        assertContains(initSql, "CREATE INDEX idx_c_ai_feature_event_usage");
        assertContains(initSql, "total_changes        NUMERIC(5)");
        assertContains(initSql, "CREATE TABLE c_ai_inpatient_emr_tpl_cache");
        assertContains(initSql, "CREATE TABLE c_ai_patient_memory");
        assertContains(initSql, "CREATE TABLE c_ai_patient_memory_obs");
        assertContains(initSql, "CREATE TABLE c_ai_patient_memory_fact");
        assertContains(initSql, "CREATE TABLE c_ai_patient_memory_audit");
        assertContains(initSql, "CREATE TABLE c_ai_chronic_followup");
        assertContains(initSql, "CREATE TABLE c_ai_chronic_artifact");
        assertContains(initSql, "id_phr                   VARCHAR(64) NOT NULL");
        assertContains(initSql, "sd_visit_kind            VARCHAR(8) NOT NULL");
        assertContains(initSql, "form_data_json           TEXT NOT NULL");
        assertContains(initSql, "management_source        VARCHAR(32) NOT NULL");
        assertContains(initSql, "accepted_items_json      TEXT NOT NULL");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_patient_memory_scope");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_patient_memory_obs_idem");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_patient_memory_fact_key");
        assertContains(initSql, "CREATE INDEX idx_c_ai_patient_memory_audit_time");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_chronic_fu_req");
        assertContains(initSql, "CREATE INDEX idx_c_ai_chronic_fu_patient");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_chronic_art_req");
        assertContains(initSql, "CREATE INDEX idx_c_ai_chronic_art_pat");
        assertContains(initSql, "nonce_hash           VARCHAR(64) NOT NULL");
        assertContains(initSql, "expires_at           BIGINT NOT NULL");
        assertContains(initSql, "CONSTRAINT pk_c_ai_request_nonce PRIMARY KEY (id_device, nonce_hash)");
        assertContains(initSql, "CREATE INDEX idx_c_ai_request_nonce_exp");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_org_code_active");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_device_code_org_active");
        assertContains(initSql, "CREATE UNIQUE INDEX uk_c_ai_feedback_latest_scope");
        assertContains(initSql, "COALESCE(id_device, '-')");
        assertContains(initSql, "INSERT INTO c_ai_org (id_org, cd_org, na_org, id_region, sd_org_type, sd_status, fg_active)");
        assertContains(initSql, "COMMIT");

        assertNotContains(initSql, "VARCHAR2");
        assertNotContains(initSql, "NUMBER(");
        assertNotContains(initSql, "CLOB");
        assertNotContains(initSql, "NVL(");
        assertNotContains(initSql, "DBMS_");
        assertNotContains(initSql, "dba_");
    }

    @Test
    void explicitUpdateScriptsShouldIncludeHisOrgAndConsultationRoundSchema() throws IOException {
        for (Path script : Arrays.asList(
            ORACLE_SQL_DIR.resolve("update_his_org_statistics.sql"),
            GAUSSDB_SQL_DIR.resolve("update_his_org_statistics.sql"),
            DAMENG_SQL_DIR.resolve("update_his_org_statistics.sql")
        )) {
            String sql = readSql(script);
            assertContains(sql, "c_ai_config");
            assertContains(sql, "speech_realtime_url");
            assertContains(sql, "c_ai_user_consultation_log");
            assertContains(sql, "consultation_round_id");
            assertContains(sql, "id_his_org");
            assertContains(sql, "cd_doctor");
            assertContains(sql, "c_ai_op_log");
            assertContains(sql, "c_ai_feature_event");
            assertContains(sql, "na_his_org");
            assertContains(sql, "COMMENT ON COLUMN c_ai_feature_event.cd_doctor IS '医生真实工号（来自SDK握手urt.personCd）'");
            assertContains(sql, "COMMENT ON COLUMN c_ai_feature_event.client_version IS '事件产生时的客户端版本'");
            assertContains(sql, "idx_c_ai_user_log_his_org");
            assertContains(sql, "idx_c_ai_user_log_round");
            assertContains(sql, "uk_c_ai_user_log_round_active");
            assertContains(sql, "idx_c_ai_op_log_his_org");
            assertContains(sql, "idx_c_ai_feature_event_his_org");
            assertContains(sql, "idx_c_ai_feature_event_usage");
            assertContains(sql, "(id_org, id_his_org, cd_doctor, client_version, event_time, fg_active)");
            assertContains(sql, "SET consultation_id = NULL");
            assertContains(sql, "trace_id = NULL");
            assertContains(sql, "session_id = NULL");
            assertContains(sql, "payload_json");
            assertContains(sql, "':minimized:v1:event:'");
            assertContains(sql, "REPLACE(LOWER(TRIM(id_event)), '-', '')");
            assertContains(sql, "idempotency_key <>");
            assertContains(sql, "idempotency_key IS NULL");
            assertContains(sql, "WHERE id_event IS NULL OR");
            assertContains(sql, "Feature-event privacy migration requires UUID event IDs");
            assertContains(sql, "Feature-event privacy migration requires non-empty feature codes");
            assertContains(sql, "Feature-event privacy migration found idempotency-key conflicts");
            assertContains(sql, "candidate_key");
            assertContains(sql, "HAVING COUNT(1) > 1");
            assertContains(sql, "SUM(is_migration_candidate) > 0");
            assertContains(sql, "c_ai_schema_migration");
            assertContains(sql, "feature_event_minimization_v1");
            assertNotContains(sql, ":legacy:");
            assertContains(sql, "COUNT(DISTINCT");
            assertNotContains(sql, "LOWER(TRIM(source_module)) = 'his_bridge'");
            assertNotContains(sql, "LOWER(TRIM(feature_code)) = 'knowledge_usage'");
            assertTrue(
                sql.indexOf("Feature-event privacy migration requires UUID event IDs")
                    < sql.indexOf("speech_realtime_url"),
                "privacy preflight should run before schema DDL"
            );
            assertTrue(
                sql.indexOf("SET idempotency_key") < sql.indexOf("feature_event_minimization_v1"),
                "migration marker should only be written after feature-event minimization"
            );
        }

        String oracleSql = readSql(ORACLE_SQL_DIR.resolve("update_his_org_statistics.sql"));
        String gaussdbSql = readSql(GAUSSDB_SQL_DIR.resolve("update_his_org_statistics.sql"));
        String damengSql = readSql(DAMENG_SQL_DIR.resolve("update_his_org_statistics.sql"));

        assertContains(oracleSql, "WHENEVER SQLERROR EXIT 1 ROLLBACK");
        assertContains(oracleSql, "SET payload_json = '{}'");
        assertContains(oracleSql, "DBMS_LOB.COMPARE(payload_json, TO_CLOB('{}')) <> 0");
        assertContains(gaussdbSql, "\\set ON_ERROR_STOP on");
        assertContains(gaussdbSql, "SET payload_json = '{}'");
        assertContains(gaussdbSql, "payload_json IS DISTINCT FROM '{}'");
        assertContains(damengSql, "WHENEVER SQLERROR EXIT 1 ROLLBACK");
        assertContains(damengSql, "SET payload_json = TO_CLOB('{}')");
        assertContains(damengSql, "WHERE payload_json IS NULL");
        assertContains(damengSql, "TEXT_EQUAL(payload_json, TO_CLOB('{}')) = 0");

        String oracleCompatibleColumn =
            "add_column_if_missing('c_ai_user_consultation_log', 'consultation_round_id', 'VARCHAR2(64)')";
        String oracleCompatibleDoctorWorkNoColumn =
            "add_column_if_missing('c_ai_user_consultation_log', 'cd_doctor', 'VARCHAR2(64)')";
        String oracleCompatibleFeatureDoctorWorkNoColumn =
            "add_column_if_missing('c_ai_feature_event', 'cd_doctor', 'VARCHAR2(64)')";
        String oracleCompatibleFeatureClientVersionColumn =
            "add_column_if_missing('c_ai_feature_event', 'client_version', 'VARCHAR2(64)')";
        String oracleCompatibleFeatureUsageIndex =
            "'CREATE INDEX idx_c_ai_feature_event_usage ON c_ai_feature_event "
                + "(id_org, id_his_org, cd_doctor, client_version, event_time, fg_active)'";
        String oracleCompatibleRealtimeColumn =
            "add_column_if_missing('c_ai_config', 'speech_realtime_url', 'VARCHAR2(500)')";
        String oracleCompatibleUniqueIndex =
            "CASE WHEN fg_active = ''1'' AND status = ''generated'' THEN consultation_round_id END";
        assertContains(oracleSql, oracleCompatibleColumn);
        assertContains(oracleSql, oracleCompatibleDoctorWorkNoColumn);
        assertContains(oracleSql, oracleCompatibleFeatureDoctorWorkNoColumn);
        assertContains(oracleSql, oracleCompatibleFeatureClientVersionColumn);
        assertContains(oracleSql, oracleCompatibleFeatureUsageIndex);
        assertContains(oracleSql, oracleCompatibleRealtimeColumn);
        assertContains(oracleSql, oracleCompatibleUniqueIndex);
        assertContains(damengSql, oracleCompatibleColumn);
        assertContains(damengSql, oracleCompatibleDoctorWorkNoColumn);
        assertContains(damengSql, oracleCompatibleFeatureDoctorWorkNoColumn);
        assertContains(damengSql, oracleCompatibleFeatureClientVersionColumn);
        assertContains(damengSql, oracleCompatibleFeatureUsageIndex);
        assertContains(damengSql, oracleCompatibleRealtimeColumn);
        assertContains(damengSql, oracleCompatibleUniqueIndex);

        assertContains(
            gaussdbSql,
            "ALTER TABLE c_ai_config ADD COLUMN IF NOT EXISTS speech_realtime_url VARCHAR(500)"
        );
        assertContains(
            gaussdbSql,
            "ALTER TABLE c_ai_user_consultation_log ADD COLUMN IF NOT EXISTS consultation_round_id VARCHAR(64)"
        );
        assertContains(
            gaussdbSql,
            "ALTER TABLE c_ai_user_consultation_log ADD COLUMN IF NOT EXISTS cd_doctor VARCHAR(64)"
        );
        assertContains(
            gaussdbSql,
            "ALTER TABLE c_ai_feature_event ADD COLUMN IF NOT EXISTS cd_doctor VARCHAR(64)"
        );
        assertContains(
            gaussdbSql,
            "ALTER TABLE c_ai_feature_event ADD COLUMN IF NOT EXISTS client_version VARCHAR(64)"
        );
        assertContains(
            gaussdbSql,
            "CREATE INDEX IF NOT EXISTS idx_c_ai_feature_event_usage\n"
                + "    ON c_ai_feature_event "
                + "(id_org, id_his_org, cd_doctor, client_version, event_time, fg_active)"
        );
        assertContains(
            gaussdbSql,
            "CASE WHEN fg_active = '1' AND status = 'generated' THEN consultation_round_id END"
        );
    }

    @Test
    void chronicDiseaseUpdateScriptsShouldCreateTypedIdempotentSchema() throws IOException {
        for (Path script : Arrays.asList(
            ORACLE_SQL_DIR.resolve("update_chronic_disease_followup.sql"),
            GAUSSDB_SQL_DIR.resolve("update_chronic_disease_followup.sql"),
            DAMENG_SQL_DIR.resolve("update_chronic_disease_followup.sql")
        )) {
            String sql = readSql(script);
            assertContains(sql, "c_ai_chronic_followup");
            assertContains(sql, "template_version");
            assertContains(sql, "path_version");
            assertContains(sql, "evidence_version");
            assertContains(sql, "rule_version");
            assertContains(sql, "management_source");
            assertContains(sql, "management_evidence");
            assertContains(sql, "id_phr");
            assertContains(sql, "id_record");
            assertContains(sql, "sd_visit_kind");
            assertContains(sql, "form_data_json");
            assertContains(sql, "uk_c_ai_chronic_fu_req");
            assertContains(sql, "idx_c_ai_chronic_fu_patient");
            assertContains(sql, "idx_c_ai_chronic_fu_disease");
            assertContains(sql, "idx_c_ai_chronic_fu_tcd");
        }

        String gaussdbSql = readSql(GAUSSDB_SQL_DIR.resolve("update_chronic_disease_followup.sql"));
        assertContains(gaussdbSql, "CREATE TABLE IF NOT EXISTS c_ai_chronic_followup");
        assertNotContains(gaussdbSql, "VARCHAR2");
        assertNotContains(gaussdbSql, "NUMBER(");
    }

    @Test
    void chronicArtifactUpdateScriptsShouldCreateTypedIdempotentSchema() throws IOException {
        for (Path script : Arrays.asList(
            ORACLE_SQL_DIR.resolve("update_chronic_disease_artifact.sql"),
            GAUSSDB_SQL_DIR.resolve("update_chronic_disease_artifact.sql"),
            DAMENG_SQL_DIR.resolve("update_chronic_disease_artifact.sql")
        )) {
            String sql = readSql(script);
            assertContains(sql, "c_ai_chronic_artifact");
            assertContains(sql, "artifact_type");
            assertContains(sql, "disease_types_json");
            assertContains(sql, "accepted_items_json");
            assertContains(sql, "uk_c_ai_chronic_art_req");
            assertContains(sql, "idx_c_ai_chronic_art_pat");
            assertContains(sql, "idx_c_ai_chronic_art_type");
        }

        String gaussdbSql = readSql(GAUSSDB_SQL_DIR.resolve("update_chronic_disease_artifact.sql"));
        assertContains(gaussdbSql, "CREATE TABLE IF NOT EXISTS c_ai_chronic_artifact");
        assertNotContains(gaussdbSql, "VARCHAR2");
        assertNotContains(gaussdbSql, "NUMBER(");
    }

    @Test
    void sharedNonceUpdateScriptsShouldCreateNewSchemaWithoutLegacyMigration() throws IOException {
        for (Path script : Arrays.asList(
            ORACLE_SQL_DIR.resolve("update_shared_nonce.sql"),
            GAUSSDB_SQL_DIR.resolve("update_shared_nonce.sql"),
            DAMENG_SQL_DIR.resolve("update_shared_nonce.sql")
        )) {
            String sql = readSql(script);
            String normalizedSql = sql.toUpperCase();
            assertContains(sql, "c_ai_request_nonce");
            assertContains(sql, "nonce_hash");
            assertContains(sql, "expires_at");
            assertContains(sql, "CONSTRAINT pk_c_ai_request_nonce PRIMARY KEY (id_device, nonce_hash)");
            assertContains(sql, "idx_c_ai_request_nonce_exp");
            assertNotContains(sql, "c_security_request_nonce");
            assertNotContains(normalizedSql, "DROP TABLE");
            assertNotContains(normalizedSql, "\nCONNECT ");
            assertNotContains(normalizedSql, "PASSWORD");
        }

        String oracleSql = readSql(ORACLE_SQL_DIR.resolve("update_shared_nonce.sql"));
        assertContains(oracleSql, "WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK");
        assertContains(oracleSql, "user_tables");
        assertContains(oracleSql, "user_tab_columns");
        assertContains(oracleSql, "user_constraints");
        assertContains(oracleSql, "user_cons_columns");
        assertContains(oracleSql, "user_indexes");
        assertContains(oracleSql, "user_ind_columns");
        assertContains(oracleSql, "RAISE_APPLICATION_ERROR");
        assertContains(oracleSql, "column contract drift detected");
        assertContains(oracleSql, "primary-key contract drift detected");
        assertContains(oracleSql, "index contract drift detected");
        assertContains(oracleSql, "VARCHAR2(32) NOT NULL");
        assertContains(oracleSql, "VARCHAR2(64) NOT NULL");
        assertContains(oracleSql, "NUMBER(19) NOT NULL");

        String gaussdbSql = readSql(GAUSSDB_SQL_DIR.resolve("update_shared_nonce.sql"));
        assertContains(gaussdbSql, "\\set ON_ERROR_STOP on");
        assertContains(gaussdbSql, "BEGIN;");
        assertContains(gaussdbSql, "CREATE TABLE IF NOT EXISTS c_ai_request_nonce");
        assertContains(gaussdbSql, "information_schema.columns");
        assertContains(gaussdbSql, "information_schema.table_constraints");
        assertContains(gaussdbSql, "information_schema.key_column_usage");
        assertContains(gaussdbSql, "pg_index");
        assertContains(gaussdbSql, "index_meta.indkey[0]");
        assertContains(gaussdbSql, "index_meta.indnatts = 1");
        assertContains(gaussdbSql, "NOT index_meta.indisunique");
        assertNotContains(gaussdbSql, "JOIN LATERAL");
        assertNotContains(gaussdbSql, "unnest(index_meta.indkey");
        assertContains(gaussdbSql, "RAISE EXCEPTION");
        assertContains(gaussdbSql, "column contract drift detected");
        assertContains(gaussdbSql, "primary-key contract drift detected");
        assertContains(gaussdbSql, "index contract drift detected");
        assertContains(gaussdbSql, "VARCHAR(32) NOT NULL");
        assertContains(gaussdbSql, "VARCHAR(64) NOT NULL");
        assertContains(gaussdbSql, "BIGINT NOT NULL");
        assertContains(gaussdbSql, "COMMIT;");
        assertNotContains(gaussdbSql, "VARCHAR2");
        assertNotContains(gaussdbSql, "NUMBER(");

        String damengSql = readSql(DAMENG_SQL_DIR.resolve("update_shared_nonce.sql"));
        assertContains(damengSql, "VARCHAR2(32) NOT NULL");
        assertContains(damengSql, "VARCHAR2(64) NOT NULL");
        assertContains(damengSql, "NUMBER(19) NOT NULL");
        assertContains(damengSql, "WHENEVER SQLERROR EXIT 1 ROLLBACK");
        assertContains(damengSql, "user_tables");
        assertContains(damengSql, "user_tab_columns");
        assertContains(damengSql, "user_constraints");
        assertContains(damengSql, "user_cons_columns");
        assertContains(damengSql, "user_indexes");
        assertContains(damengSql, "user_ind_columns");
        assertContains(damengSql, "RAISE_APPLICATION_ERROR");
        assertContains(damengSql, "column contract drift detected");
        assertContains(damengSql, "primary-key contract drift detected");
        assertContains(damengSql, "index contract drift detected");
    }

    private String readSql(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private void assertContains(String sql, String expected) {
        assertTrue(sql.contains(expected), "init.sql should contain: " + expected);
    }

    private void assertNotContains(String sql, String unexpected) {
        assertFalse(sql.contains(unexpected), "init.sql should not contain: " + unexpected);
    }
}
