-- Environment lifecycle only. Workspace/files/quota and runtime schema1 remain intact.
ALTER TABLE pf.workspace DROP CONSTRAINT workspace_environment_state_check;
ALTER TABLE pf.workspace ADD CONSTRAINT workspace_environment_state_check
    CHECK(environment_state IN('NOT_CREATED','PREPARING','READY','STOPPING','STOPPED','DELETING','DELETED','ERROR','BLOCKED'));
-- V11's unnamed column constraint received suffix1 because the explicit
-- workspace constraint already occupies the unsuffixed name in this schema.
ALTER TABLE pf.workspace_environment DROP CONSTRAINT workspace_environment_state_check1;
ALTER TABLE pf.workspace_environment ADD CONSTRAINT workspace_environment_lifecycle_state_check
    CHECK(state IN('NOT_CREATED','PREPARING','READY','STOPPING','STOPPED','DELETING','DELETED','ERROR','BLOCKED'));

ALTER TABLE pf.workspace_environment_operation DROP CONSTRAINT workspace_environment_operation_kind_check;
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT workspace_environment_operation_kind_check
    CHECK(kind IN('PREPARE','WAKE','STOP','SLEEP','DELETE'));
ALTER TABLE pf.workspace_environment_operation DROP CONSTRAINT workspace_environment_operation_status_check;
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT workspace_environment_operation_status_check
    CHECK(status IN('DRAINING','PENDING','CLAIMED','RUNNING','COMMITTED','FAILED','UNKNOWN'));

ALTER TABLE pf.workspace_environment_operation
    ADD COLUMN protocol text NOT NULL DEFAULT 'ENVIRONMENT_V2'
        CHECK(protocol IN('ENVIRONMENT_V2','ENVIRONMENT_V3')),
    ADD COLUMN lifecycle_intent text,
    ADD COLUMN target_creation_command_id uuid,
    ADD COLUMN target_creation_fence bigint CHECK(target_creation_fence>0),
    ADD COLUMN target_image_id text CHECK(target_image_id ~ '^sha256:[0-9a-f]{64}$'),
    ADD COLUMN target_policy_hash varchar(64) CHECK(target_policy_hash ~ '^[0-9a-f]{64}$'),
    ADD COLUMN target_project_id integer CHECK(target_project_id>=200001),
    ADD COLUMN target_container_id varchar(64) CHECK(target_container_id ~ '^[0-9a-f]{64}$'),
    ADD COLUMN target_container_started_at text,
    ADD COLUMN cleanup_required boolean NOT NULL DEFAULT false,
    ADD COLUMN cleanup_state text NOT NULL DEFAULT 'NOT_REQUIRED'
        CHECK(cleanup_state IN('NOT_REQUIRED','PENDING','CONFIRMED')),
    ADD COLUMN observation_owner uuid,
    ADD COLUMN observation_until timestamptz,
    ADD COLUMN observation_revision bigint NOT NULL DEFAULT 0 CHECK(observation_revision>=0);

-- Preserve historical STOP as STOP; do not invent a user sleep/delete command.
UPDATE pf.workspace_environment_operation SET lifecycle_intent=kind;
-- Old V2 binaries omit this column. NULL on a newly inserted V2 row means its
-- existing kind, never an invented lifecycle command; V3 requires it explicitly.
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT environment_lifecycle_intent
    CHECK(lifecycle_intent IN('PREPARE','WAKE','STOP','SLEEP','DELETE'));
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT environment_observation_lease
    CHECK((observation_owner IS NULL)=(observation_until IS NULL));
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT environment_v3_intent
    CHECK(protocol<>'ENVIRONMENT_V3' OR(lifecycle_intent IS NOT NULL AND lifecycle_intent=kind));
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT environment_v3_creation_authority
    CHECK(protocol<>'ENVIRONMENT_V3' OR kind NOT IN('PREPARE','WAKE') OR(initiator='USER' AND actor_id IS NOT NULL));
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT environment_v3_target
    CHECK(protocol<>'ENVIRONMENT_V3' OR status='DRAINING' OR(
        target_creation_command_id IS NOT NULL AND target_creation_fence IS NOT NULL
        AND target_creation_fence<=fence AND target_image_id IS NOT NULL
        AND target_policy_hash IS NOT NULL AND target_project_id IS NOT NULL));
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT environment_v3_stop_target
    CHECK(protocol<>'ENVIRONMENT_V3' OR kind NOT IN('STOP','SLEEP','DELETE') OR status='DRAINING'
        OR(target_container_id IS NOT NULL AND target_container_started_at IS NOT NULL));
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT environment_v3_cleanup
    CHECK(protocol<>'ENVIRONMENT_V3' OR(
        (kind IN('PREPARE','WAKE') AND NOT cleanup_required AND cleanup_state='NOT_REQUIRED')
        OR(kind IN('STOP','SLEEP','DELETE') AND cleanup_required AND cleanup_state IN('PENDING','CONFIRMED'))));
