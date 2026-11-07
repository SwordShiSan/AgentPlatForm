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

CREATE TABLE pf.skill_import (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 request_id uuid NOT NULL,
 request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
 state text NOT NULL CHECK (state IN ('CREATED','UPLOADING','QUEUED','RUNNING','DRAFT','REJECTED','BLOCKED')),
 revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
 generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
 declared_bytes bigint NOT NULL CHECK (declared_bytes BETWEEN 0 AND 20971520),
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
 FOREIGN KEY (space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY (space_id,owner_user_id,capability_id,version_id)
   REFERENCES pf.capability_version(space_id,owner_user_id,capability_id,id),
 CHECK ((capability_id IS NULL) = (version_id IS NULL)),
 CHECK (state <> 'RUNNING' OR (owner_id IS NOT NULL AND fence IS NOT NULL AND lease_until IS NOT NULL)),
 CHECK (state <> 'DRAFT' OR (capability_id IS NOT NULL AND archive_sha256 IS NOT NULL))
);
CREATE INDEX skill_import_work ON pf.skill_import(state,created_at);
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

REVOKE ALL ON pf.capability, pf.capability_version, pf.skill_package_version, pf.skill_import,
 pf.skill_import_reservation, pf.skill_import_outbox, pf.skill_import_inbox, pf.capability_version_status,
 pf.capability_binding_set, pf.capability_binding_selection FROM PUBLIC;
