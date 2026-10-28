-- Additive R4.4; existing tasks, configuration hashes and checkpoint bytes are not rewritten.
ALTER TABLE pf.tool_operation DROP CONSTRAINT tool_operation_status_check;
ALTER TABLE pf.tool_operation ADD CONSTRAINT tool_operation_status_check
 CHECK(status IN('PREPARED','RUNNING','SUCCEEDED','FAILED','UNKNOWN','DENIED'));
ALTER TABLE pf.workspace ADD COLUMN content_revision bigint NOT NULL DEFAULT 0 CHECK(content_revision>=0);
ALTER TABLE pf.file_entry DROP CONSTRAINT file_entry_collection_state_check;
ALTER TABLE pf.file_entry ADD CONSTRAINT file_entry_collection_state_check
 CHECK(collection_state IN('PENDING','CONFIRMED','CHANGED','MISSING','BLOCKED'));

-- Only new, explicitly enabled tasks receive this immutable catalog. No historic backfill.
ALTER TABLE pf.task_resource_binding ADD CONSTRAINT task_resource_actor_identity UNIQUE(space_id,task_id,actor_id,workspace_id);
CREATE TABLE pf.task_tool_catalog (
 task_id uuid PRIMARY KEY, space_id uuid NOT NULL, workspace_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES pf.app_user(id), catalog_version text NOT NULL,
 runtime_bundle_id uuid NOT NULL REFERENCES pf.runtime_bundle(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,task_id,workspace_id), UNIQUE(space_id,task_id,actor_id,workspace_id),
 FOREIGN KEY(space_id,task_id,actor_id,workspace_id) REFERENCES pf.task_resource_binding(space_id,task_id,actor_id,workspace_id)
);
CREATE TRIGGER task_tool_catalog_immutable BEFORE UPDATE OR DELETE ON pf.task_tool_catalog
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.plan_execution_acceptance (
 task_id uuid NOT NULL, space_id uuid NOT NULL, revision_id uuid NOT NULL,
 answer_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(task_id,revision_id), UNIQUE(answer_id),
 FOREIGN KEY(space_id,task_id,revision_id) REFERENCES pf.plan_revision(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,answer_id) REFERENCES pf.interaction_answer(space_id,task_id,id)
);
CREATE TRIGGER plan_execution_acceptance_immutable BEFORE UPDATE OR DELETE ON pf.plan_execution_acceptance
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

CREATE TABLE pf.workspace_tool_intent (
 operation_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, workspace_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES pf.app_user(id),
 tool_name text NOT NULL CHECK(tool_name IN('workspace_list','workspace_read','workspace_write','workspace_shell')),
 catalog_version text NOT NULL, args_hash text NOT NULL CHECK(args_hash ~ '^[a-f0-9]{64}$'),
 content_revision bigint NOT NULL CHECK(content_revision>=0),
 permission_version bigint NOT NULL CHECK(permission_version>0), policy_version text NOT NULL,
 resource_hash text NOT NULL CHECK(resource_hash ~ '^[a-f0-9]{64}$'),
 resource_json jsonb NOT NULL CHECK(octet_length(resource_json::text)<=16384),
 review_json jsonb NOT NULL CHECK(octet_length(review_json::text)<=65536),
 activation_request_id uuid UNIQUE, expected_environment_version bigint CHECK(expected_environment_version>=0),
 expected_image_id text, expected_policy_hash text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,task_id,workspace_id,operation_id),
 FOREIGN KEY(space_id,task_id,operation_id) REFERENCES pf.tool_operation(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,actor_id,workspace_id) REFERENCES pf.task_tool_catalog(space_id,task_id,actor_id,workspace_id),
 CHECK((tool_name='workspace_shell')=(activation_request_id IS NOT NULL)),
 CHECK(tool_name<>'workspace_shell' OR(expected_environment_version IS NOT NULL
   AND expected_image_id IS NOT NULL AND expected_policy_hash IS NOT NULL
   AND expected_image_id ~ '^sha256:[a-f0-9]{64}$' AND expected_policy_hash ~ '^[a-f0-9]{64}$'))
);
CREATE TRIGGER workspace_tool_intent_immutable BEFORE UPDATE OR DELETE ON pf.workspace_tool_intent
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

