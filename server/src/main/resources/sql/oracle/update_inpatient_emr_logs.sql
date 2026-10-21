WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET DEFINE OFF
SET SQLBLANKLINES ON
SET SERVEROUTPUT ON

-- PCIE Server: one-time inpatient EMR log migration, 2026-10-21.
-- Run as the application owner in SQL*Plus/SQLcl, in a dedicated session.
-- Existing objects are an error, including on a successful second run.
-- Oracle DDL commits automatically: ROLLBACK cannot undo completed DDL.
-- See docs/inpatient-emr-logs-migration.md before execution.
PROMPT Checking application schema and target object names...
DECLARE
    v_count NUMBER;
BEGIN
    DBMS_OUTPUT.PUT_LINE('User=' || USER || ', schema=' || SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'));
    IF SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') <> USER THEN
        RAISE_APPLICATION_ERROR(-20001, 'Connect directly as the application schema owner.');
    END IF;
    SELECT COUNT(*) INTO v_count FROM user_tables
    WHERE table_name IN ('C_AI_DEVICE', 'C_AI_ORG');
    IF v_count <> 2 THEN
        RAISE_APPLICATION_ERROR(-20002, 'Application base tables missing in current schema.');
    END IF;
    SELECT COUNT(*) INTO v_count FROM user_objects
    WHERE object_name IN (
        'C_AI_INPATIENT_EMR_LOG',
        'C_AI_INPATIENT_EMR_LOG_EVENT',
        'UK_C_AI_INP_LOG_ROUND',
        'IDX_C_AI_INP_LOG_TIME',
        'IDX_C_AI_INP_LOG_ORG',
        'IDX_C_AI_INP_LOG_PATIENT',
        'IDX_C_AI_INP_LOG_TEMPLATE',
        'UK_C_AI_INP_EVENT_ID',
        'UK_C_AI_INP_EVENT_SEQ'
    );
    IF v_count <> 0 THEN
        RAISE_APPLICATION_ERROR(-20003, 'Target objects already exist. DBA must inspect before migration.');
    END IF;
END;
/

-- BEGIN INPATIENT LOG DDL
CREATE TABLE c_ai_inpatient_emr_log (
    id_log VARCHAR2(32 CHAR) PRIMARY KEY,
    id_device VARCHAR2(32 CHAR) NOT NULL,
    id_org VARCHAR2(32 CHAR) NOT NULL,
    generation_id VARCHAR2(36 CHAR) NOT NULL,
    admission_id VARCHAR2(128 CHAR) NOT NULL,
    template_id VARCHAR2(128 CHAR) NOT NULL,
    template_name VARCHAR2(256 CHAR) NOT NULL,
    template_hash VARCHAR2(64 CHAR) NOT NULL,
    his_org_id VARCHAR2(128 CHAR) ,
    org_name VARCHAR2(256 CHAR) ,
    doctor_id VARCHAR2(128 CHAR) ,
    doctor_name VARCHAR2(128 CHAR) ,
    patient_id VARCHAR2(128 CHAR) ,
    patient_name VARCHAR2(128 CHAR) ,
    patient_gender VARCHAR2(32 CHAR) ,
    patient_age VARCHAR2(32 CHAR) ,
    record_time VARCHAR2(64 CHAR) ,
    generation_time TIMESTAMP NOT NULL,
    status VARCHAR2(32 CHAR) NOT NULL,
    latest_sequence NUMBER(10) NOT NULL,
    snapshot_json CLOB NOT NULL,
    fg_active CHAR(1) DEFAULT '1' NOT NULL,
    insert_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE c_ai_inpatient_emr_log IS '住院病历生成轮次日志';
COMMENT ON COLUMN c_ai_inpatient_emr_log.id_log IS '日志主键';
COMMENT ON COLUMN c_ai_inpatient_emr_log.id_device IS '鉴权设备';
COMMENT ON COLUMN c_ai_inpatient_emr_log.id_org IS '鉴权后台机构';
COMMENT ON COLUMN c_ai_inpatient_emr_log.generation_id IS '每次生成UUID';
COMMENT ON COLUMN c_ai_inpatient_emr_log.admission_id IS '住院锚点';
COMMENT ON COLUMN c_ai_inpatient_emr_log.template_id IS '模板主键';
COMMENT ON COLUMN c_ai_inpatient_emr_log.template_name IS '模板名称快照';
COMMENT ON COLUMN c_ai_inpatient_emr_log.template_hash IS '原始模板HTML的SHA256';
COMMENT ON COLUMN c_ai_inpatient_emr_log.his_org_id IS 'HIS机构快照';
COMMENT ON COLUMN c_ai_inpatient_emr_log.org_name IS '机构名称快照';
COMMENT ON COLUMN c_ai_inpatient_emr_log.doctor_id IS '医生ID快照';
COMMENT ON COLUMN c_ai_inpatient_emr_log.doctor_name IS '医生姓名快照';
COMMENT ON COLUMN c_ai_inpatient_emr_log.patient_id IS '患者主键';
COMMENT ON COLUMN c_ai_inpatient_emr_log.patient_name IS '患者姓名';
COMMENT ON COLUMN c_ai_inpatient_emr_log.patient_gender IS '患者性别';
COMMENT ON COLUMN c_ai_inpatient_emr_log.patient_age IS '完整年龄含单位';
COMMENT ON COLUMN c_ai_inpatient_emr_log.record_time IS '病历书写时间';
COMMENT ON COLUMN c_ai_inpatient_emr_log.generation_time IS '生成开始时间';
COMMENT ON COLUMN c_ai_inpatient_emr_log.status IS '业务处理状态';
COMMENT ON COLUMN c_ai_inpatient_emr_log.latest_sequence IS '最新事件序号';
COMMENT ON COLUMN c_ai_inpatient_emr_log.snapshot_json IS '最新强类型完整快照JSON';
COMMENT ON COLUMN c_ai_inpatient_emr_log.fg_active IS '有效标记';
COMMENT ON COLUMN c_ai_inpatient_emr_log.insert_time IS '接收时间';
COMMENT ON COLUMN c_ai_inpatient_emr_log.update_time IS '更新时间';
CREATE TABLE c_ai_inpatient_emr_log_event (
    id_event VARCHAR2(32 CHAR) PRIMARY KEY,
    id_log VARCHAR2(32 CHAR) NOT NULL REFERENCES c_ai_inpatient_emr_log(id_log),
    id_device VARCHAR2(32 CHAR) NOT NULL,
    event_id VARCHAR2(36 CHAR) NOT NULL,
    event_sequence NUMBER(10) NOT NULL,
    event_type VARCHAR2(32 CHAR) NOT NULL,
    status VARCHAR2(32 CHAR) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    message VARCHAR2(512 CHAR) ,
    body_hash VARCHAR2(64 CHAR) NOT NULL,
    snapshot_json CLOB NOT NULL
);
COMMENT ON TABLE c_ai_inpatient_emr_log_event IS '住院病历日志事件历史';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.id_event IS '事件主键';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.id_log IS '生成轮次记录';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.id_device IS '鉴权设备';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.event_id IS '客户端事件UUID';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.event_sequence IS '轮次内递增序号';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.event_type IS '生命周期动作';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.status IS '事件对应状态';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.occurred_at IS '事件发生时间';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.message IS '事件简短说明';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.body_hash IS '强类型正文SHA256防冲突';
COMMENT ON COLUMN c_ai_inpatient_emr_log_event.snapshot_json IS '不可变事件快照JSON';
CREATE UNIQUE INDEX uk_c_ai_inp_log_round ON c_ai_inpatient_emr_log (id_device, generation_id);
CREATE INDEX idx_c_ai_inp_log_time ON c_ai_inpatient_emr_log (generation_time, fg_active);
CREATE INDEX idx_c_ai_inp_log_org ON c_ai_inpatient_emr_log (his_org_id, generation_time, fg_active);
CREATE INDEX idx_c_ai_inp_log_patient ON c_ai_inpatient_emr_log (patient_id, admission_id, generation_time);
CREATE INDEX idx_c_ai_inp_log_template ON c_ai_inpatient_emr_log (template_id, status, generation_time);
CREATE UNIQUE INDEX uk_c_ai_inp_event_id ON c_ai_inpatient_emr_log_event (id_device, event_id);
CREATE UNIQUE INDEX uk_c_ai_inp_event_seq ON c_ai_inpatient_emr_log_event (id_log, event_sequence);

-- END INPATIENT LOG DDL

PROMPT Validating newly created inpatient log objects...
DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM user_tab_columns
    WHERE table_name IN ('C_AI_INPATIENT_EMR_LOG', 'C_AI_INPATIENT_EMR_LOG_EVENT');
    IF v_count <> 35 THEN
        RAISE_APPLICATION_ERROR(-20004, 'Expected 35 inpatient log columns.');
    END IF;
    SELECT COUNT(*) INTO v_count FROM user_constraints
    WHERE table_name IN ('C_AI_INPATIENT_EMR_LOG', 'C_AI_INPATIENT_EMR_LOG_EVENT')
      AND constraint_type IN ('P', 'R') AND status = 'ENABLED' AND validated = 'VALIDATED';
    IF v_count <> 3 THEN
        RAISE_APPLICATION_ERROR(-20005, 'Expected two primary keys and one enabled foreign key.');
    END IF;
    SELECT COUNT(*) INTO v_count FROM user_indexes
    WHERE table_name IN ('C_AI_INPATIENT_EMR_LOG', 'C_AI_INPATIENT_EMR_LOG_EVENT')
      AND status = 'VALID' AND index_name IN (
        'UK_C_AI_INP_LOG_ROUND',
        'IDX_C_AI_INP_LOG_TIME',
        'IDX_C_AI_INP_LOG_ORG',
        'IDX_C_AI_INP_LOG_PATIENT',
        'IDX_C_AI_INP_LOG_TEMPLATE',
        'UK_C_AI_INP_EVENT_ID',
        'UK_C_AI_INP_EVENT_SEQ'
    );
    IF v_count <> 7 THEN
        RAISE_APPLICATION_ERROR(-20006, 'Expected seven valid named inpatient log indexes.');
    END IF;
END;
/

SELECT table_name FROM user_tables
WHERE table_name IN ('C_AI_INPATIENT_EMR_LOG', 'C_AI_INPATIENT_EMR_LOG_EVENT') ORDER BY table_name;
SELECT index_name, table_name, uniqueness, status FROM user_indexes
WHERE table_name IN ('C_AI_INPATIENT_EMR_LOG', 'C_AI_INPATIENT_EMR_LOG_EVENT') ORDER BY index_name;
SELECT table_name, constraint_name, constraint_type, status FROM user_constraints
WHERE table_name IN ('C_AI_INPATIENT_EMR_LOG', 'C_AI_INPATIENT_EMR_LOG_EVENT')
  AND constraint_type IN ('P', 'R') ORDER BY table_name, constraint_type;

COMMIT;
PROMPT Inpatient EMR log migration completed. Review validation output before enabling clients.
EXIT SUCCESS
