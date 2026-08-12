-- GaussDB/openGauss shared nonce schema for an explicitly enabled multi-node deployment.
-- DBA-reviewed manual execution only. Stop on the first error and roll back the transaction.

\set ON_ERROR_STOP on

BEGIN;

CREATE TABLE IF NOT EXISTS c_ai_request_nonce (
    id_device            VARCHAR(32) NOT NULL,
    nonce_hash           VARCHAR(64) NOT NULL,
    expires_at           BIGINT NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_c_ai_request_nonce PRIMARY KEY (id_device, nonce_hash)
);

DO $$
DECLARE
    v_valid_column_count INTEGER;
    v_total_column_count INTEGER;
    v_valid_pk_count INTEGER;
BEGIN
    SELECT COUNT(1)
      INTO v_valid_column_count
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'c_ai_request_nonce'
       AND (
            (column_name = 'id_device' AND data_type = 'character varying'
             AND character_maximum_length = 32 AND is_nullable = 'NO')
         OR (column_name = 'nonce_hash' AND data_type = 'character varying'
             AND character_maximum_length = 64 AND is_nullable = 'NO')
         OR (column_name = 'expires_at' AND data_type = 'bigint' AND is_nullable = 'NO')
         OR (column_name = 'insert_time' AND data_type = 'timestamp without time zone'
             AND is_nullable = 'NO' AND column_default IS NOT NULL)
       );

    SELECT COUNT(1)
      INTO v_total_column_count
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'c_ai_request_nonce';

    IF v_valid_column_count <> 4 OR v_total_column_count <> 4 THEN
        RAISE EXCEPTION 'c_ai_request_nonce column contract drift detected';
    END IF;

    SELECT COUNT(1)
      INTO v_valid_pk_count
      FROM (
            SELECT tc.constraint_name
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_schema = tc.constraint_schema
               AND kcu.constraint_name = tc.constraint_name
               AND kcu.table_schema = tc.table_schema
               AND kcu.table_name = tc.table_name
             WHERE tc.table_schema = current_schema()
               AND tc.table_name = 'c_ai_request_nonce'
               AND tc.constraint_type = 'PRIMARY KEY'
             GROUP BY tc.constraint_name
            HAVING COUNT(1) = 2
               AND SUM(CASE WHEN kcu.ordinal_position = 1 AND kcu.column_name = 'id_device' THEN 1 ELSE 0 END) = 1
               AND SUM(CASE WHEN kcu.ordinal_position = 2 AND kcu.column_name = 'nonce_hash' THEN 1 ELSE 0 END) = 1
           ) valid_pk;

    IF v_valid_pk_count <> 1 THEN
        RAISE EXCEPTION 'c_ai_request_nonce primary-key contract drift detected';
    END IF;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_c_ai_request_nonce_exp ON c_ai_request_nonce (expires_at);

DO $$
DECLARE
    v_valid_index_count INTEGER;
BEGIN
    SELECT COUNT(1)
      INTO v_valid_index_count
      FROM (
            SELECT index_class.oid
              FROM pg_class index_class
              JOIN pg_namespace index_namespace ON index_namespace.oid = index_class.relnamespace
              JOIN pg_index index_meta ON index_meta.indexrelid = index_class.oid
              JOIN pg_class table_class ON table_class.oid = index_meta.indrelid
              JOIN pg_attribute column_meta
                ON column_meta.attrelid = table_class.oid
               AND column_meta.attnum = index_meta.indkey[0]
             WHERE index_namespace.nspname = current_schema()
               AND index_class.relname = 'idx_c_ai_request_nonce_exp'
               AND table_class.relname = 'c_ai_request_nonce'
               AND index_meta.indnatts = 1
               AND index_meta.indisvalid
               AND index_meta.indisready
               AND NOT index_meta.indisunique
               AND index_meta.indpred IS NULL
               AND index_meta.indexprs IS NULL
               AND column_meta.attname = 'expires_at'
           ) valid_index;

    IF v_valid_index_count <> 1 THEN
        RAISE EXCEPTION 'idx_c_ai_request_nonce_exp index contract drift detected';
    END IF;
END;
$$;

COMMENT ON TABLE c_ai_request_nonce IS '多节点请求签名nonce共享防重放表';
COMMENT ON COLUMN c_ai_request_nonce.id_device IS '设备ID，与nonce哈希共同唯一';
COMMENT ON COLUMN c_ai_request_nonce.nonce_hash IS '请求nonce的SHA-256十六进制哈希';
COMMENT ON COLUMN c_ai_request_nonce.expires_at IS 'nonce安全窗口过期时间，epoch毫秒';
COMMENT ON COLUMN c_ai_request_nonce.insert_time IS '登记时间';

COMMIT;
