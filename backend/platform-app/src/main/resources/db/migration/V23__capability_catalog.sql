-- R6.2 capabilities are owned by a real space member; historical task data is untouched.
ALTER TABLE pf.asset_object DROP CONSTRAINT asset_object_category_check;
ALTER TABLE pf.asset_object ADD CONSTRAINT asset_object_category_check
 CHECK (category IN ('file-version','preview','chat-attachment','text-extraction','skill-package'));

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

REVOKE ALL ON pf.capability, pf.capability_version, pf.capability_version_status,
 pf.capability_binding_set, pf.capability_binding_selection FROM PUBLIC;
