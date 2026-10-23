-- Additive correction: historical V11 file executions retain their actual location.
ALTER TABLE pf.workspace_environment_use ADD COLUMN file_execution_mode text NOT NULL DEFAULT 'LEGACY_SANDBOX'
    CHECK(file_execution_mode IN('LEGACY_SANDBOX','GUEST_EXEC','TRUSTED_HELPER','TRUSTED_HELPER_PROBE'));

CREATE TABLE pf.workspace_file_helper (
    use_id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    generation bigint NOT NULL CHECK(generation>0),
    fence bigint NOT NULL CHECK(fence>0),
    runner_instance_id uuid NOT NULL REFERENCES pf.workspace_runner_instance(id),
    image_id text NOT NULL CHECK(image_id ~ '^sha256:[0-9a-f]{64}$'),
    policy_hash varchar(64) NOT NULL CHECK(policy_hash ~ '^[0-9a-f]{64}$'),
    container_name text NOT NULL UNIQUE CHECK(container_name='ap-file-helper-'||use_id::text),
    intent_hash varchar(64) NOT NULL CHECK(intent_hash ~ '^[0-9a-f]{64}$'),
    container_id varchar(64) UNIQUE CHECK(container_id ~ '^[0-9a-f]{64}$'),
    start_requested boolean NOT NULL DEFAULT false,
    started_at text,
    exec_id varchar(64) UNIQUE CHECK(exec_id ~ '^[0-9a-f]{64}$'),
    version bigint NOT NULL DEFAULT 1 CHECK(version>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,environment_id,use_id)
        REFERENCES pf.workspace_environment_use(space_id,workspace_id,environment_id,id),
    CHECK(NOT start_requested OR container_id IS NOT NULL),
    CHECK(started_at IS NULL OR(start_requested AND container_id IS NOT NULL)),
    CHECK(exec_id IS NULL OR started_at IS NOT NULL)
);

CREATE TABLE pf.workspace_file_helper_stop (
    use_id uuid PRIMARY KEY REFERENCES pf.workspace_file_helper(use_id),
    observer_runner_id uuid NOT NULL REFERENCES pf.workspace_runner_instance(id),
    prior_runner_proof_id uuid REFERENCES pf.workspace_runner_stop_proof(runner_instance_id),
    container_id varchar(64) CHECK(container_id IS NULL OR container_id ~ '^[0-9a-f]{64}$'),
    image_id text NOT NULL CHECK(image_id ~ '^sha256:[0-9a-f]{64}$'),
    started_at text,
    exec_id varchar(64) CHECK(exec_id ~ '^[0-9a-f]{64}$'),
    never_started boolean NOT NULL,
    finished_at text,
    exec_stopped boolean NOT NULL CHECK(exec_stopped),
    callbacks_stopped boolean NOT NULL CHECK(callbacks_stopped),
    stop_text text NOT NULL CHECK(octet_length(stop_text)<=8192),
    content_hash varchar(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK((never_started AND started_at IS NULL AND exec_id IS NULL AND finished_at IS NULL)
        OR(NOT never_started AND started_at IS NOT NULL AND finished_at IS NOT NULL
        AND container_id IS NOT NULL
        AND CAST(started_at AS timestamptz)<CAST(finished_at AS timestamptz)
        AND CAST(finished_at AS timestamptz)<=observed_at)),
    CHECK(NOT never_started OR container_id IS NULL OR container_id ~ '^[0-9a-f]{64}$')
);
