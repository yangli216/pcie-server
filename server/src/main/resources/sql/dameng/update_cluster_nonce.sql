-- Dameng DM8 Oracle-compatible cluster nonce replay-protection schema.
-- Execute with the application schema before enabling floating-ball.cluster.enabled=true.

DECLARE
    v_table_count NUMBER;
BEGIN
    SELECT COUNT(1)
      INTO v_table_count
      FROM user_tables
     WHERE table_name = 'C_SECURITY_REQUEST_NONCE';

    IF v_table_count = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE c_security_request_nonce (
                id_device            VARCHAR2(32) NOT NULL,
                nonce_value          VARCHAR2(64) NOT NULL,
                expires_at           TIMESTAMP NOT NULL,
                insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                CONSTRAINT pk_c_security_req_nonce PRIMARY KEY (id_device, nonce_value)
            )';
    END IF;
END;
/

-- A pre-existing table may have been created without the required composite key.
-- This block is intentionally idempotent: an exact PK/UK is accepted; otherwise
-- duplicate data is rejected before the missing constraint is added.
DECLARE
    v_exact_constraint_count NUMBER;
    v_duplicate_group_count NUMBER;
    v_constraint_name_count NUMBER;
BEGIN
    SELECT COUNT(1)
      INTO v_exact_constraint_count
      FROM (
            SELECT c.constraint_name
              FROM user_constraints c
              JOIN user_cons_columns cc
                ON cc.constraint_name = c.constraint_name
               AND cc.table_name = c.table_name
             WHERE c.table_name = 'C_SECURITY_REQUEST_NONCE'
               AND c.constraint_type IN ('P', 'U')
             GROUP BY c.constraint_name
            HAVING COUNT(1) = 2
               AND SUM(CASE
                           WHEN cc.position = 1 AND cc.column_name = 'ID_DEVICE' THEN 1
                           ELSE 0
                       END) = 1
               AND SUM(CASE
                           WHEN cc.position = 2 AND cc.column_name = 'NONCE_VALUE' THEN 1
                           ELSE 0
                       END) = 1
           );

    IF v_exact_constraint_count = 0 THEN
        SELECT COUNT(1)
          INTO v_duplicate_group_count
          FROM (
                SELECT id_device, nonce_value
                  FROM c_security_request_nonce
                 GROUP BY id_device, nonce_value
                HAVING COUNT(1) > 1
               );

        IF v_duplicate_group_count > 0 THEN
            RAISE_APPLICATION_ERROR(
                -20001,
                'C_SECURITY_REQUEST_NONCE contains duplicate (ID_DEVICE, NONCE_VALUE) rows'
            );
        END IF;

        SELECT COUNT(1)
          INTO v_constraint_name_count
          FROM user_constraints
         WHERE constraint_name = 'UK_C_SECURITY_REQ_NONCE';

        IF v_constraint_name_count > 0 THEN
            RAISE_APPLICATION_ERROR(
                -20002,
                'UK_C_SECURITY_REQ_NONCE already exists but does not exactly cover '
                    || '(ID_DEVICE, NONCE_VALUE)'
            );
        END IF;

        EXECUTE IMMEDIATE '
            ALTER TABLE c_security_request_nonce
            ADD CONSTRAINT uk_c_security_req_nonce UNIQUE (id_device, nonce_value)';
    END IF;
END;
/

DECLARE
    v_index_count NUMBER;
BEGIN
    SELECT COUNT(1)
      INTO v_index_count
      FROM user_indexes
     WHERE index_name = 'IDX_C_SECURITY_NONCE_EXP';

    IF v_index_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_c_security_nonce_exp ON c_security_request_nonce (expires_at)';
    END IF;
END;
/

COMMENT ON TABLE c_security_request_nonce IS '集群请求签名nonce防重放表';
COMMENT ON COLUMN c_security_request_nonce.id_device IS '设备ID，与nonce共同唯一';
COMMENT ON COLUMN c_security_request_nonce.nonce_value IS '已验签请求的随机数';
COMMENT ON COLUMN c_security_request_nonce.expires_at IS 'nonce安全窗口过期时间';
COMMENT ON COLUMN c_security_request_nonce.insert_time IS '登记时间';

COMMIT;
