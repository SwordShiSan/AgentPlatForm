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
 status text NOT NULL DEFAULT 'RESERVED' CHECK(status IN('RESERVED','CAPTURING','COMMITTED','FAILED','RECONCILING','RELEASED')),
 file_operation_id uuid UNIQUE, file_id uuid, version_id uuid, actual_bytes bigint CHECK(actual_bytes>=0 AND actual_bytes<=reserved_bytes),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 UNIQUE(operation_id,ordinal), UNIQUE(operation_id,relative_path),
 FOREIGN KEY(space_id,task_id,workspace_id,operation_id) REFERENCES pf.workspace_tool_intent(space_id,task_id,workspace_id,operation_id),
 FOREIGN KEY(space_id,workspace_id,file_operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 CHECK(status<>'COMMITTED' OR(file_operation_id IS NOT NULL AND file_id IS NOT NULL AND version_id IS NOT NULL AND actual_bytes IS NOT NULL))
);
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
 LANGUAGE plpgsql SET search_path=pg_catalog,pf AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  UPDATE pf.workspace SET content_revision=content_revision+1 WHERE space_id=OLD.space_id AND id=OLD.workspace_id;
 ELSE
  UPDATE pf.workspace SET content_revision=content_revision+1 WHERE space_id=NEW.space_id AND id=NEW.workspace_id;
 END IF;
 RETURN NULL;
END $$;
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
