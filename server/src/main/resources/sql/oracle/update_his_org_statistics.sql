-- One-time, idempotent deployed-schema compatibility upgrade for Oracle 19c.
-- Run as the application schema owner after taking a database backup.
WHENEVER SQLERROR EXIT 1 ROLLBACK

-- Fail before any DDL/DML when legacy feature events cannot be minimized safely.
DECLARE
    v_invalid_event_ids     NUMBER;
    v_invalid_feature_codes NUMBER;
    v_key_conflicts         NUMBER;
BEGIN
    SELECT COUNT(1)
      INTO v_invalid_event_ids
      FROM c_ai_feature_event
     WHERE id_event IS NULL OR NOT REGEXP_LIKE(
               TRIM(id_event),
               '^([0-9A-Fa-f]{32}|[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})$'
           );

    IF v_invalid_event_ids > 0 THEN
        RAISE_APPLICATION_ERROR(-20020, 'Feature-event privacy migration requires UUID event IDs');
    END IF;

    SELECT COUNT(1)
      INTO v_invalid_feature_codes
      FROM c_ai_feature_event
     WHERE feature_code IS NULL OR TRIM(feature_code) IS NULL;

    IF v_invalid_feature_codes > 0 THEN
        RAISE_APPLICATION_ERROR(-20022, 'Feature-event privacy migration requires non-empty feature codes');
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
            )
           GROUP BY id_device, candidate_key
          HAVING COUNT(1) > 1
             AND SUM(is_migration_candidate) > 0
      );

    IF v_key_conflicts > 0 THEN
        RAISE_APPLICATION_ERROR(-20021, 'Feature-event privacy migration found idempotency-key conflicts');
    END IF;
END;
/

DECLARE
    PROCEDURE create_table_if_missing(
        p_table_name IN VARCHAR2,
        p_statement  IN VARCHAR2
    ) IS
        v_count NUMBER;
    BEGIN
        SELECT COUNT(1)
          INTO v_count
          FROM user_tables
         WHERE table_name = UPPER(p_table_name);

        IF v_count = 0 THEN
            EXECUTE IMMEDIATE p_statement;
        END IF;
    END;

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
    create_table_if_missing(
        'c_ai_schema_migration',
        'CREATE TABLE c_ai_schema_migration (migration_key VARCHAR2(128) PRIMARY KEY, applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)'
    );

    -- These fields already exist in the current baseline but were omitted by some deployed databases.
    add_column_if_missing('c_ai_config', 'speech_realtime_url', 'VARCHAR2(500)');

    add_column_if_missing('c_ai_user_consultation_log', 'consultation_round_id', 'VARCHAR2(64)');
    add_column_if_missing('c_ai_user_consultation_log', 'id_his_org', 'VARCHAR2(64)');

    add_column_if_missing('c_ai_op_log', 'id_his_org', 'VARCHAR2(64)');
    add_column_if_missing('c_ai_op_log', 'na_his_org', 'VARCHAR2(255)');
    add_column_if_missing('c_ai_feature_event', 'id_his_org', 'VARCHAR2(64)');
    add_column_if_missing('c_ai_feature_event', 'na_his_org', 'VARCHAR2(255)');
    add_column_if_missing('c_ai_feature_event', 'cd_doctor', 'VARCHAR2(64)');
    add_column_if_missing('c_ai_feature_event', 'client_version', 'VARCHAR2(64)');

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
    create_index_if_missing(
        'idx_c_ai_feature_event_usage',
        'CREATE INDEX idx_c_ai_feature_event_usage ON c_ai_feature_event (id_org, id_his_org, cd_doctor, client_version, event_time, fg_active)'
    );
END;
/

COMMENT ON TABLE c_ai_schema_migration IS '应用业务Schema迁移完成标记';
COMMENT ON COLUMN c_ai_schema_migration.migration_key IS '稳定迁移标识';
COMMENT ON COLUMN c_ai_schema_migration.applied_at IS '迁移完成时间';
COMMENT ON COLUMN c_ai_config.speech_realtime_url IS '实时语音识别 WebSocket 上游地址';
COMMENT ON COLUMN c_ai_user_consultation_log.consultation_round_id IS '问诊轮次ID（客户端生成UUID，每轮问诊一个，贯穿该轮所有提交）';
COMMENT ON COLUMN c_ai_user_consultation_log.id_his_org IS 'HIS端机构ID（来自桌面端问诊上下文）';
COMMENT ON COLUMN c_ai_op_log.id_his_org IS 'HIS端机构ID（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_op_log.na_his_org IS 'HIS端机构名称（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.id_his_org IS 'HIS端机构ID（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.na_his_org IS 'HIS端机构名称（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.cd_doctor IS '医生真实工号（来自SDK握手urt.personCd）';
COMMENT ON COLUMN c_ai_feature_event.client_version IS '事件产生时的客户端版本';

-- Legacy NULL cd_doctor/client_version values are intentionally preserved; do not infer identity or version.

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
 WHERE payload_json IS NULL
    OR DBMS_LOB.COMPARE(payload_json, TO_CLOB('{}')) <> 0;

-- Replace every caller-supplied idempotency key with the versioned event UUID key.
UPDATE c_ai_feature_event
   SET idempotency_key = LOWER(TRIM(feature_code)) || ':minimized:v1:event:'
           || REPLACE(LOWER(TRIM(id_event)), '-', '')
 WHERE idempotency_key IS NULL
    OR idempotency_key <>
       LOWER(TRIM(feature_code)) || ':minimized:v1:event:'
           || REPLACE(LOWER(TRIM(id_event)), '-', '');

-- Readiness accepts the schema only after every minimization step has completed.
MERGE INTO c_ai_schema_migration target
USING (SELECT 'feature_event_minimization_v1' AS migration_key FROM DUAL) source
   ON (target.migration_key = source.migration_key)
 WHEN NOT MATCHED THEN
      INSERT (migration_key, applied_at)
      VALUES (source.migration_key, CURRENT_TIMESTAMP);

COMMIT;
