WHENEVER SQLERROR EXIT SQL.SQLCODE

-- Preserve every snapshot row while changing version identity from
-- (id_org, template_hash) to (id_org, template_id, template_hash).
DECLARE
    v_index_count NUMBER := 0;
BEGIN
    SELECT COUNT(*)
      INTO v_index_count
      FROM user_indexes
     WHERE index_name = 'UK_C_AI_OUTEMR_TPL_SNAP';

    IF v_index_count > 0 THEN
        EXECUTE IMMEDIATE 'DROP INDEX uk_c_ai_outemr_tpl_snap';
    END IF;

    EXECUTE IMMEDIATE
        'CREATE UNIQUE INDEX uk_c_ai_outemr_tpl_snap '
        || 'ON c_ai_outpatient_emr_tpl_snapshot (id_org, template_id, template_hash)';
END;
/

COMMIT;
