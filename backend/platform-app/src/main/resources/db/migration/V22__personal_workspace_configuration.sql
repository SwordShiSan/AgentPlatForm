-- Extend existing preference authority; old preference values remain untouched.
ALTER TABLE pf.user_preference DROP CONSTRAINT user_preference_key_check;
ALTER TABLE pf.user_preference ADD CONSTRAINT user_preference_key_check
 CHECK (key IN ('defaultModelId', 'defaultCredentialSource', 'workbenchLayout', 'recentFiles',
                'defaultPlanEnabled', 'defaultApprovalMode'));

-- One revision protects all workspace overrides. An all-null row is retained so
-- clearing the last override cannot cause an ABA revision reset.
CREATE TABLE pf.workspace_configuration (
 space_id uuid NOT NULL,
 workspace_id uuid NOT NULL,
 version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 default_model_id uuid REFERENCES pf.model_definition(id),
 default_credential_source text CHECK (default_credential_source IN ('PLATFORM','USER')),
 default_plan_enabled boolean,
 default_approval_mode text CHECK (default_approval_mode IN ('RISK_BASED','FULL')),
 PRIMARY KEY (space_id, workspace_id),
 FOREIGN KEY (space_id, workspace_id) REFERENCES pf.workspace(space_id, id)
);

-- Existing requests continue to use their original digest algorithm. Only new
-- submission records will opt in to the presence-aware second version.
ALTER TABLE pf.idempotency_record ADD COLUMN body_hash_version smallint NOT NULL DEFAULT 1
 CHECK (body_hash_version IN (1,2));

-- Lock the model identity without granting the API role general model updates.
CREATE FUNCTION pf.lock_r61_model(selected_model uuid) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
DECLARE selected_version bigint;
BEGIN
 SELECT version INTO selected_version FROM pf.model_definition
 WHERE id=selected_model AND status='ACTIVE' AND model_name IN ('deepseek-flash','deepseek-v4-pro') FOR SHARE;
 RETURN selected_version;
END $$;
REVOKE ALL ON FUNCTION pf.lock_r61_model(uuid) FROM PUBLIC;
