-- Additive R4.1. Historical runtime/schema1, usage, UNKNOWN holds and checkpoints remain unchanged.
ALTER TABLE pf.task DROP CONSTRAINT task_status_check;
ALTER TABLE pf.task ADD CONSTRAINT task_status_check CHECK(status IN ('QUEUED','RUNNING','WAITING_INPUT','BLOCKED','SUCCEEDED','FAILED','CANCELLED'));
ALTER TABLE pf.task ADD COLUMN plan_mode boolean NOT NULL DEFAULT false;
ALTER TABLE pf.task ADD COLUMN active_millis bigint NOT NULL DEFAULT 0 CHECK(active_millis>=0);
ALTER TABLE pf.task ADD COLUMN active_started_at timestamptz;
ALTER TABLE pf.task ADD COLUMN quota_mode text NOT NULL DEFAULT 'LEGACY' CHECK(quota_mode IN ('LEGACY','USER_DAILY'));
ALTER TABLE pf.checkpoint DROP CONSTRAINT checkpoint_phase_check;
ALTER TABLE pf.checkpoint ADD CONSTRAINT checkpoint_phase_check CHECK(phase IN ('LEGACY','INPUT_READY','MODEL_COMMITTED','TOOL_COMMITTED','WAITING_INPUT','TERMINAL'));

CREATE TABLE pf.task_plan (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, current_revision bigint NOT NULL DEFAULT 0,
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','REVIEW','APPROVED','DELIVERED','REJECTED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), UNIQUE(space_id,task_id), UNIQUE(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id)
);
CREATE TABLE pf.plan_revision (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, plan_id uuid NOT NULL,
 revision bigint NOT NULL CHECK(revision>0), title text NOT NULL CHECK(octet_length(title) BETWEEN 1 AND 1024),
 markdown text NOT NULL CHECK(octet_length(markdown) BETWEEN 1 AND 131072),
 content_hash text NOT NULL CHECK(content_hash ~ '^[a-f0-9]{64}$'), source text NOT NULL CHECK(source IN ('MODEL','USER')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,task_id,id), UNIQUE(plan_id,revision),
 trace_id uuid NOT NULL, CHECK(octet_length(title)+octet_length(markdown)<=131072),
 FOREIGN KEY(space_id,task_id,plan_id) REFERENCES pf.task_plan(space_id,task_id,id)
);
CREATE TRIGGER plan_revision_immutable BEFORE UPDATE OR DELETE ON pf.plan_revision FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.task_interaction (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, source_attempt_id uuid NOT NULL,
 call_id text NOT NULL, kind text NOT NULL CHECK(kind IN ('PLAN_APPROVAL','QUESTION')),
 optional boolean NOT NULL DEFAULT false, payload_json jsonb NOT NULL, revision_id uuid,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','ANSWERED','SUPERSEDED','EXPIRED','CANCELLED')),
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,task_id,id), FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id),
 FOREIGN KEY(space_id,task_id,source_attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,revision_id) REFERENCES pf.plan_revision(space_id,task_id,id),
 CHECK(kind<>'PLAN_APPROVAL' OR (revision_id IS NOT NULL AND NOT optional))
);
CREATE UNIQUE INDEX one_blocking_interaction ON pf.task_interaction(task_id) WHERE status='OPEN' AND NOT optional;
CREATE INDEX pending_conversation_interactions ON pf.task_interaction(space_id,task_id,status);
CREATE TABLE pf.interaction_answer (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, interaction_id uuid NOT NULL,
 user_id uuid NOT NULL REFERENCES pf.app_user(id), expected_version bigint NOT NULL,
 command_id uuid NOT NULL, request_hash text NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 decision text NOT NULL CHECK(decision IN ('EXECUTE_PLAN','REVISE_PLAN','REJECT_PLAN','DELIVER_PLAN','ANSWER','SKIP')),
 content_json jsonb NOT NULL CHECK(octet_length(content_json::text)<=262144), trace_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(interaction_id), UNIQUE(user_id,command_id), UNIQUE(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,interaction_id) REFERENCES pf.task_interaction(space_id,task_id,id)
);
CREATE TRIGGER interaction_answer_immutable BEFORE UPDATE OR DELETE ON pf.interaction_answer FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.control_input (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, source_attempt_id uuid NOT NULL,
 call_id text NOT NULL, tool_name text NOT NULL CHECK(tool_name IN ('platform_plan_write','platform_plan_review','platform_ask_user')),
 call_hash text NOT NULL CHECK(call_hash ~ '^[a-f0-9]{64}$'), arguments_json jsonb NOT NULL, interaction_id uuid,
 status text NOT NULL DEFAULT 'PREPARED' CHECK(status IN ('PREPARED','COMMITTED')),
 result_json jsonb, trace_id uuid NOT NULL, UNIQUE(task_id,call_id), UNIQUE(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,source_attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,interaction_id) REFERENCES pf.task_interaction(space_id,task_id,id)
);
CREATE TABLE pf.interaction_resume (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, source_attempt_id uuid NOT NULL,
 checkpoint_id uuid NOT NULL, answer_id uuid NOT NULL, call_id text NOT NULL, answer_hash text NOT NULL CHECK(answer_hash ~ '^[a-f0-9]{64}$'),
 review_revision_id uuid, generation bigint NOT NULL, target_attempt_id uuid,
 status text NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','CLAIMED','COMPLETED','FAILED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(answer_id),
 FOREIGN KEY(space_id,task_id,source_attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,target_attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,checkpoint_id) REFERENCES pf.checkpoint(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,answer_id) REFERENCES pf.interaction_answer(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,review_revision_id) REFERENCES pf.plan_revision(space_id,task_id,id)
);
CREATE UNIQUE INDEX one_queued_interaction_resume ON pf.interaction_resume(task_id) WHERE status='QUEUED';
CREATE TABLE pf.interaction_resume_binding (
 resume_id uuid NOT NULL REFERENCES pf.interaction_resume(id), space_id uuid NOT NULL, task_id uuid NOT NULL,
 attempt_id uuid PRIMARY KEY, generation bigint NOT NULL, fence bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id)
);
CREATE TRIGGER interaction_resume_binding_immutable BEFORE UPDATE OR DELETE ON pf.interaction_resume_binding FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.control_input_consumption (
 answer_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, attempt_id uuid NOT NULL,
 answer_hash text NOT NULL CHECK(answer_hash ~ '^[a-f0-9]{64}$'), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id,answer_id) REFERENCES pf.interaction_answer(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id)
);
CREATE TRIGGER control_input_consumption_immutable BEFORE UPDATE OR DELETE ON pf.control_input_consumption FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

