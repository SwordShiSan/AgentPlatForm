-- Storage READY and environment READY are independent facts. V1-V10 remain immutable.
ALTER TABLE pf.workspace DROP CONSTRAINT workspace_environment_state_check;
ALTER TABLE pf.workspace ADD CONSTRAINT workspace_environment_state_check
    CHECK(environment_state IN('NOT_CREATED','PREPARING','READY','STOPPED','ERROR','BLOCKED'));

CREATE TABLE pf.workspace_environment (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL UNIQUE,
    generation bigint NOT NULL CHECK(generation>0),
    fence bigint NOT NULL CHECK(fence>0),
    state text NOT NULL CHECK(state IN('NOT_CREATED','PREPARING','READY','STOPPED','ERROR','BLOCKED')),
    image_id text NOT NULL CHECK(image_id ~ '^sha256:[0-9a-f]{64}$'),
    policy_hash varchar(64) NOT NULL CHECK(policy_hash ~ '^[0-9a-f]{64}$'),
    creation_command_id uuid NOT NULL,
    creation_fence bigint NOT NULL CHECK(creation_fence>0 AND creation_fence<=fence),
    operation_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK(version>0),
    container_id varchar(64) CHECK(container_id ~ '^[0-9a-f]{64}$'),
    container_started_at text,
    readiness_hash varchar(64) CHECK(readiness_hash ~ '^[0-9a-f]{64}$'),
    error_code varchar(64) CHECK(error_code ~ '^[A-Z_]{1,64}$'),
    last_activity_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id),
    UNIQUE(space_id,workspace_id,id),
    CHECK(state<>'READY' OR(container_id IS NOT NULL AND container_started_at IS NOT NULL AND readiness_hash IS NOT NULL))
);

CREATE TABLE pf.workspace_environment_operation (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    kind text NOT NULL CHECK(kind IN('PREPARE','STOP')),
    initiator text NOT NULL CHECK(initiator IN('USER','SYSTEM')),
    actor_id uuid REFERENCES pf.app_user(id),
    command_id uuid UNIQUE,
    effect_origin text NOT NULL DEFAULT 'LIFECYCLE_COMMAND' CHECK(effect_origin IN('LIFECYCLE_COMMAND','USE_TERMINATION')),
    source_use_id uuid,
    generation bigint NOT NULL CHECK(generation>0),
    fence bigint NOT NULL CHECK(fence>0),
    status text NOT NULL CHECK(status IN('PENDING','CLAIMED','RUNNING','COMMITTED','FAILED','UNKNOWN')),
    effect_state text NOT NULL DEFAULT 'NOT_STARTED' CHECK(effect_state IN('NOT_STARTED','MAY_HAVE_STARTED','READY','STOPPED')),
    owner_id uuid,
    lease_until timestamptz,
    deadline_at timestamptz,
    error_code varchar(64) CHECK(error_code ~ '^[A-Z_]{1,64}$'),
    version bigint NOT NULL DEFAULT 1 CHECK(version>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,environment_id) REFERENCES pf.workspace_environment(space_id,workspace_id,id),
    UNIQUE(space_id,workspace_id,environment_id,id),
    UNIQUE(space_id,workspace_id,environment_id,id,generation,fence),
    UNIQUE(space_id,workspace_id,environment_id,command_id,generation,fence),
    UNIQUE(space_id,workspace_id,environment_id,id,command_id,generation,fence),
    CHECK(initiator<>'USER' OR actor_id IS NOT NULL),
    CHECK(kind<>'PREPARE' OR initiator='USER'),
    CHECK((effect_origin='LIFECYCLE_COMMAND' AND command_id IS NOT NULL AND source_use_id IS NULL)
       OR(effect_origin='USE_TERMINATION' AND command_id IS NULL AND source_use_id IS NOT NULL
          AND kind='STOP' AND initiator='SYSTEM' AND status='COMMITTED' AND effect_state='STOPPED'))
);
ALTER TABLE pf.workspace_environment ADD CONSTRAINT environment_current_operation
    FOREIGN KEY(space_id,workspace_id,id,operation_id)
    REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE pf.workspace_environment ADD CONSTRAINT environment_creation_identity
    FOREIGN KEY(space_id,workspace_id,id,creation_command_id,generation,creation_fence)
    REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,command_id,generation,fence)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE pf.workspace_sandbox_slot (
    id integer PRIMARY KEY CHECK(id=1),
    space_id uuid,
    workspace_id uuid,
    environment_id uuid,
    generation bigint CHECK(generation>0),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    FOREIGN KEY(space_id,workspace_id,environment_id) REFERENCES pf.workspace_environment(space_id,workspace_id,id),
    CHECK((space_id IS NULL AND workspace_id IS NULL AND environment_id IS NULL AND generation IS NULL)
       OR(space_id IS NOT NULL AND workspace_id IS NOT NULL AND environment_id IS NOT NULL AND generation IS NOT NULL))
);
INSERT INTO pf.workspace_sandbox_slot(id) VALUES(1);

