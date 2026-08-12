-- Dameng DM8 Oracle-compatible shared nonce schema for an explicitly enabled multi-node deployment.
-- Execute with the application schema before switching any application node to shared nonce mode.

WHENEVER SQLERROR EXIT 1 ROLLBACK

DECLARE
    v_table_count NUMBER;
BEGIN
    SELECT COUNT(1)
      INTO v_table_count
      FROM user_tables
     WHERE table_name = 'C_AI_REQUEST_NONCE';

    IF v_table_count = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE c_ai_request_nonce (
                id_device            VARCHAR2(32) NOT NULL,
                nonce_hash           VARCHAR2(64) NOT NULL,
                expires_at           NUMBER(19) NOT NULL,
                insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                CONSTRAINT pk_c_ai_request_nonce PRIMARY KEY (id_device, nonce_hash)
            )';
    END IF;
END;
/

DECLARE
    v_valid_column_count NUMBER;
    v_total_column_count NUMBER;
    v_valid_pk_count NUMBER;
BEGIN
    SELECT COUNT(1)
      INTO v_valid_column_count
      FROM user_tab_columns
     WHERE table_name = 'C_AI_REQUEST_NONCE'
       AND (
            (column_name = 'ID_DEVICE' AND data_type = 'VARCHAR2' AND char_length = 32 AND nullable = 'N')
         OR (column_name = 'NONCE_HASH' AND data_type = 'VARCHAR2' AND char_length = 64 AND nullable = 'N')
         OR (column_name = 'EXPIRES_AT' AND data_type = 'NUMBER' AND data_precision = 19
             AND NVL(data_scale, 0) = 0 AND nullable = 'N')
         OR (column_name = 'INSERT_TIME' AND data_type LIKE 'TIMESTAMP%' AND nullable = 'N'
             AND data_default IS NOT NULL)
       );

    SELECT COUNT(1)
      INTO v_total_column_count
      FROM user_tab_columns
     WHERE table_name = 'C_AI_REQUEST_NONCE';

    IF v_valid_column_count <> 4 OR v_total_column_count <> 4 THEN
        RAISE_APPLICATION_ERROR(-20011, 'C_AI_REQUEST_NONCE column contract drift detected');
    END IF;

    SELECT COUNT(1)
      INTO v_valid_pk_count
      FROM (
            SELECT c.constraint_name
              FROM user_constraints c
              JOIN user_cons_columns cc
                ON cc.constraint_name = c.constraint_name
               AND cc.table_name = c.table_name
             WHERE c.table_name = 'C_AI_REQUEST_NONCE'
               AND c.constraint_type = 'P'
             GROUP BY c.constraint_name
            HAVING COUNT(1) = 2
               AND SUM(CASE WHEN cc.position = 1 AND cc.column_name = 'ID_DEVICE' THEN 1 ELSE 0 END) = 1
               AND SUM(CASE WHEN cc.position = 2 AND cc.column_name = 'NONCE_HASH' THEN 1 ELSE 0 END) = 1
           );

    IF v_valid_pk_count <> 1 THEN
        RAISE_APPLICATION_ERROR(-20012, 'C_AI_REQUEST_NONCE primary-key contract drift detected');
    END IF;
END;
/

DECLARE
    v_named_index_count NUMBER;
    v_valid_index_count NUMBER;
BEGIN
    SELECT COUNT(1)
      INTO v_named_index_count
      FROM user_indexes
     WHERE index_name = 'IDX_C_AI_REQUEST_NONCE_EXP';

    IF v_named_index_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_c_ai_request_nonce_exp ON c_ai_request_nonce (expires_at)';
    END IF;

    SELECT COUNT(1)
      INTO v_valid_index_count
      FROM (
            SELECT i.index_name
              FROM user_indexes i
              JOIN user_ind_columns ic
                ON ic.index_name = i.index_name
               AND ic.table_name = i.table_name
             WHERE i.index_name = 'IDX_C_AI_REQUEST_NONCE_EXP'
               AND i.table_name = 'C_AI_REQUEST_NONCE'
               AND i.uniqueness = 'NONUNIQUE'
             GROUP BY i.index_name
            HAVING COUNT(1) = 1
               AND SUM(CASE WHEN ic.column_position = 1 AND ic.column_name = 'EXPIRES_AT' THEN 1 ELSE 0 END) = 1
           );

    IF v_valid_index_count <> 1 THEN
        RAISE_APPLICATION_ERROR(-20013, 'IDX_C_AI_REQUEST_NONCE_EXP index contract drift detected');
    END IF;
END;
/

COMMENT ON TABLE c_ai_request_nonce IS '多节点请求签名nonce共享防重放表';
COMMENT ON COLUMN c_ai_request_nonce.id_device IS '设备ID，与nonce哈希共同唯一';
COMMENT ON COLUMN c_ai_request_nonce.nonce_hash IS '请求nonce的SHA-256十六进制哈希';
COMMENT ON COLUMN c_ai_request_nonce.expires_at IS 'nonce安全窗口过期时间，epoch毫秒';
COMMENT ON COLUMN c_ai_request_nonce.insert_time IS '登记时间';

COMMIT;
