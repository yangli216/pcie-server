-- Add version-scoped outpatient field rules to an existing snapshot table.
WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM user_tab_columns
     WHERE table_name = 'C_AI_OUTPATIENT_EMR_TPL_SNAPSHOT' AND column_name = 'TEMPLATE_STATE';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE c_ai_outpatient_emr_tpl_snapshot ADD template_state VARCHAR2(12) DEFAULT ''enabled'' NOT NULL';
    END IF;
    SELECT COUNT(*) INTO v_count FROM user_tab_columns
     WHERE table_name = 'C_AI_OUTPATIENT_EMR_TPL_SNAPSHOT' AND column_name = 'POLICY_REVISION';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE c_ai_outpatient_emr_tpl_snapshot ADD policy_revision NUMBER(16) DEFAULT 0 NOT NULL';
    END IF;
    SELECT COUNT(*) INTO v_count FROM user_tab_columns
     WHERE table_name = 'C_AI_OUTPATIENT_EMR_TPL_SNAPSHOT' AND column_name = 'FIELD_RULES_JSON';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE c_ai_outpatient_emr_tpl_snapshot ADD field_rules_json CLOB';
    END IF;
    SELECT COUNT(*) INTO v_count FROM user_tab_columns
     WHERE table_name = 'C_AI_OUTPATIENT_EMR_TPL_SNAPSHOT' AND column_name = 'AI_FIELD_COUNT';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE c_ai_outpatient_emr_tpl_snapshot ADD ai_field_count NUMBER(10)';
    END IF;
    SELECT COUNT(*) INTO v_count FROM user_tab_columns
     WHERE table_name = 'C_AI_OUTPATIENT_EMR_TPL_SNAPSHOT' AND column_name = 'CUSTOM_PROMPT_COUNT';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE c_ai_outpatient_emr_tpl_snapshot ADD custom_prompt_count NUMBER(10)';
    END IF;
END;
/