ALTER TABLE pf.workspace_environment_operation ADD CONSTRAINT environment_frozen_creation_identity
    FOREIGN KEY(space_id,workspace_id,environment_id,target_creation_command_id,generation,target_creation_fence)
    REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,command_id,generation,fence)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE pf.workspace_environment ADD COLUMN pending_operation_id uuid;
ALTER TABLE pf.workspace_environment ADD CONSTRAINT environment_pending_operation
    FOREIGN KEY(space_id,workspace_id,id,pending_operation_id)
    REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE pf.workspace_environment ADD CONSTRAINT environment_pending_distinct
    CHECK(pending_operation_id IS NULL OR pending_operation_id<>operation_id);

-- Drain handoff may advance an old-use fence before activating the queued intent.
-- Only this exact outbox tuple FK is made deferred; commit still enforces it.
DO $$
DECLARE target_name text; target_count integer;
BEGIN
    SELECT count(*),min(conname::text) INTO target_count,target_name FROM pg_constraint
    WHERE conrelid='pf.workspace_environment_outbox'::regclass
      AND confrelid='pf.workspace_environment_operation'::regclass AND contype='f'
      AND cardinality(conkey)=6;
    IF target_count<>1 THEN RAISE EXCEPTION 'Expected exact environment outbox generation identity'; END IF;
    EXECUTE format('ALTER TABLE pf.workspace_environment_outbox DROP CONSTRAINT %I',target_name);
END $$;
ALTER TABLE pf.workspace_environment_outbox ADD CONSTRAINT environment_outbox_generation_identity
    FOREIGN KEY(space_id,workspace_id,environment_id,operation_id,generation,fence)
    REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,id,generation,fence)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE pf.workspace_environment_stop_receipt ADD CONSTRAINT environment_stop_receipt_scope
    UNIQUE(space_id,workspace_id,environment_id,operation_id,id);
ALTER TABLE pf.workspace_environment_command ADD CONSTRAINT environment_command_cleanup_scope
    UNIQUE(space_id,workspace_id,environment_id,operation_id,command_id,generation,fence);
CREATE TABLE pf.workspace_environment_cleanup (
    id uuid PRIMARY KEY,
    space_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    command_id uuid NOT NULL,
    generation bigint NOT NULL CHECK(generation>0),
    fence bigint NOT NULL CHECK(fence>0),
    stop_receipt_id uuid NOT NULL,
    observer_runner_id uuid NOT NULL REFERENCES pf.workspace_runner_instance(id),
    outcome text NOT NULL CHECK(outcome IN('FAILED','CONFIRMED')),
    container_id varchar(64) NOT NULL CHECK(container_id ~ '^[0-9a-f]{64}$'),
    image_id text NOT NULL CHECK(image_id ~ '^sha256:[0-9a-f]{64}$'),
    container_started_at text NOT NULL,
    container_finished_at text NOT NULL,
    error_code varchar(64) CHECK(error_code ~ '^[A-Z_]{1,64}$'),
    evidence_text text NOT NULL CHECK(octet_length(evidence_text)<=16384),
    evidence_hash varchar(64) NOT NULL CHECK(evidence_hash ~ '^[0-9a-f]{64}$'),
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,workspace_id,environment_id,operation_id,command_id,generation,fence)
        REFERENCES pf.workspace_environment_command(space_id,workspace_id,environment_id,operation_id,command_id,generation,fence),
    FOREIGN KEY(space_id,workspace_id,environment_id,operation_id,stop_receipt_id)
        REFERENCES pf.workspace_environment_stop_receipt(space_id,workspace_id,environment_id,operation_id,id),
    CHECK(outcome<>'CONFIRMED' OR error_code IS NULL)
);
CREATE UNIQUE INDEX environment_cleanup_confirmed ON pf.workspace_environment_cleanup(command_id) WHERE outcome='CONFIRMED';
CREATE INDEX environment_pending_lifecycle ON pf.workspace_environment(pending_operation_id) WHERE pending_operation_id IS NOT NULL;
CREATE INDEX environment_original_reconciliation ON pf.workspace_environment_operation(status,observation_until,created_at)
    WHERE protocol='ENVIRONMENT_V3' AND status IN('RUNNING','UNKNOWN');
