-- One-time, idempotent deployed-schema compatibility upgrade for GaussDB/openGauss.
-- Run as the application schema owner after taking a database backup.
\set ON_ERROR_STOP on

-- Fail before any DDL/DML when legacy feature events cannot be minimized safely.
DO $$
DECLARE
    v_invalid_event_ids     BIGINT;
    v_invalid_feature_codes BIGINT;
    v_key_conflicts         BIGINT;
BEGIN
    SELECT COUNT(1)
      INTO v_invalid_event_ids
      FROM c_ai_feature_event
     WHERE id_event IS NULL OR TRIM(id_event) !~
           '^([0-9A-Fa-f]{32}|[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})$');

    IF v_invalid_event_ids > 0 THEN
        RAISE EXCEPTION 'Feature-event privacy migration requires UUID event IDs';
    END IF;

    SELECT COUNT(1)
      INTO v_invalid_feature_codes
      FROM c_ai_feature_event
     WHERE feature_code IS NULL OR TRIM(feature_code) = '';

    IF v_invalid_feature_codes > 0 THEN
        RAISE EXCEPTION 'Feature-event privacy migration requires non-empty feature codes';
    END IF;

    SELECT COUNT(1)
      INTO v_key_conflicts
      FROM (
          SELECT id_device, candidate_key
            FROM (
                SELECT id_device, idempotency_key AS candidate_key, 0 AS is_migration_candidate
                  FROM c_ai_feature_event
                UNION ALL
                SELECT id_device,
                       LOWER(TRIM(feature_code)) || ':minimized:v1:event:'
                           || REPLACE(LOWER(TRIM(id_event)), '-', '') AS candidate_key,
                       1 AS is_migration_candidate
                  FROM c_ai_feature_event
                 WHERE idempotency_key IS NULL
                    OR idempotency_key <>
                       LOWER(TRIM(feature_code)) || ':minimized:v1:event:'
                           || REPLACE(LOWER(TRIM(id_event)), '-', '')
            ) candidates
           GROUP BY id_device, candidate_key
          HAVING COUNT(1) > 1
             AND SUM(is_migration_candidate) > 0
      ) conflicts;

    IF v_key_conflicts > 0 THEN
        RAISE EXCEPTION 'Feature-event privacy migration found idempotency-key conflicts';
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS c_ai_schema_migration (
    migration_key VARCHAR(128) PRIMARY KEY,
    applied_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE c_ai_schema_migration IS '应用业务Schema迁移完成标记';
COMMENT ON COLUMN c_ai_schema_migration.migration_key IS '稳定迁移标识';
COMMENT ON COLUMN c_ai_schema_migration.applied_at IS '迁移完成时间';

-- These fields already exist in the current baseline but were omitted by some deployed databases.
ALTER TABLE c_ai_config ADD COLUMN IF NOT EXISTS speech_realtime_url VARCHAR(500);

ALTER TABLE c_ai_user_consultation_log ADD COLUMN IF NOT EXISTS consultation_round_id VARCHAR(64);
ALTER TABLE c_ai_user_consultation_log ADD COLUMN IF NOT EXISTS id_his_org VARCHAR(64);
ALTER TABLE c_ai_user_consultation_log ADD COLUMN IF NOT EXISTS cd_doctor VARCHAR(64);

ALTER TABLE c_ai_op_log ADD COLUMN IF NOT EXISTS id_his_org VARCHAR(64);
ALTER TABLE c_ai_op_log ADD COLUMN IF NOT EXISTS na_his_org VARCHAR(255);
ALTER TABLE c_ai_feature_event ADD COLUMN IF NOT EXISTS id_his_org VARCHAR(64);
ALTER TABLE c_ai_feature_event ADD COLUMN IF NOT EXISTS na_his_org VARCHAR(255);
ALTER TABLE c_ai_feature_event ADD COLUMN IF NOT EXISTS cd_doctor VARCHAR(64);
ALTER TABLE c_ai_feature_event ADD COLUMN IF NOT EXISTS client_version VARCHAR(64);

COMMENT ON COLUMN c_ai_config.speech_realtime_url IS '实时语音识别 WebSocket 上游地址';
COMMENT ON COLUMN c_ai_user_consultation_log.consultation_round_id IS '问诊轮次ID（客户端生成UUID，每轮问诊一个，贯穿该轮所有提交）';
COMMENT ON COLUMN c_ai_user_consultation_log.id_his_org IS 'HIS端机构ID（来自桌面端问诊上下文）';
COMMENT ON COLUMN c_ai_user_consultation_log.cd_doctor IS '医生真实工号（来自SDK握手urt.personCd）';
COMMENT ON COLUMN c_ai_op_log.id_his_org IS 'HIS端机构ID（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_op_log.na_his_org IS 'HIS端机构名称（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.id_his_org IS 'HIS端机构ID（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.na_his_org IS 'HIS端机构名称（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.cd_doctor IS '医生真实工号（来自SDK握手urt.personCd）';
COMMENT ON COLUMN c_ai_feature_event.client_version IS '事件产生时的客户端版本';

CREATE INDEX IF NOT EXISTS idx_c_ai_user_log_round
    ON c_ai_user_consultation_log (consultation_round_id, fg_active);
CREATE UNIQUE INDEX IF NOT EXISTS uk_c_ai_user_log_round_active
    ON c_ai_user_consultation_log (
        (CASE WHEN fg_active = '1' AND status = 'generated' THEN consultation_round_id END)
    );
CREATE INDEX IF NOT EXISTS idx_c_ai_user_log_his_org
    ON c_ai_user_consultation_log (id_his_org, consultation_time, fg_active);
CREATE INDEX IF NOT EXISTS idx_c_ai_op_log_his_org
    ON c_ai_op_log (id_his_org, operation_time, fg_active);
CREATE INDEX IF NOT EXISTS idx_c_ai_feature_event_his_org
    ON c_ai_feature_event (id_his_org, event_time, fg_active);
CREATE INDEX IF NOT EXISTS idx_c_ai_feature_event_usage
    ON c_ai_feature_event (id_org, id_his_org, cd_doctor, client_version, event_time, fg_active);

-- Legacy NULL cd_doctor/client_version values are intentionally preserved; do not infer identity or version.

UPDATE c_ai_feature_event e
   SET id_his_org = source.id_his_org,
       na_his_org = source.na_his_org
  FROM (
      SELECT id_org, id_device, consultation_id,
             MAX(id_his_org) AS id_his_org,
             MAX(na_org) AS na_his_org
        FROM c_ai_user_consultation_log
       WHERE fg_active = '1'
         AND id_his_org IS NOT NULL
       GROUP BY id_org, id_device, consultation_id
      HAVING COUNT(DISTINCT id_his_org) = 1
  ) source
 WHERE e.id_his_org IS NULL
   AND e.consultation_id IS NOT NULL
   AND e.id_org = source.id_org
   AND e.id_device = source.id_device
   AND e.consultation_id = source.consultation_id;

UPDATE c_ai_op_log l
   SET id_his_org = source.id_his_org,
       na_his_org = source.na_his_org
  FROM (
      SELECT id_org, id_device, consultation_id,
             MAX(id_his_org) AS id_his_org,
             MAX(na_org) AS na_his_org
        FROM c_ai_user_consultation_log
       WHERE fg_active = '1'
         AND id_his_org IS NOT NULL
       GROUP BY id_org, id_device, consultation_id
      HAVING COUNT(DISTINCT id_his_org) = 1
  ) source
 WHERE l.id_his_org IS NULL
   AND l.consultation_id IS NOT NULL
   AND l.id_org = source.id_org
   AND l.id_device = source.id_device
   AND l.consultation_id = source.consultation_id;

-- Feature-usage facts never retain patient/visit or cross-table correlation identifiers.
UPDATE c_ai_feature_event
   SET consultation_id = NULL,
       trace_id = NULL,
       session_id = NULL
 WHERE consultation_id IS NOT NULL
    OR trace_id IS NOT NULL
    OR session_id IS NOT NULL;

-- Remove every legacy extension payload independently from key migration.
UPDATE c_ai_feature_event
   SET payload_json = '{}'
 WHERE payload_json IS DISTINCT FROM '{}';

-- Replace every caller-supplied idempotency key with the versioned event UUID key.
UPDATE c_ai_feature_event
   SET idempotency_key = LOWER(TRIM(feature_code)) || ':minimized:v1:event:'
           || REPLACE(LOWER(TRIM(id_event)), '-', '')
 WHERE idempotency_key IS NULL
    OR idempotency_key <>
       LOWER(TRIM(feature_code)) || ':minimized:v1:event:'
           || REPLACE(LOWER(TRIM(id_event)), '-', '');

-- Readiness accepts the schema only after every minimization step has completed.
INSERT INTO c_ai_schema_migration (migration_key, applied_at)
SELECT 'feature_event_minimization_v1', CURRENT_TIMESTAMP
 WHERE NOT EXISTS (
     SELECT 1
       FROM c_ai_schema_migration
      WHERE migration_key = 'feature_event_minimization_v1'
 );

COMMIT;
