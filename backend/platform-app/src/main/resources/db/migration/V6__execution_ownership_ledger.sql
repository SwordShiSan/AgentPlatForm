-- Additive R2.1 facts. Runtime baseline remains schema1; no invented R1 checkpoints.
ALTER TABLE pf.attempt ADD COLUMN dispatch_generation bigint;
ALTER TABLE pf.attempt ADD CONSTRAINT attempt_scoped_identity UNIQUE(space_id,task_id,id);
CREATE TABLE pf.conversation_lease (
 space_id uuid NOT NULL, conversation_id uuid NOT NULL, task_id uuid NOT NULL,
 attempt_id uuid NOT NULL, owner_id uuid NOT NULL, generation bigint NOT NULL,
 fencing_token bigint NOT NULL, lease_until timestamptz NOT NULL,
 PRIMARY KEY(space_id,conversation_id),
 FOREIGN KEY(space_id,conversation_id) REFERENCES pf.conversation(space_id,id),
 FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id)
);
CREATE TABLE pf.model_turn (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, attempt_id uuid NOT NULL,
 fencing_token bigint NOT NULL, ordinal bigint NOT NULL CHECK(ordinal>0),
 status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','COMMITTED')),
 sdk_turn_id uuid, input_json jsonb NOT NULL, input_hash text NOT NULL,
 round_json jsonb, content_hash text, accepted_call_id uuid REFERENCES pf.model_call(id),
 created_at timestamptz NOT NULL DEFAULT now(), committed_at timestamptz,
 UNIQUE(space_id,task_id,id), UNIQUE(attempt_id,ordinal),
 FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
 CHECK((status='OPEN' AND round_json IS NULL AND content_hash IS NULL) OR
       (status='COMMITTED' AND round_json IS NOT NULL AND content_hash IS NOT NULL AND committed_at IS NOT NULL))
);
CREATE UNIQUE INDEX model_turn_one_open ON pf.model_turn(attempt_id) WHERE status='OPEN';
ALTER TABLE pf.model_call ADD COLUMN turn_id uuid;
ALTER TABLE pf.model_call ADD CONSTRAINT call_turn_scope FOREIGN KEY(space_id,task_id,turn_id) REFERENCES pf.model_turn(space_id,task_id,id);
CREATE TABLE pf.tool_operation (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, attempt_id uuid NOT NULL,
 turn_id uuid NOT NULL, fencing_token bigint NOT NULL, call_id text NOT NULL, call_json jsonb NOT NULL,
 call_hash text NOT NULL, status text NOT NULL DEFAULT 'PREPARED' CHECK(status IN ('PREPARED','RUNNING','SUCCEEDED','UNKNOWN','DENIED')),
 result_json jsonb, result_hash text, created_at timestamptz NOT NULL DEFAULT now(), ended_at timestamptz,
 UNIQUE(task_id,call_id), FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,turn_id) REFERENCES pf.model_turn(space_id,task_id,id)
);
CREATE TABLE pf.checkpoint (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, attempt_id uuid NOT NULL,
 fencing_token bigint NOT NULL, status text NOT NULL DEFAULT 'COMMITTED' CHECK(status='COMMITTED'),
 bundle_id uuid NOT NULL REFERENCES pf.runtime_bundle(id), bundle_hash text NOT NULL,
 manifest_json jsonb NOT NULL, manifest_hash text NOT NULL,
 canonical_json jsonb NOT NULL, canonical_hash text NOT NULL, watermark bigint NOT NULL,
 state_json jsonb NOT NULL, state_hash text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id)
);
CREATE FUNCTION pf.reject_checkpoint_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'CHECKPOINT_IMMUTABLE' USING ERRCODE='55000'; END $$;
CREATE TRIGGER checkpoint_immutable BEFORE UPDATE OR DELETE ON pf.checkpoint FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE INDEX checkpoint_latest ON pf.checkpoint(space_id,task_id,created_at DESC,id DESC);
CREATE TABLE pf.stopped_owner_proof (
 owner_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, attempt_id uuid NOT NULL,
 fencing_token bigint NOT NULL, container_id text NOT NULL, image_id text NOT NULL,
 started_at timestamptz NOT NULL, stopped_at timestamptz NOT NULL, observed_at timestamptz NOT NULL,
 evidence_hash text NOT NULL UNIQUE, consumed_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
 CHECK(started_at<stopped_at AND stopped_at<=observed_at)
);
REVOKE ALL ON pf.stopped_owner_proof FROM PUBLIC;
