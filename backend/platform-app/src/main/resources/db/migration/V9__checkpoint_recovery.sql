-- Additive recovery authority. Historical rows are not invented recovery evidence.
ALTER TABLE pf.task ADD COLUMN execution_deadline_at timestamptz;
ALTER TABLE pf.task ADD COLUMN recovery_revision bigint NOT NULL DEFAULT 0 CHECK(recovery_revision>=0);
ALTER TABLE pf.model_turn ADD COLUMN task_ordinal bigint CHECK(task_ordinal>0);
CREATE UNIQUE INDEX logical_turn_task_ordinal ON pf.model_turn(task_id,task_ordinal) WHERE task_ordinal IS NOT NULL;
CREATE UNIQUE INDEX logical_turn_one_open ON pf.model_turn(task_id) WHERE status='OPEN' AND task_ordinal IS NOT NULL;
ALTER TABLE pf.checkpoint ADD COLUMN manifest_version integer NOT NULL DEFAULT 1 CHECK(manifest_version IN (1,2));
ALTER TABLE pf.checkpoint ADD COLUMN phase text NOT NULL DEFAULT 'LEGACY'
    CHECK(phase IN ('LEGACY','INPUT_READY','MODEL_COMMITTED','TOOL_COMMITTED','TERMINAL'));
ALTER TABLE pf.checkpoint ADD CONSTRAINT checkpoint_scoped_identity UNIQUE(space_id,task_id,id);
ALTER TABLE pf.tool_operation ADD CONSTRAINT operation_scoped_identity UNIQUE(space_id,task_id,id);
CREATE TABLE pf.checkpoint_recovery (
    command_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL,
    source_attempt_id uuid NOT NULL REFERENCES pf.execution_stop_receipt(attempt_id),
    checkpoint_id uuid NOT NULL, expected_task_version bigint NOT NULL,
    revision bigint NOT NULL CHECK(revision>0), request_hash text NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
    operator_id text NOT NULL CHECK(length(operator_id) BETWEEN 1 AND 128),
    status text NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','CLAIMED','COMPLETED','FAILED')),
    target_attempt_id uuid, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), completed_at timestamptz,
    UNIQUE(task_id,revision),
    FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id),
    FOREIGN KEY(space_id,task_id,source_attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
    FOREIGN KEY(space_id,task_id,target_attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
    FOREIGN KEY(space_id,task_id,checkpoint_id) REFERENCES pf.checkpoint(space_id,task_id,id)
);
CREATE UNIQUE INDEX recovery_one_queued ON pf.checkpoint_recovery(task_id) WHERE status='QUEUED';
CREATE TABLE pf.turn_execution_binding (
    space_id uuid NOT NULL, task_id uuid NOT NULL, attempt_id uuid NOT NULL, turn_id uuid NOT NULL,
    fencing_token bigint NOT NULL CHECK(fencing_token>0),
    PRIMARY KEY(attempt_id,turn_id),
    FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
    FOREIGN KEY(space_id,task_id,turn_id) REFERENCES pf.model_turn(space_id,task_id,id)
);
CREATE TABLE pf.operation_execution_binding (
    space_id uuid NOT NULL, task_id uuid NOT NULL, attempt_id uuid NOT NULL, operation_id uuid NOT NULL,
    fencing_token bigint NOT NULL CHECK(fencing_token>0),
    PRIMARY KEY(attempt_id,operation_id),
    FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
    FOREIGN KEY(space_id,task_id,operation_id) REFERENCES pf.tool_operation(space_id,task_id,id)
);
CREATE INDEX recovery_task_history ON pf.checkpoint_recovery(space_id,task_id,revision DESC);
