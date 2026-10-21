-- Additive workspace control. READY is storage readiness, never an executable environment.
CREATE TABLE pf.workspace_capacity (
    id integer PRIMARY KEY CHECK(id=1),
    capacity_bytes bigint NOT NULL CHECK(capacity_bytes=4294967296),
    reserved_bytes bigint NOT NULL DEFAULT 0 CHECK(reserved_bytes BETWEEN 0 AND 2147483648),
    reserved_slots integer NOT NULL DEFAULT 0 CHECK(reserved_slots BETWEEN 0 AND 1),
    next_project_id integer NOT NULL DEFAULT 200001 CHECK(next_project_id>=200001),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0)
);
INSERT INTO pf.workspace_capacity(id,capacity_bytes) VALUES(1,4294967296);

CREATE TABLE pf.workspace (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES pf.space(id),
    actor_id uuid NOT NULL REFERENCES pf.app_user(id),
    name varchar(160) NOT NULL CHECK(length(name) BETWEEN 1 AND 80),
    state text NOT NULL CHECK(state IN ('PROVISIONING','READY','ERROR','BLOCKED')),
    environment_state text NOT NULL DEFAULT 'NOT_CREATED' CHECK(environment_state='NOT_CREATED'),
    quota_bytes bigint NOT NULL CHECK(quota_bytes=2147483648),
    quota_inodes bigint NOT NULL CHECK(quota_inodes=100000),
    project_id integer NOT NULL UNIQUE CHECK(project_id>=200001),
    version bigint NOT NULL DEFAULT 1 CHECK(version>0),
    operation_id uuid NOT NULL UNIQUE,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(space_id,id)
);
CREATE INDEX workspace_space_paging ON pf.workspace(space_id,id DESC);

CREATE TABLE pf.workspace_operation (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    actor_id uuid NOT NULL REFERENCES pf.app_user(id),
    command_id uuid NOT NULL UNIQUE,
    generation bigint NOT NULL DEFAULT 1 CHECK(generation>0),
    fence bigint NOT NULL DEFAULT 1 CHECK(fence>0),
    status text NOT NULL CHECK(status IN ('PENDING','CLAIMED','RUNNING','COMMITTED','FAILED','UNKNOWN')),
    effect_state text NOT NULL DEFAULT 'NOT_STARTED' CHECK(effect_state IN ('NOT_STARTED','MAY_HAVE_STARTED','ALLOCATED')),
    owner_id uuid,
    lease_until timestamptz,
    deadline_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK(version>0),
    error_code text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id),
    UNIQUE(space_id,workspace_id,id),
    UNIQUE(space_id,workspace_id,id,command_id,generation,fence)
);
ALTER TABLE pf.workspace ADD CONSTRAINT workspace_current_operation
    FOREIGN KEY(space_id,id,operation_id) REFERENCES pf.workspace_operation(space_id,workspace_id,id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE pf.workspace_reservation (
    workspace_id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    bytes bigint NOT NULL CHECK(bytes=2147483648),
    inodes bigint NOT NULL CHECK(inodes=100000),
    status text NOT NULL CHECK(status IN ('RESERVED','ALLOCATED','RECONCILING')),
    FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id)
);
CREATE TABLE pf.workspace_idempotency (
    space_id uuid NOT NULL, actor_id uuid NOT NULL, request_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    workspace_id uuid NOT NULL, operation_id uuid NOT NULL,
    PRIMARY KEY(space_id,actor_id,request_id),
    FOREIGN KEY(space_id,workspace_id,operation_id) REFERENCES pf.workspace_operation(space_id,workspace_id,id)
);
CREATE TABLE pf.workspace_outbox (
    id uuid PRIMARY KEY, space_id uuid NOT NULL, workspace_id uuid NOT NULL,
    operation_id uuid NOT NULL, generation bigint NOT NULL CHECK(generation>0), fence bigint NOT NULL CHECK(fence>0),
    status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PUBLISHING','PUBLISHED')),
    relay_owner uuid, relay_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,operation_id) REFERENCES pf.workspace_operation(space_id,workspace_id,id),
    UNIQUE(operation_id,generation)
);
CREATE INDEX workspace_outbox_pending ON pf.workspace_outbox(status,created_at);
CREATE TABLE pf.workspace_inbox (
    event_id uuid PRIMARY KEY REFERENCES pf.workspace_outbox(id),
    operation_id uuid NOT NULL REFERENCES pf.workspace_operation(id),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pf.runner_command (
    command_id uuid PRIMARY KEY,
    space_id uuid NOT NULL, workspace_id uuid NOT NULL, operation_id uuid NOT NULL,
    generation bigint NOT NULL CHECK(generation>0), fence bigint NOT NULL CHECK(fence>0),
    request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    state text NOT NULL CHECK(state IN ('ACCEPTED','RUNNING','COMMITTED','FAILED','UNKNOWN')),
    owner_id uuid NOT NULL, result_revision bigint NOT NULL DEFAULT 0 CHECK(result_revision>=0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,operation_id,command_id,generation,fence)
        REFERENCES pf.workspace_operation(space_id,workspace_id,id,command_id,generation,fence)
);
CREATE TABLE pf.runner_receipt (
    command_id uuid NOT NULL REFERENCES pf.runner_command(command_id),
    revision bigint NOT NULL CHECK(revision>0),
    state text NOT NULL CHECK(state IN ('COMMITTED','FAILED','UNKNOWN')),
    result_json jsonb NOT NULL CHECK(jsonb_typeof(result_json)='object'),
    result_text text NOT NULL,
    content_hash varchar(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(command_id,revision),
    CHECK(result_json=CAST(result_text AS jsonb))
);
CREATE TABLE pf.workspace_event (
    id uuid PRIMARY KEY, space_id uuid NOT NULL, workspace_id uuid NOT NULL,
    workspace_version bigint NOT NULL CHECK(workspace_version>0),
    event_type text NOT NULL CHECK(event_type='workspace.state_changed'),
    payload_json jsonb NOT NULL CHECK(jsonb_typeof(payload_json)='object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id),
    UNIQUE(workspace_id,workspace_version)
);
CREATE TABLE pf.workspace_quarantine (
    id uuid PRIMARY KEY, reason varchar(64) NOT NULL, payload_sha256 varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Follow the existing scoped authorization-function pattern. The caller's short
-- transaction retains locks until commit; runner never receives identity secrets.
CREATE FUNCTION pf.authorize_workspace_actor(actor uuid, selected_space uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
BEGIN
    PERFORM u.id FROM pf.app_user u
        JOIN pf.membership m ON m.user_id=u.id
        JOIN pf.space s ON s.id=m.space_id
        JOIN pf.space_storage st ON st.space_id=s.id
    WHERE u.id=actor AND s.id=selected_space AND u.status='ACTIVE'
        AND m.status='ACTIVE' AND st.status='ACTIVE' AND m.role IN('OWNER','ADMIN','MEMBER')
        AND(s.type<>'PERSONAL' OR s.owner_user_id=u.id)
    FOR SHARE OF u,m,s,st;
    RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION pf.authorize_workspace_actor(uuid,uuid) FROM PUBLIC;