-- Common quota policy: default and per-user overrides. Administrators bypass admission limits, not metering.
CREATE TABLE pf.quota_policy (
 id uuid PRIMARY KEY, subject_user_id uuid REFERENCES pf.app_user(id), metric text NOT NULL CHECK(metric IN ('TOKENS','COST_MICROS')),
 period text NOT NULL DEFAULT 'DAILY' CHECK(period='DAILY'), timezone text NOT NULL DEFAULT 'Asia/Shanghai',
 limit_value bigint CHECK(limit_value>=0), version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX quota_default_policy ON pf.quota_policy(metric) WHERE subject_user_id IS NULL;
CREATE UNIQUE INDEX quota_user_policy ON pf.quota_policy(subject_user_id,metric) WHERE subject_user_id IS NOT NULL;
INSERT INTO pf.quota_policy(id,metric,limit_value) VALUES
 ('0199e000-0000-7000-8000-000000000015','TOKENS',1000000),
 ('0199e000-0000-7000-8000-000000000016','COST_MICROS',NULL);
CREATE TABLE pf.quota_principal (user_id uuid PRIMARY KEY REFERENCES pf.app_user(id));
CREATE TABLE pf.quota_bucket (
 user_id uuid NOT NULL REFERENCES pf.app_user(id), metric text NOT NULL CHECK(metric IN ('TOKENS','COST_MICROS')),
 window_start timestamptz NOT NULL, window_end timestamptz NOT NULL CHECK(window_end>window_start),
 reserved bigint NOT NULL DEFAULT 0 CHECK(reserved>=0), consumed bigint NOT NULL DEFAULT 0 CHECK(consumed>=0),
 version bigint NOT NULL DEFAULT 1, PRIMARY KEY(user_id,metric,window_start)
);
CREATE TABLE pf.quota_reservation (
 call_id uuid NOT NULL REFERENCES pf.model_call(id), metric text NOT NULL, user_id uuid NOT NULL,
 window_start timestamptz NOT NULL, policy_id uuid NOT NULL REFERENCES pf.quota_policy(id), policy_version bigint NOT NULL,
 reserved bigint NOT NULL CHECK(reserved>=0), actual bigint CHECK(actual>=0), unlimited boolean NOT NULL,
 status text NOT NULL DEFAULT 'RESERVED' CHECK(status IN ('RESERVED','UNKNOWN','SETTLED','RELEASED')),
 PRIMARY KEY(call_id,metric), FOREIGN KEY(user_id,metric,window_start) REFERENCES pf.quota_bucket(user_id,metric,window_start),
 CHECK((status IN ('SETTLED','RELEASED') AND actual IS NOT NULL) OR (status IN ('RESERVED','UNKNOWN') AND actual IS NULL))
);
CREATE INDEX unresolved_user_quota ON pf.quota_reservation(user_id,metric,window_start) WHERE status IN ('RESERVED','UNKNOWN');
CREATE TABLE pf.quota_policy_audit (
 command_id uuid PRIMARY KEY, policy_id uuid NOT NULL REFERENCES pf.quota_policy(id), expected_version bigint NOT NULL,
 operator_id text NOT NULL, request_hash text NOT NULL, before_json jsonb NOT NULL, after_json jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- Protected acceptance-only meter. Production admission never reads this table.
CREATE TABLE pf.acceptance_run (
 id uuid PRIMARY KEY, stage text NOT NULL CHECK(stage='R4.1'), physical_requests integer NOT NULL DEFAULT 0 CHECK(physical_requests BETWEEN 0 AND 20),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pf.acceptance_run_call (
 call_id uuid PRIMARY KEY REFERENCES pf.model_call(id), run_id uuid NOT NULL REFERENCES pf.acceptance_run(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Historical calls remain accounted for on upgrade; never reset known usage or UNKNOWN holds.
INSERT INTO pf.quota_bucket(user_id,metric,window_start,window_end)
SELECT DISTINCT t.creator_id,p.metric,date_trunc('day',m.created_at AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai',
 (date_trunc('day',m.created_at AT TIME ZONE 'Asia/Shanghai')+interval '1 day') AT TIME ZONE 'Asia/Shanghai'
FROM pf.model_call m JOIN pf.task t ON t.id=m.task_id JOIN pf.budget_reservation r ON r.call_id=m.id
CROSS JOIN pf.quota_policy p WHERE p.subject_user_id IS NULL ON CONFLICT DO NOTHING;
INSERT INTO pf.quota_reservation(call_id,metric,user_id,window_start,policy_id,policy_version,reserved,actual,unlimited,status)
SELECT m.id,v.metric,t.creator_id,date_trunc('day',m.created_at AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai',p.id,p.version,v.reserved,
 CASE WHEN r.state='RELEASED' THEN 0 WHEN r.state='SETTLED' AND v.actual IS NOT NULL THEN v.actual ELSE NULL END,
 u.platform_admin OR p.limit_value IS NULL,
 CASE WHEN r.state='RELEASED' THEN 'RELEASED' WHEN r.state='SETTLED' AND v.actual IS NOT NULL THEN 'SETTLED'
      WHEN r.state='RESERVED' AND m.status='PREPARED' THEN 'RESERVED' ELSE 'UNKNOWN' END
FROM pf.model_call m JOIN pf.task t ON t.id=m.task_id JOIN pf.app_user u ON u.id=t.creator_id JOIN pf.budget_reservation r ON r.call_id=m.id
CROSS JOIN LATERAL(VALUES ('TOKENS',r.tokens,m.input_tokens+m.output_tokens),('COST_MICROS',r.amount_micros,m.cost_micros)) v(metric,reserved,actual)
JOIN pf.quota_policy p ON p.metric=v.metric AND p.subject_user_id IS NULL;
UPDATE pf.quota_bucket b SET reserved=x.reserved,consumed=x.consumed FROM (
 SELECT user_id,metric,window_start,coalesce(sum(reserved) FILTER(WHERE status IN ('RESERVED','UNKNOWN')),0) AS reserved,
 coalesce(sum(actual) FILTER(WHERE status='SETTLED'),0) AS consumed
 FROM pf.quota_reservation GROUP BY user_id,metric,window_start
) x WHERE b.user_id=x.user_id AND b.metric=x.metric AND b.window_start=x.window_start;
