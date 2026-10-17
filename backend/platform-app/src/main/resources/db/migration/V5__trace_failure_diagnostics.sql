-- Additive only: R0 baseline schema remains 1; historical task trace is its stable ID.
-- Remove only acceptance ceilings; preserve facts, nonnegative checks and business budgets.
ALTER TABLE pf.acceptance_model_meter DROP CONSTRAINT acceptance_model_meter_physical_calls_check;
ALTER TABLE pf.acceptance_model_meter DROP CONSTRAINT acceptance_model_meter_check;
ALTER TABLE pf.acceptance_model_meter ADD CONSTRAINT acceptance_meter_nonnegative
 CHECK(physical_calls>=0 AND reserved_micros>=0 AND consumed_micros>=0);
ALTER TABLE pf.task ADD COLUMN trace_id uuid;
UPDATE pf.task SET trace_id=id;
ALTER TABLE pf.task ALTER COLUMN trace_id SET NOT NULL;
ALTER TABLE pf.event_outbox ADD COLUMN trace_id uuid;
UPDATE pf.event_outbox o SET trace_id=t.trace_id FROM pf.task t WHERE t.id=o.aggregate_id;
ALTER TABLE pf.event_outbox ALTER COLUMN trace_id SET NOT NULL;
ALTER TABLE pf.attempt ADD COLUMN trace_id uuid;
UPDATE pf.attempt a SET trace_id=t.trace_id FROM pf.task t WHERE t.id=a.task_id;
ALTER TABLE pf.attempt ALTER COLUMN trace_id SET NOT NULL;
ALTER TABLE pf.model_call ADD COLUMN trace_id uuid;
UPDATE pf.model_call m SET trace_id=t.trace_id FROM pf.task t WHERE t.id=m.task_id;
ALTER TABLE pf.model_call ALTER COLUMN trace_id SET NOT NULL;
CREATE FUNCTION pf.inherit_task_trace() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='event_outbox' THEN
  SELECT trace_id INTO NEW.trace_id FROM pf.task WHERE id=NEW.aggregate_id;
 ELSE
  SELECT trace_id INTO NEW.trace_id FROM pf.task WHERE id=NEW.task_id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER outbox_trace BEFORE INSERT ON pf.event_outbox FOR EACH ROW EXECUTE FUNCTION pf.inherit_task_trace();
CREATE TRIGGER attempt_trace BEFORE INSERT ON pf.attempt FOR EACH ROW EXECUTE FUNCTION pf.inherit_task_trace();
CREATE TRIGGER call_trace BEFORE INSERT ON pf.model_call FOR EACH ROW EXECUTE FUNCTION pf.inherit_task_trace();
CREATE TABLE pf.failure_record (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, attempt_id uuid REFERENCES pf.attempt(id),
 call_id uuid REFERENCES pf.model_call(id), trace_id uuid NOT NULL, code text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id)
);
CREATE INDEX failure_task ON pf.failure_record(space_id,task_id,created_at);
CREATE FUNCTION pf.record_task_failure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.reason_code IS NOT NULL AND NEW.status IN ('FAILED','CANCELLED','BLOCKED') AND
    (OLD.status IS DISTINCT FROM NEW.status OR OLD.reason_code IS DISTINCT FROM NEW.reason_code) THEN
  INSERT INTO pf.failure_record(id,space_id,task_id,attempt_id,call_id,trace_id,code)
  SELECT NEW.id,NEW.space_id,NEW.id,
   (SELECT id FROM pf.attempt WHERE task_id=NEW.id ORDER BY ordinal DESC LIMIT 1),
   (SELECT id FROM pf.model_call WHERE task_id=NEW.id ORDER BY created_at DESC,id DESC LIMIT 1),
   NEW.trace_id,NEW.reason_code ON CONFLICT(id) DO NOTHING;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER task_failure AFTER UPDATE ON pf.task FOR EACH ROW EXECUTE FUNCTION pf.record_task_failure();
