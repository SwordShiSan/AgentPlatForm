-- R6.2 capabilities are owned by a real space member; historical task data is untouched.
ALTER TABLE pf.asset_object DROP CONSTRAINT asset_object_category_check;
ALTER TABLE pf.asset_object ADD CONSTRAINT asset_object_category_check
 CHECK (category IN ('file-version','preview','chat-attachment','text-extraction','skill-package'));
ALTER TABLE pf.asset_object ADD CONSTRAINT asset_object_skill_identity_unique
 UNIQUE (space_id,version_id,id,category,sha256,size_bytes);

CREATE TABLE pf.capability (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 kind text NOT NULL CHECK (kind IN ('SKILL','MCP')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (space_id,id),
 UNIQUE (space_id,owner_user_id,id),
 FOREIGN KEY (space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id)
);

-- A version is an immutable identity; mutable publication state lives separately.
CREATE TABLE pf.capability_version (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 capability_id uuid NOT NULL,
 version_number bigint NOT NULL CHECK (version_number > 0),
 content_hash varchar(64) NOT NULL CHECK (content_hash ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (space_id,owner_user_id,capability_id,id),
 UNIQUE (space_id,capability_id,version_number),
 FOREIGN KEY (space_id,owner_user_id,capability_id)
   REFERENCES pf.capability(space_id,owner_user_id,id)
);
CREATE TRIGGER capability_version_immutable BEFORE UPDATE OR DELETE ON pf.capability_version
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

-- The package is immutable and refers to the very same version identity in private object storage.
CREATE TABLE pf.skill_package_version (
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 capability_id uuid NOT NULL,
 version_id uuid NOT NULL,
 asset_id uuid NOT NULL,
 asset_category text NOT NULL DEFAULT 'skill-package' CHECK (asset_category = 'skill-package'),
 archive_bytes bigint NOT NULL CHECK (archive_bytes BETWEEN 0 AND 20971520),
 archive_sha256 varchar(64) NOT NULL CHECK (archive_sha256 ~ '^[a-f0-9]{64}$'),
 expanded_bytes bigint NOT NULL CHECK (expanded_bytes BETWEEN 0 AND 104857600),
 file_count integer NOT NULL CHECK (file_count BETWEEN 1 AND 1000),
 name text NOT NULL CHECK (octet_length(name) BETWEEN 1 AND 512),
 description text NOT NULL CHECK (octet_length(description) BETWEEN 1 AND 65536),
 manifest jsonb NOT NULL CHECK (jsonb_typeof(manifest) = 'array'),
 dependency_manifest jsonb NOT NULL CHECK (jsonb_typeof(dependency_manifest) = 'array'),
 PRIMARY KEY (space_id,owner_user_id,capability_id,version_id),
 UNIQUE (space_id,asset_id),
 FOREIGN KEY (space_id,owner_user_id,capability_id,version_id)
   REFERENCES pf.capability_version(space_id,owner_user_id,capability_id,id),
 FOREIGN KEY (space_id,version_id,asset_id,asset_category,archive_sha256,archive_bytes)
   REFERENCES pf.asset_object(space_id,version_id,id,category,sha256,size_bytes)
);
CREATE TRIGGER skill_package_version_immutable BEFORE UPDATE OR DELETE ON pf.skill_package_version
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

-- Connector secrets are separate from model credentials and belong to one member of one space.
CREATE TABLE pf.connector_credential (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 request_id uuid NOT NULL,
 request_fingerprint varchar(64) NOT NULL CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
 ciphertext bytea NOT NULL,
 nonce bytea NOT NULL CHECK (octet_length(nonce) = 12),
 key_version integer NOT NULL CHECK (key_version > 0),
 revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
 state text NOT NULL CHECK (state IN ('ACTIVE','REVOKED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (space_id,owner_user_id,id),
 UNIQUE (space_id,owner_user_id,request_id),
 FOREIGN KEY (space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id)
);
CREATE FUNCTION pf.guard_connector_credential_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.id IS DISTINCT FROM OLD.id OR NEW.space_id IS DISTINCT FROM OLD.space_id
    OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id OR NEW.request_id IS DISTINCT FROM OLD.request_id
    OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint OR NEW.ciphertext IS DISTINCT FROM OLD.ciphertext
    OR NEW.nonce IS DISTINCT FROM OLD.nonce OR NEW.key_version IS DISTINCT FROM OLD.key_version
    OR NEW.created_at IS DISTINCT FROM OLD.created_at OR OLD.state <> 'ACTIVE'
    OR NEW.state <> 'REVOKED' OR NEW.revision <> OLD.revision + 1 THEN
  RAISE EXCEPTION 'CONNECTOR_CREDENTIAL_IMMUTABLE' USING ERRCODE='55000';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER connector_credential_revoke_only BEFORE UPDATE ON pf.connector_credential
 FOR EACH ROW EXECUTE FUNCTION pf.guard_connector_credential_update();
CREATE TRIGGER connector_credential_no_delete BEFORE DELETE ON pf.connector_credential
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
REVOKE ALL ON FUNCTION pf.guard_connector_credential_update() FROM PUBLIC;
CREATE TABLE pf.connector_credential_revocation (
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 request_id uuid NOT NULL,
 credential_id uuid NOT NULL,
 expected_revision bigint NOT NULL CHECK (expected_revision > 0),
 result_revision bigint NOT NULL CHECK (result_revision = expected_revision + 1),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (space_id,owner_user_id,request_id),
 FOREIGN KEY (space_id,owner_user_id,credential_id) REFERENCES pf.connector_credential(space_id,owner_user_id,id)
);
CREATE TRIGGER connector_credential_revocation_immutable BEFORE UPDATE OR DELETE ON pf.connector_credential_revocation
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

CREATE TABLE pf.mcp_connector_version (
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 capability_id uuid NOT NULL,
 version_id uuid NOT NULL,
 endpoint text NOT NULL CHECK (octet_length(endpoint) BETWEEN 9 AND 4096),
 auth_kind text NOT NULL CHECK (auth_kind IN ('NONE','BEARER','API_KEY')),
 api_key_header text CHECK (api_key_header IN ('X-API-Key','api-key')),
 credential_id uuid,
 credential_revision bigint,
 protocol text NOT NULL CHECK (protocol = 'STREAMABLE_HTTP'),
 policy_hash varchar(64) NOT NULL CHECK (policy_hash ~ '^[a-f0-9]{64}$'),
 tool_count integer NOT NULL CHECK (tool_count BETWEEN 0 AND 100),
 schemas_bytes bigint NOT NULL CHECK (schemas_bytes BETWEEN 0 AND 1048576),
 PRIMARY KEY (space_id,owner_user_id,capability_id,version_id),
 FOREIGN KEY (space_id,owner_user_id,capability_id,version_id)
   REFERENCES pf.capability_version(space_id,owner_user_id,capability_id,id),
 FOREIGN KEY (space_id,owner_user_id,credential_id)
   REFERENCES pf.connector_credential(space_id,owner_user_id,id),
 CHECK ((auth_kind='NONE' AND credential_id IS NULL AND credential_revision IS NULL AND api_key_header IS NULL)
     OR (auth_kind='BEARER' AND credential_id IS NOT NULL AND credential_revision IS NOT NULL AND api_key_header IS NULL)
     OR (auth_kind='API_KEY' AND credential_id IS NOT NULL AND credential_revision IS NOT NULL AND api_key_header IS NOT NULL))
);
CREATE TRIGGER mcp_connector_version_immutable BEFORE UPDATE OR DELETE ON pf.mcp_connector_version
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

CREATE TABLE pf.mcp_connector_tool (
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 capability_id uuid NOT NULL,
 version_id uuid NOT NULL,
 tool_name text NOT NULL CHECK (octet_length(tool_name) BETWEEN 1 AND 512),
 input_schema jsonb NOT NULL CHECK (jsonb_typeof(input_schema) = 'object' AND octet_length(input_schema::text) <= 65536),
 schema_hash varchar(64) NOT NULL CHECK (schema_hash ~ '^[a-f0-9]{64}$'),
 PRIMARY KEY (space_id,owner_user_id,capability_id,version_id,tool_name),
 FOREIGN KEY (space_id,owner_user_id,capability_id,version_id)
   REFERENCES pf.mcp_connector_version(space_id,owner_user_id,capability_id,version_id)
);
CREATE TRIGGER mcp_connector_tool_immutable BEFORE UPDATE OR DELETE ON pf.mcp_connector_tool
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

CREATE TABLE pf.skill_import (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 request_id uuid NOT NULL,
 request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
 state text NOT NULL CHECK (state IN ('CREATED','UPLOADING','VERIFIED','QUEUED','RUNNING','DRAFT','REJECTED','BLOCKED')),
 revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
 generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
 declared_bytes bigint NOT NULL CHECK (declared_bytes BETWEEN 0 AND 20971520),
 declared_sha256 varchar(64) NOT NULL CHECK (declared_sha256 ~ '^[a-f0-9]{64}$'),
 staging_object_key text NOT NULL UNIQUE,
 target_capability_id uuid NOT NULL,
 target_version_id uuid NOT NULL UNIQUE,
 target_asset_id uuid NOT NULL UNIQUE,
 transfer_owner uuid,
 transfer_until timestamptz,
 archive_sha256 varchar(64) CHECK (archive_sha256 ~ '^[a-f0-9]{64}$'),
 capability_id uuid,
 version_id uuid,
 owner_id uuid,
 fence bigint CHECK (fence > 0),
 lease_until timestamptz,
 error_code varchar(64),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (space_id,id),
 UNIQUE (space_id,owner_user_id,request_id),
 UNIQUE (space_id,owner_user_id,id),
 FOREIGN KEY (space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY (space_id,owner_user_id,capability_id,version_id)
   REFERENCES pf.capability_version(space_id,owner_user_id,capability_id,id),
 CHECK ((capability_id IS NULL) = (version_id IS NULL)),
 CHECK ((state='UPLOADING')=(transfer_owner IS NOT NULL AND transfer_until IS NOT NULL)),
 CHECK (staging_object_key LIKE 'spaces/'||space_id::text||'/skill-package/'||target_asset_id::text||'/'||target_version_id::text||'/%'),
 CHECK (state <> 'RUNNING' OR (owner_id IS NOT NULL AND fence IS NOT NULL AND lease_until IS NOT NULL)),
 CHECK (state <> 'DRAFT' OR (capability_id IS NOT NULL AND version_id IS NOT NULL AND archive_sha256 IS NOT NULL
   AND capability_id=target_capability_id AND version_id=target_version_id AND archive_sha256=declared_sha256))
);
CREATE INDEX skill_import_work ON pf.skill_import(state,created_at);
CREATE TABLE pf.skill_import_completion_request (
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 request_id uuid NOT NULL,
 import_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (space_id,owner_user_id,request_id),
 FOREIGN KEY (space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY (space_id,owner_user_id,import_id) REFERENCES pf.skill_import(space_id,owner_user_id,id)
);
CREATE TRIGGER skill_import_completion_request_immutable BEFORE UPDATE OR DELETE ON pf.skill_import_completion_request
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.skill_import_reservation (
 import_id uuid PRIMARY KEY,
 space_id uuid NOT NULL REFERENCES pf.space_storage(space_id),
 bytes bigint NOT NULL CHECK (bytes BETWEEN 0 AND 20971520),
 state text NOT NULL CHECK (state IN ('RESERVED','SETTLED','RELEASED','RECONCILING')),
 revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
 FOREIGN KEY (space_id,import_id) REFERENCES pf.skill_import(space_id,id)
);
CREATE TABLE pf.skill_import_outbox (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 import_id uuid NOT NULL,
 generation bigint NOT NULL CHECK (generation > 0),
 state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','PUBLISHING','PUBLISHED')),
 relay_owner uuid,
 relay_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (import_id,generation),
 UNIQUE (space_id,id,import_id),
 FOREIGN KEY (space_id,import_id) REFERENCES pf.skill_import(space_id,id)
);
CREATE TABLE pf.skill_import_inbox (
 event_id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 import_id uuid NOT NULL,
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY (space_id,event_id,import_id) REFERENCES pf.skill_import_outbox(space_id,id,import_id)
);
CREATE TABLE pf.skill_import_quarantine (
 id uuid PRIMARY KEY,
 body_hash varchar(64) NOT NULL CHECK (body_hash ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE pf.capability_version_status (
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 capability_id uuid NOT NULL,
 version_id uuid NOT NULL,
 state text NOT NULL CHECK (state IN ('DRAFT','PUBLISHED','REVOKED')),
 revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (space_id,owner_user_id,capability_id,version_id),
 FOREIGN KEY (space_id,owner_user_id,capability_id,version_id)
   REFERENCES pf.capability_version(space_id,owner_user_id,capability_id,id)
);
CREATE FUNCTION pf.guard_capability_version_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.space_id IS DISTINCT FROM OLD.space_id OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
    OR NEW.capability_id IS DISTINCT FROM OLD.capability_id OR NEW.version_id IS DISTINCT FROM OLD.version_id
    OR NEW.revision <> OLD.revision + 1 OR NOT (
      (OLD.state='DRAFT' AND NEW.state IN ('PUBLISHED','REVOKED'))
      OR (OLD.state='PUBLISHED' AND NEW.state='REVOKED')) THEN
  RAISE EXCEPTION 'CAPABILITY_VERSION_TRANSITION_REJECTED' USING ERRCODE='55000';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER capability_version_transition BEFORE UPDATE ON pf.capability_version_status
 FOR EACH ROW EXECUTE FUNCTION pf.guard_capability_version_transition();
CREATE TRIGGER capability_version_status_no_delete BEFORE DELETE ON pf.capability_version_status
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
REVOKE ALL ON FUNCTION pf.guard_capability_version_transition() FROM PUBLIC;
CREATE TABLE pf.capability_version_command (
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 request_id uuid NOT NULL,
 capability_id uuid NOT NULL,
 version_id uuid NOT NULL,
 action text NOT NULL CHECK (action IN ('PUBLISH','REVOKE')),
 expected_revision bigint NOT NULL CHECK (expected_revision > 0),
 result_revision bigint NOT NULL CHECK (result_revision > expected_revision),
 result_state text NOT NULL CHECK (result_state IN ('PUBLISHED','REVOKED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (space_id,owner_user_id,request_id),
 FOREIGN KEY (space_id,owner_user_id,capability_id,version_id)
   REFERENCES pf.capability_version(space_id,owner_user_id,capability_id,id)
);
CREATE TRIGGER capability_version_command_immutable BEFORE UPDATE OR DELETE ON pf.capability_version_command
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

-- No workspace row means inheritance; a row with no selections is an explicit empty override.
-- Cleared overrides retain their revision to prevent stale ETags becoming valid again.
CREATE TABLE pf.capability_binding_set (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 workspace_id uuid,
 inherit_personal boolean NOT NULL DEFAULT false,
 revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
 UNIQUE (space_id,owner_user_id,id),
 FOREIGN KEY (space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY (space_id,workspace_id) REFERENCES pf.workspace(space_id,id),
 CHECK (workspace_id IS NOT NULL OR NOT inherit_personal)
);
CREATE UNIQUE INDEX capability_binding_personal_unique
 ON pf.capability_binding_set(space_id,owner_user_id) WHERE workspace_id IS NULL;
CREATE UNIQUE INDEX capability_binding_workspace_unique
 ON pf.capability_binding_set(space_id,owner_user_id,workspace_id) WHERE workspace_id IS NOT NULL;

CREATE TABLE pf.capability_binding_selection (
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 binding_set_id uuid NOT NULL,
 capability_id uuid NOT NULL,
 version_id uuid NOT NULL,
 PRIMARY KEY (binding_set_id,capability_id),
 FOREIGN KEY (space_id,owner_user_id,binding_set_id)
   REFERENCES pf.capability_binding_set(space_id,owner_user_id,id),
 FOREIGN KEY (space_id,owner_user_id,capability_id,version_id)
   REFERENCES pf.capability_version(space_id,owner_user_id,capability_id,id)
);

-- A retry resolves the already accepted result without reapplying a change or relying on the current revision.
CREATE TABLE pf.capability_binding_request (
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 request_id uuid NOT NULL,
 request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
 binding_set_id uuid NOT NULL,
 result_revision bigint NOT NULL CHECK (result_revision > 0),
 inherit_personal boolean NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (space_id,owner_user_id,request_id),
 FOREIGN KEY (space_id,owner_user_id,binding_set_id)
   REFERENCES pf.capability_binding_set(space_id,owner_user_id,id)
);
CREATE TABLE pf.capability_binding_request_selection (
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 request_id uuid NOT NULL,
 capability_id uuid NOT NULL,
 version_id uuid NOT NULL,
 PRIMARY KEY (space_id,owner_user_id,request_id,capability_id),
 FOREIGN KEY (space_id,owner_user_id,request_id)
   REFERENCES pf.capability_binding_request(space_id,owner_user_id,request_id),
 FOREIGN KEY (space_id,owner_user_id,capability_id,version_id)
   REFERENCES pf.capability_version(space_id,owner_user_id,capability_id,id)
);
CREATE TRIGGER capability_binding_request_immutable BEFORE UPDATE OR DELETE ON pf.capability_binding_request
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TRIGGER capability_binding_request_selection_immutable BEFORE UPDATE OR DELETE ON pf.capability_binding_request_selection
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

CREATE TABLE pf.capability_audit (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 action text NOT NULL CHECK (action IN ('BINDINGS_REPLACED','SKILL_IMPORTED','SKILL_PUBLISHED','SKILL_REVOKED',
   'CONNECTOR_CREATED','CONNECTOR_TESTED','CONNECTOR_ENABLED','CONNECTOR_DISABLED','CREDENTIAL_CHANGED')),
 resource_id uuid NOT NULL,
 trace_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY (space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id)
);
CREATE TRIGGER capability_audit_immutable BEFORE UPDATE OR DELETE ON pf.capability_audit
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

REVOKE ALL ON pf.capability, pf.capability_version, pf.skill_package_version, pf.skill_import,
 pf.skill_import_completion_request, pf.skill_import_reservation, pf.skill_import_outbox, pf.skill_import_inbox, pf.skill_import_quarantine, pf.capability_version_status,
 pf.connector_credential,pf.connector_credential_revocation,pf.mcp_connector_version,pf.mcp_connector_tool,pf.capability_version_command,
 pf.capability_binding_set, pf.capability_binding_selection, pf.capability_binding_request,
 pf.capability_binding_request_selection, pf.capability_audit FROM PUBLIC;
