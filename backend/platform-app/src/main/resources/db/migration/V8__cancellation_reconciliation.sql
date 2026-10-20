-- Additive cancellation and accounting evidence; never invent legacy supplier facts.
ALTER TABLE pf.model_call ADD COLUMN reconciliation_revision bigint NOT NULL DEFAULT 0 CHECK(reconciliation_revision>=0);
ALTER TABLE pf.model_call ADD COLUMN provider_kind text NOT NULL DEFAULT 'LEGACY_UNKNOWN'
    CHECK(provider_kind IN ('REAL','DETERMINISTIC','LEGACY_UNKNOWN'));
ALTER TABLE pf.model_call ADD CONSTRAINT call_scoped_identity UNIQUE(space_id,task_id,id);
CREATE TABLE pf.task_cancellation (
    task_id uuid PRIMARY KEY, space_id uuid NOT NULL, command_id uuid NOT NULL UNIQUE,
    actor_id uuid NOT NULL, requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    attempt_id uuid, owner_id uuid, generation bigint, fencing_token bigint,
    FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id),
    FOREIGN KEY(space_id,actor_id) REFERENCES pf.membership(space_id,user_id),
    FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id)
);
CREATE TABLE pf.execution_stop_receipt (
    attempt_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL,
    owner_id uuid NOT NULL, fencing_token bigint NOT NULL CHECK(fencing_token>0),
    source text NOT NULL CHECK(source IN ('LOCAL_QUIESCENCE','HOST_STOP_PROOF')),
    evidence_hash text NOT NULL CHECK(evidence_hash ~ '^[a-f0-9]{64}$'),
    stopped_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id)
);
CREATE TABLE pf.model_call_reconciliation (
    command_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, call_id uuid NOT NULL,
    expected_revision bigint NOT NULL CHECK(expected_revision>=0),
    decision text NOT NULL CHECK(decision IN ('KEEP_UNKNOWN','CONFIRMED_NOT_EXECUTED','CONFIRMED_USAGE')),
    evidence_ref uuid NOT NULL, evidence_hash text NOT NULL CHECK(evidence_hash ~ '^[a-f0-9]{64}$'),
    evidence_kind text NOT NULL CHECK(evidence_kind IN ('REVIEW','UNSENT_LEDGER','PROVIDER_RECORD')),
    operator_id text NOT NULL CHECK(length(operator_id) BETWEEN 1 AND 128),
    request_hash text NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
    before_state jsonb NOT NULL, after_state jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(call_id,expected_revision),
    FOREIGN KEY(space_id,task_id,call_id) REFERENCES pf.model_call(space_id,task_id,id)
);
CREATE INDEX stop_receipt_task ON pf.execution_stop_receipt(space_id,task_id);
CREATE INDEX reconciliation_task ON pf.model_call_reconciliation(space_id,task_id,created_at);
CREATE FUNCTION pf.reject_control_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'CONTROL_EVIDENCE_IMMUTABLE' USING ERRCODE='55000'; END $$;
CREATE TRIGGER cancellation_immutable BEFORE UPDATE OR DELETE ON pf.task_cancellation
    FOR EACH ROW EXECUTE FUNCTION pf.reject_control_evidence_mutation();
CREATE TRIGGER stop_receipt_immutable BEFORE UPDATE OR DELETE ON pf.execution_stop_receipt
    FOR EACH ROW EXECUTE FUNCTION pf.reject_control_evidence_mutation();
CREATE TRIGGER reconciliation_immutable BEFORE UPDATE OR DELETE ON pf.model_call_reconciliation
    FOR EACH ROW EXECUTE FUNCTION pf.reject_control_evidence_mutation();