-- Immutable links to existing child authorities; no second outbox or job status authority.
CREATE TABLE pf.workspace_tool_job_binding (
 id uuid PRIMARY KEY, operation_id uuid NOT NULL, space_id uuid NOT NULL, task_id uuid NOT NULL, workspace_id uuid NOT NULL,
 environment_id uuid, activation_operation_id uuid UNIQUE, environment_use_id uuid UNIQUE, file_operation_id uuid UNIQUE,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id,workspace_id,operation_id) REFERENCES pf.workspace_tool_intent(space_id,task_id,workspace_id,operation_id),
 FOREIGN KEY(space_id,workspace_id,environment_id,activation_operation_id) REFERENCES pf.workspace_environment_operation(space_id,workspace_id,environment_id,id),
 FOREIGN KEY(space_id,workspace_id,environment_id,environment_use_id) REFERENCES pf.workspace_environment_use(space_id,workspace_id,environment_id,id),
 FOREIGN KEY(space_id,workspace_id,file_operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id),
 CHECK(num_nonnulls(activation_operation_id,environment_use_id,file_operation_id)=1),
 CHECK((activation_operation_id IS NULL AND environment_use_id IS NULL) OR environment_id IS NOT NULL)
);
CREATE TRIGGER workspace_tool_job_binding_immutable BEFORE UPDATE OR DELETE ON pf.workspace_tool_job_binding
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

CREATE TABLE pf.tool_output_capture (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, workspace_id uuid NOT NULL, operation_id uuid NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal BETWEEN 0 AND 15), relative_path text NOT NULL CHECK(octet_length(relative_path) BETWEEN 1 AND 4096),
 reserved_bytes bigint NOT NULL CHECK(reserved_bytes BETWEEN 0 AND 104857600),
 reserved_version_id uuid NOT NULL UNIQUE, asset_id uuid NOT NULL UNIQUE, object_key text NOT NULL UNIQUE,
 status text NOT NULL DEFAULT 'RESERVED' CHECK(status IN('RESERVED','CAPTURING','COMMITTED','FAILED','RECONCILING','RELEASED')),
 file_operation_id uuid UNIQUE, file_id uuid, version_id uuid, actual_bytes bigint CHECK(actual_bytes>=0 AND actual_bytes<=reserved_bytes),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 UNIQUE(operation_id,ordinal), UNIQUE(operation_id,relative_path),
 FOREIGN KEY(space_id,task_id,workspace_id,operation_id) REFERENCES pf.workspace_tool_intent(space_id,task_id,workspace_id,operation_id),
 FOREIGN KEY(space_id,workspace_id,file_operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 CHECK(status<>'COMMITTED' OR(file_operation_id IS NOT NULL AND file_id IS NOT NULL AND version_id=reserved_version_id AND actual_bytes IS NOT NULL)),
 FOREIGN KEY(space_id,version_id,asset_id) REFERENCES pf.asset_object(space_id,version_id,id),
 CHECK(object_key LIKE 'spaces/'||space_id::text||'/file-version/'||asset_id::text||'/'||reserved_version_id::text||'/%')
);
CREATE TABLE pf.tool_result_reservation (
 operation_id uuid PRIMARY KEY,space_id uuid NOT NULL,task_id uuid NOT NULL,workspace_id uuid NOT NULL,
 reserved_bytes bigint NOT NULL CHECK(reserved_bytes=65536),actual_bytes bigint CHECK(actual_bytes BETWEEN 0 AND reserved_bytes),
 status text NOT NULL DEFAULT 'RESERVED' CHECK(status IN('RESERVED','COMMITTED','RELEASED','RECONCILING')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 FOREIGN KEY(space_id,task_id,workspace_id,operation_id) REFERENCES pf.workspace_tool_intent(space_id,task_id,workspace_id,operation_id)
);
REVOKE ALL ON pf.tool_result_reservation FROM PUBLIC;
CREATE FUNCTION pf.guard_tool_capture_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(OLD.id,OLD.operation_id,OLD.space_id,OLD.task_id,OLD.workspace_id,OLD.ordinal,OLD.relative_path,OLD.reserved_bytes,OLD.reserved_version_id,OLD.asset_id,OLD.object_key)
 IS DISTINCT FROM ROW(NEW.id,NEW.operation_id,NEW.space_id,NEW.task_id,NEW.workspace_id,NEW.ordinal,NEW.relative_path,NEW.reserved_bytes,NEW.reserved_version_id,NEW.asset_id,NEW.object_key)
 THEN RAISE EXCEPTION 'CAPTURE_IDENTITY_IMMUTABLE' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER tool_capture_identity BEFORE UPDATE ON pf.tool_output_capture FOR EACH ROW EXECUTE FUNCTION pf.guard_tool_capture_identity();
CREATE TRIGGER tool_capture_no_delete BEFORE DELETE ON pf.tool_output_capture FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.tool_result_object (
 operation_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, workspace_id uuid NOT NULL,
 object_key text NOT NULL UNIQUE, sha256 text NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
 size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 0 AND 65536),
 stdout_bytes integer NOT NULL CHECK(stdout_bytes>=0), stderr_bytes integer NOT NULL CHECK(stderr_bytes>=0),
 reserved_bytes bigint NOT NULL CHECK(reserved_bytes BETWEEN 0 AND 65536),
 status text NOT NULL CHECK(status IN('STAGING','AVAILABLE','RECONCILING')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id,workspace_id,operation_id) REFERENCES pf.workspace_tool_intent(space_id,task_id,workspace_id,operation_id),
 CHECK(object_key LIKE 'spaces/'||space_id::text||'/tool-result/'||operation_id::text||'/%'),
 CHECK(size_bytes=stdout_bytes::bigint+stderr_bytes::bigint),
 CHECK(status<>'AVAILABLE' OR size_bytes<=reserved_bytes)
);

