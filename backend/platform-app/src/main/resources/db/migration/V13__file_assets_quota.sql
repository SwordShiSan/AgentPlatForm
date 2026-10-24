-- Additive file assets. No file body, endpoint or credential is stored in these facts.
CREATE TABLE pf.file_entry (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    relative_path text NOT NULL CHECK(octet_length(relative_path) BETWEEN 1 AND 4096),
    parent_path text NOT NULL,
    name text NOT NULL CHECK(octet_length(name) BETWEEN 1 AND 255),
    kind text NOT NULL CHECK(kind IN('FILE','DIRECTORY')),
    current_version_id uuid,
    collection_state text NOT NULL DEFAULT 'PENDING' CHECK(collection_state IN('PENDING','CONFIRMED','CHANGED','BLOCKED')),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id),
    UNIQUE(space_id,workspace_id,id),
    UNIQUE(space_id,workspace_id,relative_path),
    CHECK(kind='FILE' OR current_version_id IS NULL),
    CHECK(collection_state<>'CONFIRMED' OR kind='DIRECTORY' OR current_version_id IS NOT NULL)
);
CREATE INDEX file_entry_paging ON pf.file_entry(space_id,workspace_id,parent_path,relative_path,id);

CREATE TABLE pf.asset_object (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES pf.space(id),
    category text NOT NULL CHECK(category='file-version'),
    version_id uuid NOT NULL,
    object_key text NOT NULL UNIQUE,
    sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 0 AND 104857600),
    mime text NOT NULL DEFAULT 'application/octet-stream',
    status text NOT NULL CHECK(status IN('STAGING','AVAILABLE','QUARANTINED','TOMBSTONED','PURGED')),
    created_by uuid NOT NULL REFERENCES pf.app_user(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    UNIQUE(space_id,id),
    UNIQUE(space_id,version_id,id),
    CHECK(object_key LIKE 'spaces/'||space_id::text||'/file-version/'||id::text||'/'||version_id::text||'/%')
);

CREATE TABLE pf.upload_session (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    actor_id uuid NOT NULL REFERENCES pf.app_user(id),
    request_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    relative_path text NOT NULL CHECK(octet_length(relative_path) BETWEEN 1 AND 4096),
    mode text NOT NULL CHECK(mode IN('CREATE','REPLACE')),
    file_id uuid NOT NULL,
    base_version_id uuid,
    expected_revision bigint CHECK(expected_revision>0),
    version_id uuid NOT NULL UNIQUE,
    asset_id uuid NOT NULL,
    size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 0 AND 104857600),
    sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    status text NOT NULL CHECK(status IN('CREATED','UPLOADING','VERIFYING','VERIFIED','QUEUED','COMMITTED','ABORTED','EXPIRED','UNKNOWN')),
    transfer_owner uuid,
    transfer_until timestamptz,
    operation_id uuid UNIQUE,
    expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '24 hours',
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id),
    FOREIGN KEY(space_id,version_id,asset_id) REFERENCES pf.asset_object(space_id,version_id,id),
    UNIQUE(space_id,actor_id,request_id),
    UNIQUE(space_id,workspace_id,id),
    CHECK((mode='CREATE' AND base_version_id IS NULL AND expected_revision IS NULL)
        OR(mode='REPLACE' AND base_version_id IS NOT NULL AND expected_revision IS NOT NULL)),
    CHECK((status='UPLOADING')=(transfer_owner IS NOT NULL AND transfer_until IS NOT NULL))
);
CREATE INDEX upload_expiry ON pf.upload_session(status,expires_at);

CREATE TABLE pf.object_reservation (
    upload_id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    bytes bigint NOT NULL CHECK(bytes BETWEEN 0 AND 104857600),
    status text NOT NULL CHECK(status IN('RESERVED','COMMITTED','RELEASED','RECONCILING')),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    FOREIGN KEY(space_id,workspace_id,upload_id) REFERENCES pf.upload_session(space_id,workspace_id,id)
);

CREATE TABLE pf.file_operation (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    actor_id uuid NOT NULL REFERENCES pf.app_user(id),
    kind text NOT NULL CHECK(kind IN('APPLY_UPLOAD','SCAN')),
    upload_id uuid UNIQUE,
    command_id uuid NOT NULL UNIQUE,
    owner_id uuid,
    generation bigint NOT NULL DEFAULT 1 CHECK(generation>0),
    fence bigint CHECK(fence>0),
    lease_until timestamptz,
    deadline_at timestamptz,
    status text NOT NULL CHECK(status IN('PENDING','CLAIMED','RUNNING','COMMITTED','FAILED','UNKNOWN')),
    effect_state text NOT NULL DEFAULT 'NOT_STARTED' CHECK(effect_state IN('NOT_STARTED','MAY_HAVE_STARTED','CONFIRMED')),
    error_code varchar(64),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id),
    FOREIGN KEY(space_id,workspace_id,upload_id) REFERENCES pf.upload_session(space_id,workspace_id,id),
    UNIQUE(space_id,workspace_id,id),
    UNIQUE(space_id,workspace_id,id,command_id,generation),
    CHECK((kind='APPLY_UPLOAD')=(upload_id IS NOT NULL)),
    CHECK(status NOT IN('CLAIMED','RUNNING') OR(owner_id IS NOT NULL AND fence IS NOT NULL AND lease_until IS NOT NULL AND deadline_at IS NOT NULL))
);
ALTER TABLE pf.upload_session ADD CONSTRAINT upload_operation_fk
    FOREIGN KEY(space_id,workspace_id,operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id);

