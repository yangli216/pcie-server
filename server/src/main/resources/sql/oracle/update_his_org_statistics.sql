-- One-time, idempotent deployed-schema compatibility upgrade for Oracle 19c.
-- Run as the application schema owner after taking a database backup.

DECLARE
    PROCEDURE add_column_if_missing(
        p_table_name  IN VARCHAR2,
        p_column_name IN VARCHAR2,
        p_definition  IN VARCHAR2
    ) IS
        v_count NUMBER;
    BEGIN
        SELECT COUNT(1)
          INTO v_count
          FROM user_tab_columns
         WHERE table_name = UPPER(p_table_name)
           AND column_name = UPPER(p_column_name);

        IF v_count = 0 THEN
            EXECUTE IMMEDIATE 'ALTER TABLE ' || p_table_name || ' ADD (' || p_column_name || ' ' || p_definition || ')';
        END IF;
    END;

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
    -- These fields already exist in the current baseline but were omitted by some deployed databases.
    add_column_if_missing('c_ai_config', 'speech_realtime_url', 'VARCHAR2(500)');

    add_column_if_missing('c_ai_user_consultation_log', 'consultation_round_id', 'VARCHAR2(64)');
    add_column_if_missing('c_ai_user_consultation_log', 'id_his_org', 'VARCHAR2(64)');
    add_column_if_missing('c_ai_user_consultation_log', 'cd_doctor', 'VARCHAR2(64)');

    add_column_if_missing('c_ai_op_log', 'id_his_org', 'VARCHAR2(64)');
    add_column_if_missing('c_ai_op_log', 'na_his_org', 'VARCHAR2(255)');
    add_column_if_missing('c_ai_feature_event', 'id_his_org', 'VARCHAR2(64)');
    add_column_if_missing('c_ai_feature_event', 'na_his_org', 'VARCHAR2(255)');

    create_index_if_missing(
        'idx_c_ai_user_log_round',
        'CREATE INDEX idx_c_ai_user_log_round ON c_ai_user_consultation_log (consultation_round_id, fg_active)'
    );
    create_index_if_missing(
        'uk_c_ai_user_log_round_active',
        'CREATE UNIQUE INDEX uk_c_ai_user_log_round_active ON c_ai_user_consultation_log (CASE WHEN fg_active = ''1'' AND status = ''generated'' THEN consultation_round_id END)'
    );
    create_index_if_missing(
        'idx_c_ai_user_log_his_org',
        'CREATE INDEX idx_c_ai_user_log_his_org ON c_ai_user_consultation_log (id_his_org, consultation_time, fg_active)'
    );
    create_index_if_missing(
        'idx_c_ai_op_log_his_org',
        'CREATE INDEX idx_c_ai_op_log_his_org ON c_ai_op_log (id_his_org, operation_time, fg_active)'
    );
    create_index_if_missing(
        'idx_c_ai_feature_event_his_org',
        'CREATE INDEX idx_c_ai_feature_event_his_org ON c_ai_feature_event (id_his_org, event_time, fg_active)'
    );
END;
/

COMMENT ON COLUMN c_ai_config.speech_realtime_url IS '实时语音识别 WebSocket 上游地址';
COMMENT ON COLUMN c_ai_user_consultation_log.consultation_round_id IS '问诊轮次ID（客户端生成UUID，每轮问诊一个，贯穿该轮所有提交）';
COMMENT ON COLUMN c_ai_user_consultation_log.id_his_org IS 'HIS端机构ID（来自桌面端问诊上下文）';
COMMENT ON COLUMN c_ai_user_consultation_log.cd_doctor IS '医生真实工号（来自SDK握手urt.personCd）';
COMMENT ON COLUMN c_ai_op_log.id_his_org IS 'HIS端机构ID（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_op_log.na_his_org IS 'HIS端机构名称（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.id_his_org IS 'HIS端机构ID（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.na_his_org IS 'HIS端机构名称（来自桌面端事件上下文）';

-- Backfill only rows that can be associated with an existing consultation fact.
UPDATE c_ai_feature_event e
   SET id_his_org = (
           SELECT MAX(u.id_his_org)
             FROM c_ai_user_consultation_log u
            WHERE u.fg_active = '1'
              AND u.id_his_org IS NOT NULL
              AND u.id_org = e.id_org
              AND u.id_device = e.id_device
              AND u.consultation_id = e.consultation_id
       ),
       na_his_org = (
           SELECT MAX(u.na_org)
             FROM c_ai_user_consultation_log u
            WHERE u.fg_active = '1'
              AND u.id_his_org IS NOT NULL
              AND u.id_org = e.id_org
              AND u.id_device = e.id_device
              AND u.consultation_id = e.consultation_id
       )
 WHERE e.id_his_org IS NULL
   AND e.consultation_id IS NOT NULL
   AND 1 = (
       SELECT COUNT(DISTINCT u.id_his_org)
         FROM c_ai_user_consultation_log u
        WHERE u.fg_active = '1'
          AND u.id_org = e.id_org
          AND u.id_device = e.id_device
          AND u.consultation_id = e.consultation_id
   );

UPDATE c_ai_op_log l
   SET id_his_org = (
           SELECT MAX(u.id_his_org)
             FROM c_ai_user_consultation_log u
            WHERE u.fg_active = '1'
              AND u.id_his_org IS NOT NULL
              AND u.id_org = l.id_org
              AND u.id_device = l.id_device
              AND u.consultation_id = l.consultation_id
       ),
       na_his_org = (
           SELECT MAX(u.na_org)
             FROM c_ai_user_consultation_log u
            WHERE u.fg_active = '1'
              AND u.id_his_org IS NOT NULL
              AND u.id_org = l.id_org
              AND u.id_device = l.id_device
              AND u.consultation_id = l.consultation_id
       )
 WHERE l.id_his_org IS NULL
   AND l.consultation_id IS NOT NULL
   AND 1 = (
       SELECT COUNT(DISTINCT u.id_his_org)
         FROM c_ai_user_consultation_log u
        WHERE u.fg_active = '1'
          AND u.id_org = l.id_org
          AND u.id_device = l.id_device
          AND u.consultation_id = l.consultation_id
   );

COMMIT;