CREATE TABLE pf.workspace_environment_idempotency (
    space_id uuid NOT NULL,
    actor_id uuid NOT NULL REFERENCES pf.app_user(id),
    request_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    workspace_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    PRIMARY KEY(space_id,actor_id,request_id),
    FOREIGN KEY(space_id,workspace_id,environment_id,operation_id)
        REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,id)
);
CREATE TABLE pf.workspace_environment_outbox (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    operation_id uuid NOT NULL UNIQUE,
    generation bigint NOT NULL CHECK(generation>0),
    fence bigint NOT NULL CHECK(fence>0),
    status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','PUBLISHING','PUBLISHED')),
    relay_owner uuid,
    relay_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,environment_id,operation_id)
        REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,id),
    FOREIGN KEY(space_id,workspace_id,environment_id,operation_id,generation,fence)
        REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,id,generation,fence)
);
CREATE INDEX environment_outbox_pending ON pf.workspace_environment_outbox(status,created_at);
CREATE TABLE pf.workspace_environment_inbox (
    event_id uuid PRIMARY KEY REFERENCES pf.workspace_environment_outbox(id),
    operation_id uuid NOT NULL REFERENCES pf.workspace_environment_operation(id),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pf.workspace_runner_instance (
    id uuid PRIMARY KEY,
    container_id varchar(64) NOT NULL CHECK(container_id ~ '^[0-9a-f]{64}$'),
    image_id text NOT NULL CHECK(image_id ~ '^sha256:[0-9a-f]{64}$'),
    started_at text NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(container_id,started_at)
);
CREATE TABLE pf.workspace_runner_stop_proof (
    runner_instance_id uuid PRIMARY KEY REFERENCES pf.workspace_runner_instance(id),
    container_id varchar(64) NOT NULL CHECK(container_id ~ '^[0-9a-f]{64}$'),
    image_id text NOT NULL CHECK(image_id ~ '^sha256:[0-9a-f]{64}$'),
    started_at text NOT NULL,
    stopped_at timestamptz NOT NULL,
    observed_at timestamptz NOT NULL,
    evidence_hash varchar(64) NOT NULL UNIQUE CHECK(evidence_hash ~ '^[0-9a-f]{64}$'),
    consumed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK(CAST(started_at AS timestamptz)<stopped_at AND stopped_at<=observed_at)
);
CREATE TABLE pf.workspace_environment_command (
    command_id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    generation bigint NOT NULL CHECK(generation>0),
    fence bigint NOT NULL CHECK(fence>0),
    owner_id uuid NOT NULL,
    runner_instance_id uuid NOT NULL REFERENCES pf.workspace_runner_instance(id),
    result_runner_instance_id uuid REFERENCES pf.workspace_runner_instance(id),
    request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    state text NOT NULL CHECK(state IN('RUNNING','COMMITTED','FAILED','UNKNOWN')),
    result_revision bigint NOT NULL DEFAULT 0 CHECK(result_revision>=0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,environment_id,operation_id,command_id,generation,fence)
        REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,id,command_id,generation,fence)
);
CREATE TABLE pf.workspace_environment_receipt (
    command_id uuid NOT NULL REFERENCES pf.workspace_environment_command(command_id),
    revision bigint NOT NULL CHECK(revision>0),
    state text NOT NULL CHECK(state IN('COMMITTED','FAILED','UNKNOWN')),
    result_text text NOT NULL CHECK(octet_length(result_text)<=16384),
    result_json jsonb NOT NULL CHECK(jsonb_typeof(result_json)='object'),
    content_hash varchar(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(command_id,revision),
    CHECK(result_json=CAST(result_text AS jsonb))
);

-- Uses have their own identity; a nonexistent task is never fabricated.
CREATE TABLE pf.workspace_environment_use (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    actor_id uuid NOT NULL REFERENCES pf.app_user(id),
    generation bigint NOT NULL CHECK(generation>0),
    fence bigint NOT NULL CHECK(fence>0),
    owner_id uuid NOT NULL,
    runner_instance_id uuid REFERENCES pf.workspace_runner_instance(id),
    kind text NOT NULL CHECK(kind IN('FILE_READ','FILE_WRITE','FILE_LIST','FIXED_PROBE')),
    request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    request_id uuid NOT NULL,
    request_text text NOT NULL CHECK(octet_length(request_text)<=65536),
    creation_command_id uuid NOT NULL,
    creation_fence bigint NOT NULL CHECK(creation_fence>0),
    project_id integer NOT NULL CHECK(project_id>=200001),
    image_id text NOT NULL CHECK(image_id ~ '^sha256:[0-9a-f]{64}$'),
    policy_hash varchar(64) NOT NULL CHECK(policy_hash ~ '^[0-9a-f]{64}$'),
    status text NOT NULL CHECK(status IN('CLAIMED','RUNNING','COMMITTED','FAILED','UNKNOWN','STOPPED')),
    result_state text NOT NULL DEFAULT 'PENDING' CHECK(result_state IN('PENDING','KNOWN','UNKNOWN')),
    container_id varchar(64) NOT NULL CHECK(container_id ~ '^[0-9a-f]{64}$'),
    container_started_at text NOT NULL,
    cancel_requested boolean NOT NULL DEFAULT false,
    error_code varchar(64) CHECK(error_code ~ '^[A-Z_]{1,64}$'),
    stop_operation_id uuid,
    exec_id varchar(64) UNIQUE CHECK(exec_id ~ '^[0-9a-f]{64}$'),
    lease_until timestamptz NOT NULL,
    execute_until timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK(version>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,environment_id) REFERENCES pf.workspace_environment(space_id,workspace_id,id),
    FOREIGN KEY(space_id,workspace_id,environment_id,stop_operation_id) REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,id),
    FOREIGN KEY(space_id,workspace_id,environment_id,creation_command_id,generation,creation_fence)
        REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,command_id,generation,fence),
    UNIQUE(space_id,workspace_id,environment_id,id),
    UNIQUE(space_id,actor_id,request_id),
    CHECK(lease_until<=deadline_at AND execute_until<deadline_at),
    CHECK(status<>'RUNNING' OR(container_id IS NOT NULL AND exec_id IS NOT NULL AND runner_instance_id IS NOT NULL))
);
CREATE UNIQUE INDEX environment_one_unresolved_use ON pf.workspace_environment_use(environment_id)
    WHERE status IN('CLAIMED','RUNNING','UNKNOWN');
