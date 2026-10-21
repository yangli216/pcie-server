\set ON_ERROR_STOP on

-- PCIE Server: one-time inpatient EMR log migration, 2026-10-21.
-- GaussDB/openGauss PostgreSQL-compatible mode only; run with gsql.
-- Run in a dedicated session with the application's database/schema/search_path.
-- Existing objects are an error, including on a successful second run.
-- See docs/inpatient-emr-logs-migration.md before execution.
BEGIN;

SELECT current_database(), current_user, current_schema();
DO $$
DECLARE
    v_count INTEGER;
BEGIN
    IF current_schema() IS NULL THEN
        RAISE EXCEPTION 'No effective schema in search_path.';
    END IF;
    SELECT COUNT(*) INTO v_count FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = current_schema() AND c.relkind = 'r' AND c.relname IN ('c_ai_device', 'c_ai_org');
    IF v_count <> 2 THEN
        RAISE EXCEPTION 'Application base tables missing in current schema.';
    END IF;
    SELECT COUNT(*) INTO v_count FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = current_schema() AND c.relname IN (
        'c_ai_inpatient_emr_log',
        'c_ai_inpatient_emr_log_event',
        'uk_c_ai_inp_log_round',
        'idx_c_ai_inp_log_time',
        'idx_c_ai_inp_log_org',
        'idx_c_ai_inp_log_patient',
        'idx_c_ai_inp_log_template',
        'uk_c_ai_inp_event_id',
        'uk_c_ai_inp_event_seq'
    );
    IF v_count <> 0 THEN
        RAISE EXCEPTION 'Target objects already exist. DBA must inspect before migration.';
    END IF;
END;
$$;

-- BEGIN INPATIENT LOG DDL
CREATE TABLE c_ai_inpatient_emr_log (
    id_log VARCHAR(32) PRIMARY KEY,
    id_device VARCHAR(32) NOT NULL,
    id_org VARCHAR(32) NOT NULL,
    generation_id VARCHAR(36) NOT NULL,
    admission_id VARCHAR(128) NOT NULL,
    template_id VARCHAR(128) NOT NULL,
    template_name VARCHAR(256) NOT NULL,
    template_hash VARCHAR(64) NOT NULL,
    his_org_id VARCHAR(128) ,
    org_name VARCHAR(256) ,
    doctor_id VARCHAR(128) ,
    doctor_name VARCHAR(128) ,
    patient_id VARCHAR(128) ,
    patient_name VARCHAR(128) ,
    patient_gender VARCHAR(32) ,
    patient_age VARCHAR(32) ,
    record_time VARCHAR(64) ,
    generation_time TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL,
    latest_sequence NUMERIC(10) NOT NULL,
    snapshot_json TEXT NOT NULL,
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
    id_event VARCHAR(32) PRIMARY KEY,
    id_log VARCHAR(32) NOT NULL REFERENCES c_ai_inpatient_emr_log(id_log),
    id_device VARCHAR(32) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    event_sequence NUMERIC(10) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    message VARCHAR(512) ,
    body_hash VARCHAR(64) NOT NULL,
    snapshot_json TEXT NOT NULL
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

DO $$
DECLARE
    v_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name IN ('c_ai_inpatient_emr_log', 'c_ai_inpatient_emr_log_event');
    IF v_count <> 35 THEN
        RAISE EXCEPTION 'Expected 35 inpatient log columns.';
    END IF;
    SELECT COUNT(*) INTO v_count FROM pg_constraint k
    JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = current_schema()
      AND c.relname IN ('c_ai_inpatient_emr_log', 'c_ai_inpatient_emr_log_event')
      AND k.contype IN ('p', 'f') AND k.convalidated;
    IF v_count <> 3 THEN
        RAISE EXCEPTION 'Expected two primary keys and one validated foreign key.';
    END IF;
    SELECT COUNT(*) INTO v_count FROM pg_index i
    JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_class t ON t.oid = i.indrelid
    WHERE n.nspname = current_schema() AND i.indisvalid
      AND t.relname IN ('c_ai_inpatient_emr_log', 'c_ai_inpatient_emr_log_event')
      AND c.relname IN (
        'uk_c_ai_inp_log_round',
        'idx_c_ai_inp_log_time',
        'idx_c_ai_inp_log_org',
        'idx_c_ai_inp_log_patient',
        'idx_c_ai_inp_log_template',
        'uk_c_ai_inp_event_id',
        'uk_c_ai_inp_event_seq'
    );
    IF v_count <> 7 THEN
        RAISE EXCEPTION 'Expected seven valid named inpatient log indexes.';
    END IF;
END;
$$;

SELECT table_name FROM information_schema.tables
WHERE table_schema = current_schema()
  AND table_name IN ('c_ai_inpatient_emr_log', 'c_ai_inpatient_emr_log_event') ORDER BY table_name;
SELECT indexname, tablename, indexdef FROM pg_indexes
WHERE schemaname = current_schema()
  AND tablename IN ('c_ai_inpatient_emr_log', 'c_ai_inpatient_emr_log_event') ORDER BY indexname;
SELECT c.relname AS table_name, k.conname AS constraint_name, k.contype AS constraint_type
FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = current_schema()
  AND c.relname IN ('c_ai_inpatient_emr_log', 'c_ai_inpatient_emr_log_event')
  AND k.contype IN ('p', 'f') ORDER BY c.relname, k.contype;

COMMIT;
\echo Inpatient EMR log migration completed. Review validation output before enabling clients.