-- These transitions are projected only by the new versioned runner protocol.
ALTER TABLE pf.workspace_environment_use DROP CONSTRAINT workspace_environment_use_kind_check;
ALTER TABLE pf.workspace_environment_use ADD CONSTRAINT workspace_environment_use_kind_check
 CHECK(kind IN('FILE_READ','FILE_WRITE','FILE_LIST','FIXED_PROBE','SHELL'));
ALTER TABLE pf.file_operation DROP CONSTRAINT file_operation_kind_check;
ALTER TABLE pf.file_operation ADD CONSTRAINT file_operation_kind_check CHECK(kind IN('APPLY_UPLOAD','SCAN','CAPTURE'));

CREATE FUNCTION pf.advance_workspace_content_revision() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  UPDATE pf.workspace SET content_revision=content_revision+1 WHERE space_id=OLD.space_id AND id=OLD.workspace_id;
 ELSE
  UPDATE pf.workspace SET content_revision=content_revision+1 WHERE space_id=NEW.space_id AND id=NEW.workspace_id;
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION pf.advance_workspace_content_revision() FROM PUBLIC;
CREATE TRIGGER file_content_insert AFTER INSERT ON pf.file_entry
 FOR EACH ROW EXECUTE FUNCTION pf.advance_workspace_content_revision();
CREATE TRIGGER file_content_change AFTER UPDATE ON pf.file_entry FOR EACH ROW
 WHEN (ROW(OLD.current_version_id,OLD.collection_state,OLD.observed_sha256,OLD.observed_bytes,OLD.relative_path)
   IS DISTINCT FROM ROW(NEW.current_version_id,NEW.collection_state,NEW.observed_sha256,NEW.observed_bytes,NEW.relative_path))
 EXECUTE FUNCTION pf.advance_workspace_content_revision();
CREATE TRIGGER file_content_remove AFTER DELETE ON pf.file_entry
 FOR EACH ROW EXECUTE FUNCTION pf.advance_workspace_content_revision();
REVOKE ALL ON pf.task_tool_catalog,pf.plan_execution_acceptance,pf.workspace_tool_intent,pf.workspace_tool_job_binding,
 pf.tool_output_capture,pf.tool_result_object FROM PUBLIC;

