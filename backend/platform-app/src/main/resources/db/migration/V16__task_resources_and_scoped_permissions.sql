-- Additive R4.2. Historical task/configuration/checkpoint bytes are not rewritten.
CREATE TABLE pf.task_resource_binding (
 task_id uuid PRIMARY KEY, space_id uuid NOT NULL, actor_id uuid NOT NULL REFERENCES pf.app_user(id),
 workspace_id uuid, root_scope_id uuid NOT NULL UNIQUE,
 initial_mode text NOT NULL CHECK(initial_mode IN ('RISK_BASED','FULL')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,task_id), UNIQUE(space_id,task_id,workspace_id),
 FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id),
 FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id)
);
CREATE TRIGGER task_resource_binding_immutable BEFORE UPDATE OR DELETE ON pf.task_resource_binding
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.task_permission (
 task_id uuid PRIMARY KEY, space_id uuid NOT NULL,
 mode text NOT NULL CHECK(mode IN ('RISK_BASED','FULL')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 policy_version text NOT NULL DEFAULT 'task-directory-policy-v1',
 FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id)
);
CREATE TABLE pf.task_directory_grant (
 id uuid PRIMARY KEY, task_id uuid NOT NULL, space_id uuid NOT NULL, workspace_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES pf.app_user(id), scope_id uuid NOT NULL,
 directory_path text NOT NULL CHECK(octet_length(directory_path)<=4096),
 capabilities jsonb NOT NULL CHECK(jsonb_typeof(capabilities)='array' AND jsonb_array_length(capabilities) BETWEEN 1 AND 3
   AND capabilities <@ '["LIST","READ","WRITE"]'::jsonb),
 policy_version text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL CHECK(expires_at>created_at AND expires_at<=created_at+interval '24 hours'),
 revoked_at timestamptz, UNIQUE(space_id,task_id,id),
 FOREIGN KEY(space_id,task_id,workspace_id) REFERENCES pf.task_resource_binding(space_id,task_id,workspace_id)
);
CREATE INDEX task_directory_grant_active ON pf.task_directory_grant(task_id,expires_at) WHERE revoked_at IS NULL;
CREATE TABLE pf.task_permission_command (
 task_id uuid NOT NULL, space_id uuid NOT NULL, actor_id uuid NOT NULL REFERENCES pf.app_user(id),
 command_id uuid NOT NULL, request_hash text NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 response_json jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(space_id,actor_id,task_id,command_id), FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id)
);
CREATE TRIGGER task_permission_command_immutable BEFORE UPDATE OR DELETE ON pf.task_permission_command
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.task_permission_audit (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES pf.app_user(id), attempt_id uuid REFERENCES pf.attempt(id),
 operation_id uuid REFERENCES pf.tool_operation(id), call_id text,
 decision text NOT NULL CHECK(decision IN ('ALLOW','ASK','DENY','MODE_CHANGED','GRANT_CREATED','GRANT_REVOKED')),
 reason_code text NOT NULL, policy_version text NOT NULL, permission_version bigint NOT NULL,
 arguments_hash text, trace_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id)
);
CREATE INDEX task_permission_audit_task ON pf.task_permission_audit(space_id,task_id,created_at);
CREATE TRIGGER task_permission_audit_immutable BEFORE UPDATE OR DELETE ON pf.task_permission_audit
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
