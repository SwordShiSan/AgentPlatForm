-- Additive R4.3. Original checkpoints and operation facts are never changed by an answer.
ALTER TABLE pf.task_interaction DROP CONSTRAINT task_interaction_kind_check;
ALTER TABLE pf.task_interaction ADD CONSTRAINT task_interaction_kind_check CHECK(kind IN ('PLAN_APPROVAL','QUESTION','TOOL_APPROVAL'));
ALTER TABLE pf.task_interaction ADD CONSTRAINT tool_approval_is_required CHECK(kind<>'TOOL_APPROVAL' OR NOT optional);
ALTER TABLE pf.interaction_answer DROP CONSTRAINT interaction_answer_decision_check;
ALTER TABLE pf.interaction_answer ADD CONSTRAINT interaction_answer_decision_check
 CHECK(decision IN ('EXECUTE_PLAN','REVISE_PLAN','REJECT_PLAN','DELIVER_PLAN','ANSWER','SKIP','ALLOW_ONCE','DENY'));
ALTER TABLE pf.checkpoint DROP CONSTRAINT checkpoint_phase_check;
ALTER TABLE pf.checkpoint ADD CONSTRAINT checkpoint_phase_check
 CHECK(phase IN ('LEGACY','INPUT_READY','MODEL_COMMITTED','TOOL_COMMITTED','WAITING_INPUT','WAITING_APPROVAL','TERMINAL'));

CREATE TABLE pf.tool_approval_binding (
 interaction_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES pf.app_user(id), source_attempt_id uuid NOT NULL,
 operation_id uuid NOT NULL, call_id text NOT NULL, call_hash text NOT NULL CHECK(call_hash ~ '^[a-f0-9]{64}$'),
 fingerprint text NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'), workspace_id uuid,
 resource_hash text NOT NULL CHECK(resource_hash ~ '^[a-f0-9]{64}$'), permission_version bigint NOT NULL CHECK(permission_version>0),
 policy_version text NOT NULL, summary text NOT NULL CHECK(octet_length(summary)<=4096), reason_code text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,task_id,interaction_id),
 FOREIGN KEY(space_id,task_id,interaction_id) REFERENCES pf.task_interaction(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,source_attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,operation_id) REFERENCES pf.tool_operation(space_id,task_id,id),
 FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id)
);
CREATE INDEX tool_approval_call ON pf.tool_approval_binding(space_id,task_id,call_id,created_at DESC);
CREATE INDEX tool_approval_fingerprint ON pf.tool_approval_binding(space_id,task_id,fingerprint);
CREATE TRIGGER tool_approval_binding_immutable BEFORE UPDATE OR DELETE ON pf.tool_approval_binding
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.tool_approval_consumption (
 operation_id uuid PRIMARY KEY, interaction_id uuid NOT NULL, answer_id uuid NOT NULL,
 space_id uuid NOT NULL, task_id uuid NOT NULL, attempt_id uuid NOT NULL, fence bigint NOT NULL CHECK(fence>0),
 outcome text NOT NULL CHECK(outcome IN ('ALLOW_ONCE','DENY','STALE_DENIED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id,operation_id) REFERENCES pf.tool_operation(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,interaction_id) REFERENCES pf.tool_approval_binding(space_id,task_id,interaction_id),
 FOREIGN KEY(space_id,task_id,answer_id) REFERENCES pf.interaction_answer(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id)
);
CREATE TRIGGER tool_approval_consumption_immutable BEFORE UPDATE OR DELETE ON pf.tool_approval_consumption
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

-- Locking a selected workspace must not require granting UPDATE on the workspace table.
CREATE FUNCTION pf.lock_approval_workspace(actor uuid, selected_space uuid, selected_workspace uuid) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
DECLARE result text;
BEGIN
 IF NOT pf.authorize_workspace_actor(actor,selected_space) THEN RETURN NULL; END IF;
 SELECT jsonb_build_object('id',w.id,'actor',w.actor_id,'space',w.space_id,'state',w.state,'version',w.version,'projectId',w.project_id)::text
 INTO result FROM pf.workspace w WHERE w.id=selected_workspace AND w.space_id=selected_space AND w.actor_id=actor FOR SHARE OF w;
 RETURN result;
END $$;
REVOKE ALL ON FUNCTION pf.lock_approval_workspace(uuid,uuid,uuid) FROM PUBLIC;