-- Public approval validation returns only an opaque hash. Private call parameters
-- and environment/job details never need to be granted to the API role.
CREATE FUNCTION pf.current_tool_resource_hash(actor uuid,selected_space uuid,selected_task uuid,selected_operation uuid)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
DECLARE result text;
BEGIN
 IF NOT pf.authorize_workspace_actor(actor,selected_space) THEN RETURN NULL; END IF;
 SELECT i.resource_hash INTO result FROM pf.workspace_tool_intent i
 JOIN pf.task t ON t.space_id=i.space_id AND t.id=i.task_id AND t.creator_id=i.actor_id
 JOIN pf.task_permission p ON p.space_id=i.space_id AND p.task_id=i.task_id
 JOIN pf.workspace w ON w.space_id=i.space_id AND w.id=i.workspace_id AND w.actor_id=i.actor_id
 LEFT JOIN pf.workspace_environment e ON e.space_id=w.space_id AND e.workspace_id=w.id
 WHERE i.space_id=selected_space AND i.task_id=selected_task AND i.operation_id=selected_operation AND i.actor_id=actor
 AND w.state='READY' AND w.content_revision=i.content_revision
 AND p.version=i.permission_version AND p.policy_version=i.policy_version
 AND NOT t.cancel_requested AND NOT t.plan_mode AND t.status IN('QUEUED','RUNNING','WAITING_INPUT')
 AND (NOT EXISTS(SELECT 1 FROM pf.task_plan tp WHERE tp.space_id=i.space_id AND tp.task_id=i.task_id)
   OR EXISTS(SELECT 1 FROM pf.task_plan tp JOIN pf.plan_revision pr ON pr.plan_id=tp.id AND pr.revision=tp.current_revision
     JOIN pf.plan_execution_acceptance pa ON pa.space_id=tp.space_id AND pa.task_id=tp.task_id AND pa.revision_id=pr.id
     WHERE tp.space_id=i.space_id AND tp.task_id=i.task_id AND tp.status='APPROVED'))
 AND (i.tool_name<>'workspace_shell' OR coalesce(e.version,0)=i.expected_environment_version
   OR EXISTS(SELECT 1 FROM pf.workspace_tool_job_binding jb
     JOIN pf.workspace_environment_operation op ON op.space_id=jb.space_id AND op.workspace_id=jb.workspace_id
       AND op.environment_id=jb.environment_id AND op.id=jb.activation_operation_id
     WHERE jb.operation_id=i.operation_id AND jb.space_id=i.space_id AND jb.task_id=i.task_id
       AND e.id=jb.environment_id AND e.operation_id=op.id AND e.image_id=i.expected_image_id AND e.policy_hash=i.expected_policy_hash));
 RETURN result;
END $$;
REVOKE ALL ON FUNCTION pf.current_tool_resource_hash(uuid,uuid,uuid,uuid) FROM PUBLIC;

