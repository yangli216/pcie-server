-- One-time, idempotent upgrade for chronic-disease printable artifact snapshots.
-- Run as the application schema owner after taking a database backup.

DECLARE
    v_table_count NUMBER;
BEGIN
    SELECT COUNT(1)
      INTO v_table_count
      FROM user_tables
     WHERE table_name = 'C_AI_CHRONIC_ARTIFACT';

    IF v_table_count = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE c_ai_chronic_artifact (
                id_snapshot              VARCHAR2(32) PRIMARY KEY,
                request_id               VARCHAR2(64) NOT NULL,
                artifact_type            VARCHAR2(32) NOT NULL,
                id_device                VARCHAR2(32),
                id_org                   VARCHAR2(32) NOT NULL,
                id_his_org               VARCHAR2(64),
                na_his_org               VARCHAR2(255),
                patient_id               VARCHAR2(64) NOT NULL,
                visit_id                 VARCHAR2(64),
                patient_name             VARCHAR2(128) NOT NULL,
                disease_types_json       VARCHAR2(1000) NOT NULL,
                data_as_of               TIMESTAMP NOT NULL,
                assessment_year          NUMBER(4),
                template_versions_json   VARCHAR2(1000) NOT NULL,
                path_versions_json       VARCHAR2(1000) NOT NULL,
                evidence_versions_json   VARCHAR2(2000) NOT NULL,
                rule_version             VARCHAR2(64) NOT NULL,
                summary_text             VARCHAR2(4000) NOT NULL,
                systolic_pressure        NUMBER(4),
                diastolic_pressure       NUMBER(4),
                blood_glucose            NUMBER(8,2),
                bp_record_count          NUMBER(8) NOT NULL,
                glucose_record_count     NUMBER(8) NOT NULL,
                accepted_items_json      CLOB NOT NULL,
                doctor_notes             VARCHAR2(2000),
                id_doctor                VARCHAR2(64),
                na_doctor                VARCHAR2(128) NOT NULL,
                save_status              VARCHAR2(16) DEFAULT ''saved'' NOT NULL,
                fg_active                CHAR(1) DEFAULT ''1'' NOT NULL,
                insert_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                update_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )';
    END IF;
END;
/

DECLARE
    PROCEDURE create_index_if_missing(
        p_index_name IN VARCHAR2,
        p_statement  IN VARCHAR2
    ) IS
        v_count NUMBER;
    BEGIN
        SELECT COUNT(1)
          INTO v_count
          FROM user_indexes
         WHERE index_name = UPPER(p_index_name);
        IF v_count = 0 THEN
            EXECUTE IMMEDIATE p_statement;
        END IF;
    END;
BEGIN
    create_index_if_missing(
        'uk_c_ai_chronic_art_req',
        'CREATE UNIQUE INDEX uk_c_ai_chronic_art_req ON c_ai_chronic_artifact (id_org, CASE WHEN fg_active = ''1'' THEN request_id END)'
    );
    create_index_if_missing(
        'idx_c_ai_chronic_art_pat',
        'CREATE INDEX idx_c_ai_chronic_art_pat ON c_ai_chronic_artifact (id_org, id_his_org, patient_id, insert_time, fg_active)'
    );
    create_index_if_missing(
        'idx_c_ai_chronic_art_type',
        'CREATE INDEX idx_c_ai_chronic_art_type ON c_ai_chronic_artifact (id_org, artifact_type, assessment_year, insert_time, fg_active)'
    );
END;
/

COMMENT ON TABLE c_ai_chronic_artifact IS '双慢病健康处方与年度评估打印留痕快照';
COMMENT ON COLUMN c_ai_chronic_artifact.request_id IS '客户端幂等请求ID，在平台机构激活记录内唯一';
COMMENT ON COLUMN c_ai_chronic_artifact.artifact_type IS 'health_prescription/annual_assessment';
COMMENT ON COLUMN c_ai_chronic_artifact.disease_types_json IS '快照覆盖病种的强类型JSON数组';
COMMENT ON COLUMN c_ai_chronic_artifact.data_as_of IS '快照使用的患者证据截至时间';
COMMENT ON COLUMN c_ai_chronic_artifact.accepted_items_json IS '医生确认建议的强类型JSON数组';
COMMENT ON COLUMN c_ai_chronic_artifact.save_status IS '保存状态，当前固定saved';

COMMIT;