CREATE TABLE pf.workspace_environment_use_result (
    use_id uuid PRIMARY KEY REFERENCES pf.workspace_environment_use(id),
    --64KiB raw output may require6x JSON escaping; this is still a bounded result projection.
    result_text text NOT NULL CHECK(octet_length(result_text)<=524288),
    content_hash varchar(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
    exit_code bigint NOT NULL,
    truncated boolean NOT NULL,
    runner_instance_id uuid NOT NULL REFERENCES pf.workspace_runner_instance(id),
    prior_runner_proof_id uuid REFERENCES pf.workspace_runner_stop_proof(runner_instance_id),
    writer_kind text NOT NULL CHECK(writer_kind IN('EXECUTION_OWNER','SYSTEM_STOP')),
    writer_owner_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pf.workspace_environment_stop_receipt (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    generation bigint NOT NULL CHECK(generation>0),
    fence bigint NOT NULL CHECK(fence>0),
    owner_id uuid NOT NULL,
    runner_instance_id uuid NOT NULL REFERENCES pf.workspace_runner_instance(id),
    prior_runner_proof_id uuid REFERENCES pf.workspace_runner_stop_proof(runner_instance_id),
    source_use_id uuid,
    proof_kind text NOT NULL CHECK(proof_kind IN('LOCAL_INSPECT','HOST_PROOF','NEVER_STARTED')),
    container_id varchar(64) NOT NULL CHECK(container_id ~ '^[0-9a-f]{64}$'),
    image_id text NOT NULL CHECK(image_id ~ '^sha256:[0-9a-f]{64}$'),
    container_started_at text NOT NULL,
    container_finished_at text NOT NULL,
    exec_stopped boolean NOT NULL CHECK(exec_stopped),
    callbacks_stopped boolean NOT NULL CHECK(callbacks_stopped),
    proof_hash varchar(64) NOT NULL CHECK(proof_hash ~ '^[0-9a-f]{64}$'),
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,environment_id,operation_id)
        REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,id),
    FOREIGN KEY(space_id,workspace_id,environment_id,source_use_id)
        REFERENCES pf.workspace_environment_use(space_id,workspace_id,environment_id,id),
    UNIQUE(operation_id,container_id)
);
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT environment_stop_source_use
    FOREIGN KEY(space_id,workspace_id,environment_id,source_use_id)
    REFERENCES pf.workspace_environment_use(space_id,workspace_id,environment_id,id);
CREATE TABLE pf.workspace_environment_event (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    environment_version bigint NOT NULL CHECK(environment_version>0),
    event_type text NOT NULL CHECK(event_type='workspace.environment_changed'),
    payload_json jsonb NOT NULL CHECK(jsonb_typeof(payload_json)='object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,environment_id) REFERENCES pf.workspace_environment(space_id,workspace_id,id),
    UNIQUE(environment_id,environment_version)
);