-- The original tool owner admits the upload in the same transaction as RUNNING.
-- This is a link and immutable admission outcome, never another file job status.
CREATE TABLE pf.workspace_tool_file_admission (
 operation_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, workspace_id uuid NOT NULL,
 attempt_id uuid NOT NULL, owner_id uuid NOT NULL, generation bigint NOT NULL CHECK(generation>0), fence bigint NOT NULL CHECK(fence>0),
 upload_id uuid UNIQUE REFERENCES pf.upload_session(id), failure_code text CHECK(failure_code ~ '^[A-Z][A-Z0-9_]{1,95}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id,workspace_id,operation_id) REFERENCES pf.workspace_tool_intent(space_id,task_id,workspace_id,operation_id),
 FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
 CHECK(num_nonnulls(upload_id,failure_code)=1)
);
CREATE TRIGGER workspace_tool_file_admission_immutable BEFORE UPDATE OR DELETE ON pf.workspace_tool_file_admission
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
REVOKE ALL ON pf.workspace_tool_file_admission FROM PUBLIC;

CREATE TABLE pf.workspace_tool_shell_admission (
 operation_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, workspace_id uuid NOT NULL,
 attempt_id uuid NOT NULL, owner_id uuid NOT NULL, task_generation bigint NOT NULL CHECK(task_generation>0), task_fence bigint NOT NULL CHECK(task_fence>0),
 environment_id uuid NOT NULL, use_id uuid NOT NULL UNIQUE, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id,workspace_id,operation_id) REFERENCES pf.workspace_tool_intent(space_id,task_id,workspace_id,operation_id),
 FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id),
 FOREIGN KEY(space_id,workspace_id,environment_id,use_id) REFERENCES pf.workspace_environment_use(space_id,workspace_id,environment_id,id)
);
CREATE TRIGGER workspace_tool_shell_admission_immutable BEFORE UPDATE OR DELETE ON pf.workspace_tool_shell_admission
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.workspace_tool_stop_request (
 operation_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, workspace_id uuid NOT NULL,
 attempt_id uuid NOT NULL, owner_id uuid NOT NULL, task_fence bigint NOT NULL CHECK(task_fence>0),
 reason_code text NOT NULL CHECK(reason_code IN('CANCEL','TIMEOUT','AUTHORIZATION','OWNER_LOST')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id,workspace_id,operation_id) REFERENCES pf.workspace_tool_intent(space_id,task_id,workspace_id,operation_id),
 FOREIGN KEY(space_id,task_id,attempt_id) REFERENCES pf.attempt(space_id,task_id,id)
);
CREATE TRIGGER workspace_tool_stop_request_immutable BEFORE UPDATE OR DELETE ON pf.workspace_tool_stop_request
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
REVOKE ALL ON pf.workspace_tool_shell_admission,pf.workspace_tool_stop_request FROM PUBLIC;

-- Evaluate an already admitted operation without revealing private task or key data.
-- Physical environment generation/fence remain independent of these task tokens.
CREATE FUNCTION pf.workspace_tool_owner_allowed(selected_operation uuid,selected_attempt uuid,selected_owner uuid,
 selected_generation bigint,selected_fence bigint,take_locks boolean)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
DECLARE intent pf.workspace_tool_intent; parent pf.task;
BEGIN
 SELECT * INTO intent FROM pf.workspace_tool_intent WHERE operation_id=selected_operation;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT * INTO parent FROM pf.task WHERE space_id=intent.space_id AND id=intent.task_id;
 IF take_locks THEN
  PERFORM 1 FROM pf.quota_principal WHERE user_id=intent.actor_id FOR UPDATE;
  PERFORM 1 FROM pf.space_storage WHERE space_id=intent.space_id FOR UPDATE;
  PERFORM 1 FROM pf.execution_slot WHERE id=1 FOR UPDATE;
  PERFORM 1 FROM pf.conversation WHERE space_id=parent.space_id AND id=parent.conversation_id FOR UPDATE;
  PERFORM 1 FROM pf.task WHERE space_id=parent.space_id AND id=parent.id FOR UPDATE;
  PERFORM 1 FROM pf.execution_dispatch WHERE task_id=parent.id FOR UPDATE;
  PERFORM 1 FROM pf.attempt WHERE id=selected_attempt FOR UPDATE;
  PERFORM 1 FROM pf.conversation_lease WHERE space_id=parent.space_id AND conversation_id=parent.conversation_id FOR UPDATE;
 END IF;
 RETURN pf.authorize_workspace_actor(intent.actor_id,intent.space_id)
 AND NOT EXISTS(SELECT 1 FROM pf.workspace_tool_stop_request s WHERE s.operation_id=intent.operation_id)
 AND EXISTS(SELECT 1 FROM pf.workspace w WHERE w.space_id=intent.space_id AND w.id=intent.workspace_id AND w.actor_id=intent.actor_id AND w.state='READY')
 AND EXISTS(SELECT 1 FROM pf.task t JOIN pf.task_permission p ON p.space_id=t.space_id AND p.task_id=t.id
   JOIN pf.chat_config_snapshot cfg ON cfg.space_id=t.space_id AND cfg.id=t.config_snapshot_id
   JOIN pf.model_definition m ON m.id=cfg.model_id AND m.status='ACTIVE'
   WHERE t.space_id=intent.space_id AND t.id=intent.task_id AND t.creator_id=intent.actor_id
   AND t.status='RUNNING' AND NOT t.cancel_requested AND NOT t.plan_mode AND t.execution_deadline_at>clock_timestamp()
   AND p.version=intent.permission_version AND p.policy_version=intent.policy_version
   AND pf.authorize_chat_credential(cfg.credential_id,cfg.credential_revision,t.space_id,t.creator_id))
 AND (NOT EXISTS(SELECT 1 FROM pf.task_plan tp WHERE tp.space_id=intent.space_id AND tp.task_id=intent.task_id)
   OR EXISTS(SELECT 1 FROM pf.task_plan tp JOIN pf.plan_revision pr ON pr.plan_id=tp.id AND pr.revision=tp.current_revision
     JOIN pf.plan_execution_acceptance a ON a.space_id=tp.space_id AND a.task_id=tp.task_id AND a.revision_id=pr.id
     WHERE tp.space_id=intent.space_id AND tp.task_id=intent.task_id AND tp.status='APPROVED'))
 AND EXISTS(SELECT 1 FROM pf.execution_slot s WHERE s.id=1 AND s.task_id=intent.task_id AND s.owner_id=selected_owner
   AND s.fencing_token=selected_fence AND s.lease_until>clock_timestamp())
 AND EXISTS(SELECT 1 FROM pf.execution_dispatch d JOIN pf.attempt a ON a.task_id=d.task_id
   JOIN pf.conversation_lease v ON v.attempt_id=a.id
   WHERE d.task_id=intent.task_id AND d.generation=selected_generation AND d.status='CLAIMED'
   AND d.lease_owner=selected_owner AND d.fencing_token=selected_fence AND d.lease_until>clock_timestamp()
   AND a.id=selected_attempt AND a.space_id=intent.space_id AND a.owner_id=selected_owner AND a.fencing_token=selected_fence
   AND a.dispatch_generation=selected_generation AND a.status='RUNNING' AND v.task_id=intent.task_id
   AND v.owner_id=selected_owner AND v.fencing_token=selected_fence AND v.generation=selected_generation AND v.lease_until>clock_timestamp())
 AND EXISTS(SELECT 1 FROM pf.tool_operation o WHERE o.id=intent.operation_id AND o.space_id=intent.space_id
   AND o.task_id=intent.task_id AND o.status='RUNNING' AND o.call_hash=intent.args_hash);
