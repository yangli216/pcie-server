-- Add version-scoped outpatient field rules to an existing snapshot table.
ALTER TABLE c_ai_outpatient_emr_tpl_snapshot ADD COLUMN IF NOT EXISTS template_state VARCHAR(12) DEFAULT 'enabled' NOT NULL;
ALTER TABLE c_ai_outpatient_emr_tpl_snapshot ADD COLUMN IF NOT EXISTS policy_revision NUMERIC(16) DEFAULT 0 NOT NULL;
ALTER TABLE c_ai_outpatient_emr_tpl_snapshot ADD COLUMN IF NOT EXISTS field_rules_json TEXT;
ALTER TABLE c_ai_outpatient_emr_tpl_snapshot ADD COLUMN IF NOT EXISTS ai_field_count NUMERIC(10);
ALTER TABLE c_ai_outpatient_emr_tpl_snapshot ADD COLUMN IF NOT EXISTS custom_prompt_count NUMERIC(10);
