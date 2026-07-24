-- Dameng DM8 Oracle-compatible variant.
-- Keep this script aligned with ../oracle/update_chronic_disease_followup.sql.

DECLARE
    v_table_count NUMBER;
BEGIN
    SELECT COUNT(1)
      INTO v_table_count
      FROM user_tables
     WHERE table_name = 'C_AI_CHRONIC_FOLLOWUP';

    IF v_table_count = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE c_ai_chronic_followup (
                id_followup              VARCHAR2(32) PRIMARY KEY,
                request_id               VARCHAR2(64) NOT NULL,
                id_device                VARCHAR2(32),
                id_org                   VARCHAR2(32) NOT NULL,
                id_phr                   VARCHAR2(64) NOT NULL,
                id_record                VARCHAR2(64) NOT NULL,
                source_form_id           VARCHAR2(64),
                visit_status             VARCHAR2(2) NOT NULL,
                sd_visit_kind            VARCHAR2(8) NOT NULL,
                dt_hy_plan               VARCHAR2(10),
                dt_dbs_plan              VARCHAR2(10),
                input_user               VARCHAR2(64),
                id_user                  VARCHAR2(64),
                form_data_json           CLOB NOT NULL,
                id_his_org               VARCHAR2(64),
                na_his_org               VARCHAR2(255),
                patient_id               VARCHAR2(64) NOT NULL,
                visit_id                 VARCHAR2(64),
                patient_name             VARCHAR2(128) NOT NULL,
                disease_type             VARCHAR2(32) NOT NULL,
                management_source        VARCHAR2(32) NOT NULL,
                management_evidence      VARCHAR2(2000) NOT NULL,
                template_version         VARCHAR2(64) NOT NULL,
                path_version             VARCHAR2(64) NOT NULL,
                evidence_version         VARCHAR2(128) NOT NULL,
                rule_version             VARCHAR2(64) NOT NULL,
                followup_date            DATE NOT NULL,
                followup_method          VARCHAR2(32) NOT NULL,
                symptom_codes            VARCHAR2(1000) NOT NULL,
                systolic_pressure        NUMBER(4),
                diastolic_pressure       NUMBER(4),
                fasting_glucose          NUMBER(8,2),
                postprandial_glucose     NUMBER(8,2),
                height_cm                NUMBER(8,2),
                weight_kg                NUMBER(8,2),
                bmi                      NUMBER(6,2),
                waist_cm                 NUMBER(8,2),
                daily_cigarettes         NUMBER(4),
                daily_alcohol_units      NUMBER(8,2),
                weekly_exercise_sessions NUMBER(3),
                exercise_minutes         NUMBER(4),
                salt_intake_level        VARCHAR2(32) NOT NULL,
                psychological_status     VARCHAR2(32) NOT NULL,
                medication_adherence     VARCHAR2(32) NOT NULL,
                adverse_reaction         CHAR(1) DEFAULT ''0'' NOT NULL,
                adverse_reaction_text    VARCHAR2(1000),
                medication_summary       VARCHAR2(2000),
                followup_classification  VARCHAR2(32) NOT NULL,
                referral_required        CHAR(1) DEFAULT ''0'' NOT NULL,
                referral_reason          VARCHAR2(1000),
                referral_organization    VARCHAR2(255),
                next_followup_date       DATE NOT NULL,
                id_doctor                VARCHAR2(64),
                na_doctor                VARCHAR2(128) NOT NULL,
                notes                    VARCHAR2(2000),
                save_status              VARCHAR2(16) DEFAULT ''saved'' NOT NULL,
                fg_active                CHAR(1) DEFAULT ''1'' NOT NULL,
                insert_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                update_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )';
    END IF;
END;
/

DECLARE
    PROCEDURE add_column_if_missing(
        p_column_name IN VARCHAR2,
        p_definition  IN VARCHAR2
    ) IS
        v_count NUMBER;
    BEGIN
        SELECT COUNT(1)
          INTO v_count
          FROM user_tab_columns
         WHERE table_name = 'C_AI_CHRONIC_FOLLOWUP'
           AND column_name = UPPER(p_column_name);
        IF v_count = 0 THEN
            EXECUTE IMMEDIATE
                'ALTER TABLE c_ai_chronic_followup ADD (' || p_definition || ')';
        END IF;
    END;