END $$;
REVOKE ALL ON FUNCTION pf.workspace_tool_owner_allowed(uuid,uuid,uuid,bigint,bigint,boolean) FROM PUBLIC;

-- Public/historical file operations retain their own guards. Tool children share
-- the same parent deadline, stop intent, authorization and task ownership gate.
CREATE FUNCTION pf.file_tool_parent_allowed(selected_upload uuid,selected_operation uuid,take_locks boolean)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
DECLARE admission pf.workspace_tool_file_admission;
BEGIN
 SELECT a.* INTO admission FROM pf.workspace_tool_file_admission a
 WHERE a.upload_id=selected_upload OR EXISTS(SELECT 1 FROM pf.workspace_tool_job_binding b
   WHERE b.operation_id=a.operation_id AND b.file_operation_id=selected_operation);
 IF NOT FOUND THEN RETURN true; END IF;
 RETURN pf.workspace_tool_owner_allowed(admission.operation_id,admission.attempt_id,admission.owner_id,
   admission.generation,admission.fence,take_locks);
END $$;
REVOKE ALL ON FUNCTION pf.file_tool_parent_allowed(uuid,uuid,boolean) FROM PUBLIC;


CREATE FUNCTION pf.workspace_shell_admission_allowed(actor uuid,selected_space uuid,selected_workspace uuid,request jsonb)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
DECLARE p jsonb; intent pf.workspace_tool_intent;
BEGIN
 IF request->>'protocol'<>'WORKSPACE_SHELL_V1' THEN RETURN false; END IF;
 p=request->'parent';SELECT * INTO intent FROM pf.workspace_tool_intent WHERE operation_id=(p->>'operationId')::uuid;
 IF NOT FOUND OR intent.tool_name<>'workspace_shell' OR intent.space_id<>selected_space OR intent.workspace_id<>selected_workspace
   OR intent.actor_id<>actor OR intent.task_id<>(p->>'taskId')::uuid OR intent.args_hash<>p->>'argumentsHash'
   OR intent.content_revision<>(p->>'contentRevision')::bigint OR intent.permission_version<>(p->>'permissionVersion')::bigint THEN RETURN false; END IF;
 IF NOT pf.workspace_tool_owner_allowed(intent.operation_id,(p->>'attemptId')::uuid,(p->>'ownerId')::uuid,
   (p->>'taskGeneration')::bigint,(p->>'taskFence')::bigint,true) THEN RETURN false; END IF;
 RETURN pf.current_tool_resource_hash(actor,selected_space,intent.task_id,intent.operation_id) IS NOT NULL
 AND EXISTS(SELECT 1 FROM pf.tool_operation o WHERE o.id=intent.operation_id
   AND o.call_json#>'{arguments,command}'=request#>'{shell,command}'
   AND o.call_json#>'{arguments,cwd}'=request#>'{shell,cwd}'
   AND o.call_json#>'{arguments,timeoutSeconds}'=request#>'{shell,timeoutSeconds}')
 AND EXISTS(SELECT 1 FROM pf.workspace_environment e WHERE e.space_id=selected_space AND e.workspace_id=selected_workspace
   AND e.state='READY' AND e.image_id=intent.expected_image_id AND e.policy_hash=intent.expected_policy_hash);
END $$;
REVOKE ALL ON FUNCTION pf.workspace_shell_admission_allowed(uuid,uuid,uuid,jsonb) FROM PUBLIC;

CREATE FUNCTION pf.workspace_shell_parent_allowed(selected_use uuid,take_locks boolean)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
DECLARE a pf.workspace_tool_shell_admission;
BEGIN
 SELECT s.* INTO a FROM pf.workspace_tool_shell_admission s JOIN pf.workspace_environment_use u ON u.id=s.use_id
  JOIN pf.workspace_tool_job_binding b ON b.operation_id=s.operation_id AND b.environment_use_id=u.id
  WHERE s.use_id=selected_use AND u.kind='SHELL' AND u.space_id=s.space_id AND u.workspace_id=s.workspace_id
    AND u.environment_id=s.environment_id AND u.owner_id=s.owner_id;
 IF NOT FOUND THEN RETURN false; END IF;
 RETURN pf.workspace_tool_owner_allowed(a.operation_id,a.attempt_id,a.owner_id,a.task_generation,a.task_fence,take_locks);
END $$;
REVOKE ALL ON FUNCTION pf.workspace_shell_parent_allowed(uuid,boolean) FROM PUBLIC;

-- The API role gets this allowlisted projection, never the private intent,
-- call_json/result_json, physical identity or SDK state tables.
CREATE FUNCTION pf.workspace_tool_public_operations(actor uuid,selected_space uuid,selected_task uuid,before_id uuid,page_size integer)
RETURNS TABLE(operation_id uuid,task_id uuid,tool_name text,status text,phase text,summary text,
 created_at timestamptz,ended_at timestamptz,error_code text,trace_id text,review_json text,result_available boolean)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
 SELECT o.id,o.task_id,i.tool_name,o.status,
 CASE WHEN o.status='PREPARED' AND t.status='WAITING_INPUT' THEN 'WAITING_APPROVAL'
      WHEN o.status='RUNNING' AND EXISTS(SELECT 1 FROM pf.workspace_tool_stop_request s WHERE s.operation_id=o.id) THEN 'STOPPING'
      WHEN o.status='RUNNING' AND EXISTS(SELECT 1 FROM pf.tool_output_capture c WHERE c.operation_id=o.id AND c.status='CAPTURING') THEN 'SEALING'
      WHEN o.status='RUNNING' AND EXISTS(SELECT 1 FROM pf.workspace_tool_job_binding b JOIN pf.workspace_environment_operation e ON e.id=b.activation_operation_id
        WHERE b.operation_id=o.id AND e.status IN('PENDING','CLAIMED','RUNNING')) THEN 'ENVIRONMENT_PREPARING'
      ELSE o.status END,
 CASE i.tool_name WHEN 'workspace_list' THEN '列举工作区文件' WHEN 'workspace_read' THEN '读取明确文件版本'
      WHEN 'workspace_write' THEN '写入并封存文件' ELSE '执行工作区命令' END,
 o.created_at,o.ended_at,
 CASE o.status WHEN 'UNKNOWN' THEN 'TOOL_RESULT_UNKNOWN' WHEN 'FAILED' THEN 'TOOL_EXECUTION_FAILED' ELSE NULL END,
 NULL::text,
 jsonb_build_object('kind',CASE i.tool_name WHEN 'workspace_list' THEN 'LIST' WHEN 'workspace_read' THEN 'READ' WHEN 'workspace_write' THEN 'WRITE' ELSE 'SHELL' END,
  'command',i.review_json->'command','cwd',i.review_json->'cwd','path',i.review_json->'path',
  'fileId',i.review_json->'fileId','versionId',i.review_json->'versionId','writeMode',i.review_json->'mode',
  'textSha256',i.review_json->'textSha256','bytes',i.review_json->'bytes',
  'outputs',coalesce((SELECT jsonb_agg(jsonb_build_object('path',v->'path','maxBytes',v->'maxBytes')) FROM jsonb_array_elements(i.review_json->'outputs') v),'[]'::jsonb),
  'impact',CASE i.tool_name WHEN 'workspace_list' THEN 'WORKSPACE_INDEX' WHEN 'workspace_read' THEN 'EXPLICIT_VERSION' WHEN 'workspace_write' THEN 'NAMED_FILE' ELSE 'ENTIRE_BOUND_WORKSPACE' END,
  'network','NONE','stopsAfterEachCall',i.tool_name='workspace_shell')::text,
 (o.status IN('SUCCEEDED','FAILED') AND o.result_json IS NOT NULL
   AND NOT EXISTS(SELECT 1 FROM pf.tool_result_object r WHERE r.operation_id=o.id AND r.status<>'AVAILABLE'))
 FROM pf.workspace_tool_intent i JOIN pf.tool_operation o ON o.id=i.operation_id AND o.space_id=i.space_id AND o.task_id=i.task_id
 JOIN pf.task t ON t.id=i.task_id AND t.space_id=i.space_id AND t.creator_id=i.actor_id
 WHERE i.actor_id=actor AND i.space_id=selected_space AND i.task_id=selected_task
 AND pf.authorize_workspace_actor(actor,selected_space) AND page_size BETWEEN 1 AND 51
 AND (before_id IS NULL OR o.id<before_id) ORDER BY o.id DESC LIMIT greatest(0,least(page_size,51))
$$;
REVOKE ALL ON FUNCTION pf.workspace_tool_public_operations(uuid,uuid,uuid,uuid,integer) FROM PUBLIC;

CREATE FUNCTION pf.workspace_tool_public_result(actor uuid,selected_space uuid,selected_operation uuid)
RETURNS TABLE(operation_id uuid,task_id uuid,tool_name text,status text,text text,path text,
 object_key text,sha256 text,bytes bigint,stdout_bytes integer,stderr_bytes integer)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
 SELECT o.id,o.task_id,i.tool_name,o.status,
 CASE WHEN o.status IN('SUCCEEDED','FAILED') AND octet_length(o.result_json->>'text')<=8192
   AND (r.operation_id IS NULL OR r.status='AVAILABLE') THEN o.result_json->>'text' ELSE NULL END,
 i.review_json->>'path',r.object_key,r.sha256,r.size_bytes,r.stdout_bytes,r.stderr_bytes
 FROM pf.workspace_tool_intent i JOIN pf.tool_operation o ON o.id=i.operation_id AND o.task_id=i.task_id AND o.space_id=i.space_id
 JOIN pf.task t ON t.id=i.task_id AND t.space_id=i.space_id AND t.creator_id=i.actor_id
 LEFT JOIN pf.tool_result_object r ON r.operation_id=o.id AND r.space_id=o.space_id AND r.task_id=o.task_id
 WHERE i.operation_id=selected_operation AND i.space_id=selected_space AND i.actor_id=actor AND pf.authorize_workspace_actor(actor,selected_space)
$$;
REVOKE ALL ON FUNCTION pf.workspace_tool_public_result(uuid,uuid,uuid) FROM PUBLIC;
