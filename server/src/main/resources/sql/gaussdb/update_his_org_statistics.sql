-- One-time, idempotent deployed-schema compatibility upgrade for GaussDB/openGauss.
-- Run as the application schema owner after taking a database backup.

-- These fields already exist in the current baseline but were omitted by some deployed databases.
ALTER TABLE c_ai_config ADD COLUMN IF NOT EXISTS speech_realtime_url VARCHAR(500);

ALTER TABLE c_ai_user_consultation_log ADD COLUMN IF NOT EXISTS consultation_round_id VARCHAR(64);
ALTER TABLE c_ai_user_consultation_log ADD COLUMN IF NOT EXISTS id_his_org VARCHAR(64);
ALTER TABLE c_ai_user_consultation_log ADD COLUMN IF NOT EXISTS cd_doctor VARCHAR(64);

ALTER TABLE c_ai_op_log ADD COLUMN IF NOT EXISTS id_his_org VARCHAR(64);
ALTER TABLE c_ai_op_log ADD COLUMN IF NOT EXISTS na_his_org VARCHAR(255);
ALTER TABLE c_ai_feature_event ADD COLUMN IF NOT EXISTS id_his_org VARCHAR(64);
ALTER TABLE c_ai_feature_event ADD COLUMN IF NOT EXISTS na_his_org VARCHAR(255);

COMMENT ON COLUMN c_ai_config.speech_realtime_url IS '实时语音识别 WebSocket 上游地址';
COMMENT ON COLUMN c_ai_user_consultation_log.consultation_round_id IS '问诊轮次ID（客户端生成UUID，每轮问诊一个，贯穿该轮所有提交）';
COMMENT ON COLUMN c_ai_user_consultation_log.id_his_org IS 'HIS端机构ID（来自桌面端问诊上下文）';
COMMENT ON COLUMN c_ai_user_consultation_log.cd_doctor IS '医生真实工号（来自SDK握手urt.personCd）';
COMMENT ON COLUMN c_ai_op_log.id_his_org IS 'HIS端机构ID（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_op_log.na_his_org IS 'HIS端机构名称（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.id_his_org IS 'HIS端机构ID（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.na_his_org IS 'HIS端机构名称（来自桌面端事件上下文）';

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

COMMIT;