BEGIN
    add_column_if_missing(
        'management_source',
        'management_source VARCHAR2(32) DEFAULT ''legacy_unverified'' NOT NULL'
    );
    add_column_if_missing(
        'management_evidence',
        'management_evidence VARCHAR2(2000) DEFAULT ''历史记录，未留存公卫在管依据'' NOT NULL'
    );
    add_column_if_missing('id_phr', 'id_phr VARCHAR2(64)');
    add_column_if_missing('id_record', 'id_record VARCHAR2(64)');
    add_column_if_missing('source_form_id', 'source_form_id VARCHAR2(64)');
    add_column_if_missing('visit_status', 'visit_status VARCHAR2(2)');
    add_column_if_missing('sd_visit_kind', 'sd_visit_kind VARCHAR2(8)');
    add_column_if_missing('dt_hy_plan', 'dt_hy_plan VARCHAR2(10)');
    add_column_if_missing('dt_dbs_plan', 'dt_dbs_plan VARCHAR2(10)');
    add_column_if_missing('input_user', 'input_user VARCHAR2(64)');
    add_column_if_missing('id_user', 'id_user VARCHAR2(64)');
    add_column_if_missing('form_data_json', 'form_data_json CLOB');
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
        'uk_c_ai_chronic_fu_req',
        'CREATE UNIQUE INDEX uk_c_ai_chronic_fu_req ON c_ai_chronic_followup (id_org, CASE WHEN fg_active = ''1'' THEN request_id END)'
    );
    create_index_if_missing(
        'idx_c_ai_chronic_fu_patient',
        'CREATE INDEX idx_c_ai_chronic_fu_patient ON c_ai_chronic_followup (id_org, id_his_org, patient_id, followup_date, fg_active)'
    );
    create_index_if_missing(
        'idx_c_ai_chronic_fu_disease',
        'CREATE INDEX idx_c_ai_chronic_fu_disease ON c_ai_chronic_followup (id_org, disease_type, followup_date, fg_active)'
    );
    create_index_if_missing(
        'idx_c_ai_chronic_fu_tcd',
        'CREATE INDEX idx_c_ai_chronic_fu_tcd ON c_ai_chronic_followup (id_org, id_phr, id_record, sd_visit_kind, fg_active)'
    );
END;
/

COMMENT ON TABLE c_ai_chronic_followup IS '原TcdVisitForm结构的高血压与糖尿病融合随访记录';
COMMENT ON COLUMN c_ai_chronic_followup.request_id IS 'X-Request-Id，在平台机构激活记录内唯一';
COMMENT ON COLUMN c_ai_chronic_followup.id_phr IS '原慢病系统人员主键idPhr';
COMMENT ON COLUMN c_ai_chronic_followup.id_record IS '原慢病系统登记表主键idRecord';
COMMENT ON COLUMN c_ai_chronic_followup.sd_visit_kind IS '1高血压/2糖尿病/1,2联合';
COMMENT ON COLUMN c_ai_chronic_followup.form_data_json IS '强类型TcdVisitForm.getFormData完整快照';
COMMENT ON COLUMN c_ai_chronic_followup.disease_type IS '兼容检索列：hypertension/type2_diabetes/combined';
COMMENT ON COLUMN c_ai_chronic_followup.management_source IS '新记录固定public_health；迁移历史记录为legacy_unverified';
COMMENT ON COLUMN c_ai_chronic_followup.management_evidence IS '公卫明确在管的来源证据';
COMMENT ON COLUMN c_ai_chronic_followup.template_version IS '保存时使用的受控公卫随访模板版本';
COMMENT ON COLUMN c_ai_chronic_followup.path_version IS '保存时绑定的受控临床路径版本';
COMMENT ON COLUMN c_ai_chronic_followup.evidence_version IS '保存时绑定的规范或指南依据版本';
COMMENT ON COLUMN c_ai_chronic_followup.rule_version IS '保存时使用的条件和值域规则版本';
COMMENT ON COLUMN c_ai_chronic_followup.symptom_codes IS '受控症状编码，逗号分隔；不是无类型业务载荷';
COMMENT ON COLUMN c_ai_chronic_followup.followup_classification IS 'stable/uncontrolled/adverse_reaction/complication';
COMMENT ON COLUMN c_ai_chronic_followup.referral_required IS '是否需要转诊：1是/0否';
COMMENT ON COLUMN c_ai_chronic_followup.save_status IS '保存状态，当前固定saved';

COMMIT;
