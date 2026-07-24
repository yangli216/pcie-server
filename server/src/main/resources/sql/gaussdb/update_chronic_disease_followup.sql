-- One-time, idempotent upgrade for the typed hypertension/type-2-diabetes follow-up record.
-- Run as the application schema owner after taking a database backup.

CREATE TABLE IF NOT EXISTS c_ai_chronic_followup (
    id_followup              VARCHAR(32) PRIMARY KEY,
    request_id               VARCHAR(64) NOT NULL,
    id_device                VARCHAR(32),
    id_org                   VARCHAR(32) NOT NULL,
    id_phr                   VARCHAR(64) NOT NULL,
    id_record                VARCHAR(64) NOT NULL,
    source_form_id           VARCHAR(64),
    visit_status             VARCHAR(2) NOT NULL,
    sd_visit_kind            VARCHAR(8) NOT NULL,
    dt_hy_plan               VARCHAR(10),
    dt_dbs_plan              VARCHAR(10),
    input_user               VARCHAR(64),
    id_user                  VARCHAR(64),
    form_data_json           TEXT NOT NULL,
    id_his_org               VARCHAR(64),
    na_his_org               VARCHAR(255),
    patient_id               VARCHAR(64) NOT NULL,
    visit_id                 VARCHAR(64),
    patient_name             VARCHAR(128) NOT NULL,
    disease_type             VARCHAR(32) NOT NULL,
    management_source        VARCHAR(32) NOT NULL,
    management_evidence      VARCHAR(2000) NOT NULL,
    template_version         VARCHAR(64) NOT NULL,
    path_version             VARCHAR(64) NOT NULL,
    evidence_version         VARCHAR(128) NOT NULL,
    rule_version             VARCHAR(64) NOT NULL,
    followup_date            DATE NOT NULL,
    followup_method          VARCHAR(32) NOT NULL,
    symptom_codes            VARCHAR(1000) NOT NULL,
    systolic_pressure        NUMERIC(4),
    diastolic_pressure       NUMERIC(4),
    fasting_glucose          NUMERIC(8,2),
    postprandial_glucose     NUMERIC(8,2),
    height_cm                NUMERIC(8,2),
    weight_kg                NUMERIC(8,2),
    bmi                      NUMERIC(6,2),
    waist_cm                 NUMERIC(8,2),
    daily_cigarettes         NUMERIC(4),
    daily_alcohol_units      NUMERIC(8,2),
    weekly_exercise_sessions NUMERIC(3),
    exercise_minutes         NUMERIC(4),
    salt_intake_level        VARCHAR(32) NOT NULL,
    psychological_status     VARCHAR(32) NOT NULL,
    medication_adherence     VARCHAR(32) NOT NULL,
    adverse_reaction         CHAR(1) DEFAULT '0' NOT NULL,
    adverse_reaction_text    VARCHAR(1000),
    medication_summary       VARCHAR(2000),
    followup_classification  VARCHAR(32) NOT NULL,
    referral_required        CHAR(1) DEFAULT '0' NOT NULL,
    referral_reason          VARCHAR(1000),
    referral_organization    VARCHAR(255),
    next_followup_date       DATE NOT NULL,
    id_doctor                VARCHAR(64),
    na_doctor                VARCHAR(128) NOT NULL,
    notes                    VARCHAR(2000),
    save_status              VARCHAR(16) DEFAULT 'saved' NOT NULL,
    fg_active                CHAR(1) DEFAULT '1' NOT NULL,
    insert_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE c_ai_chronic_followup
    ADD COLUMN IF NOT EXISTS management_source VARCHAR(32)
    DEFAULT 'legacy_unverified' NOT NULL;
ALTER TABLE c_ai_chronic_followup
    ADD COLUMN IF NOT EXISTS management_evidence VARCHAR(2000)
    DEFAULT '历史记录，未留存公卫在管依据' NOT NULL;
ALTER TABLE c_ai_chronic_followup ADD COLUMN IF NOT EXISTS id_phr VARCHAR(64);
ALTER TABLE c_ai_chronic_followup ADD COLUMN IF NOT EXISTS id_record VARCHAR(64);
ALTER TABLE c_ai_chronic_followup ADD COLUMN IF NOT EXISTS source_form_id VARCHAR(64);
ALTER TABLE c_ai_chronic_followup ADD COLUMN IF NOT EXISTS visit_status VARCHAR(2);
ALTER TABLE c_ai_chronic_followup ADD COLUMN IF NOT EXISTS sd_visit_kind VARCHAR(8);
ALTER TABLE c_ai_chronic_followup ADD COLUMN IF NOT EXISTS dt_hy_plan VARCHAR(10);
ALTER TABLE c_ai_chronic_followup ADD COLUMN IF NOT EXISTS dt_dbs_plan VARCHAR(10);
ALTER TABLE c_ai_chronic_followup ADD COLUMN IF NOT EXISTS input_user VARCHAR(64);
ALTER TABLE c_ai_chronic_followup ADD COLUMN IF NOT EXISTS id_user VARCHAR(64);
ALTER TABLE c_ai_chronic_followup ADD COLUMN IF NOT EXISTS form_data_json TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_c_ai_chronic_fu_req ON c_ai_chronic_followup (
    id_org,
    (CASE WHEN fg_active = '1' THEN request_id END)
);
CREATE INDEX IF NOT EXISTS idx_c_ai_chronic_fu_patient ON c_ai_chronic_followup (
    id_org, id_his_org, patient_id, followup_date, fg_active
);
CREATE INDEX IF NOT EXISTS idx_c_ai_chronic_fu_disease ON c_ai_chronic_followup (
    id_org, disease_type, followup_date, fg_active
);
CREATE INDEX IF NOT EXISTS idx_c_ai_chronic_fu_tcd ON c_ai_chronic_followup (
    id_org, id_phr, id_record, sd_visit_kind, fg_active
);

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
