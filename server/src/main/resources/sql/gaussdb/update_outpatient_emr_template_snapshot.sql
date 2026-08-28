\set ON_ERROR_STOP on

-- Preserve every snapshot row while changing version identity from
-- (id_org, template_hash) to (id_org, template_id, template_hash).
BEGIN;

DROP INDEX IF EXISTS uk_c_ai_outemr_tpl_snap;

CREATE UNIQUE INDEX uk_c_ai_outemr_tpl_snap
    ON c_ai_outpatient_emr_tpl_snapshot (id_org, template_id, template_hash);

COMMIT;