CREATE TABLE pf.file_version (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    file_id uuid NOT NULL,
    parent_version_id uuid,
    asset_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 0 AND 104857600),
    actor_id uuid NOT NULL REFERENCES pf.app_user(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,file_id) REFERENCES pf.file_entry(space_id,workspace_id,id),
    FOREIGN KEY(space_id,id,asset_id) REFERENCES pf.asset_object(space_id,version_id,id),
    FOREIGN KEY(space_id,workspace_id,operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id),
    UNIQUE(space_id,workspace_id,file_id,id),
    UNIQUE(space_id,id),
    UNIQUE(file_id,operation_id),
    FOREIGN KEY(space_id,workspace_id,file_id,parent_version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id)
);
ALTER TABLE pf.file_entry ADD CONSTRAINT file_current_version_fk
    FOREIGN KEY(space_id,workspace_id,id,current_version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE pf.upload_session ADD CONSTRAINT upload_replace_version_fk
    FOREIGN KEY(space_id,workspace_id,file_id,base_version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id);

CREATE TABLE pf.asset_reference (
    space_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    file_version_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(space_id,asset_id,file_version_id),
    FOREIGN KEY(space_id,asset_id) REFERENCES pf.asset_object(space_id,id),
    FOREIGN KEY(space_id,file_version_id) REFERENCES pf.file_version(space_id,id)
);

-- A lease remains occupied until a positive stop receipt; time expiry alone is not release.
CREATE TABLE pf.workspace_file_lease (
    workspace_id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    operation_id uuid UNIQUE,
    owner_id uuid,
    fence bigint NOT NULL DEFAULT 0 CHECK(fence>=0),
    lease_until timestamptz,
    status text NOT NULL DEFAULT 'FREE' CHECK(status IN('FREE','ACTIVE','BLOCKED')),
    FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id),
    FOREIGN KEY(space_id,workspace_id,operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id),
    CHECK((status='FREE')=(operation_id IS NULL AND owner_id IS NULL AND lease_until IS NULL)),
    CHECK(status='FREE' OR(operation_id IS NOT NULL AND owner_id IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE TABLE pf.file_outbox (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    operation_id uuid NOT NULL UNIQUE,
    generation bigint NOT NULL CHECK(generation>0),
    status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','PUBLISHING','PUBLISHED')),
    relay_owner uuid,
    relay_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id)
);
CREATE INDEX file_outbox_pending ON pf.file_outbox(status,created_at);
CREATE TABLE pf.file_inbox (
    event_id uuid PRIMARY KEY REFERENCES pf.file_outbox(id),
    operation_id uuid NOT NULL REFERENCES pf.file_operation(id),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pf.file_runner_command (
    command_id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    generation bigint NOT NULL CHECK(generation>0),
    fence bigint NOT NULL CHECK(fence>0),
    owner_id uuid NOT NULL,
    runner_instance_id uuid NOT NULL REFERENCES pf.workspace_runner_instance(id),
    request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    image_id text NOT NULL CHECK(image_id ~ '^sha256:[0-9a-f]{64}$'),
    policy_hash varchar(64) NOT NULL CHECK(policy_hash ~ '^[0-9a-f]{64}$'),
    container_id varchar(64) UNIQUE CHECK(container_id ~ '^[0-9a-f]{64}$'),
    started_at text,
    exec_id varchar(64) UNIQUE CHECK(exec_id ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,operation_id,command_id,generation)
        REFERENCES pf.file_operation(space_id,workspace_id,id,command_id,generation),
    UNIQUE(space_id,workspace_id,command_id)
);
CREATE TABLE pf.file_runner_receipt (
    command_id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    result_state text NOT NULL CHECK(result_state IN('APPLIED','NOT_APPLIED','UNKNOWN','SCANNED')),
    result_text text NOT NULL CHECK(octet_length(result_text)<=32768),
    result_hash varchar(64) NOT NULL CHECK(result_hash ~ '^[0-9a-f]{64}$'),
    stop_text text NOT NULL CHECK(octet_length(stop_text)<=8192),
    stop_hash varchar(64) NOT NULL CHECK(stop_hash ~ '^[0-9a-f]{64}$'),
    observer_runner_id uuid NOT NULL REFERENCES pf.workspace_runner_instance(id),
    prior_runner_proof_id uuid REFERENCES pf.workspace_runner_stop_proof(runner_instance_id),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision=1),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,command_id) REFERENCES pf.file_runner_command(space_id,workspace_id,command_id)
);
CREATE TABLE pf.file_event (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    file_id uuid NOT NULL,
    file_revision bigint NOT NULL CHECK(file_revision>0),
    event_type text NOT NULL CHECK(event_type='file.version.created'),
    payload_json jsonb NOT NULL CHECK(jsonb_typeof(payload_json)='object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,file_id) REFERENCES pf.file_entry(space_id,workspace_id,id),
    UNIQUE(file_id,file_revision)
);
