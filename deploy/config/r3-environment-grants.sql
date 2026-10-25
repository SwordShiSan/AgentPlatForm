-- Apply after V12 and r3-workspace-grants.sql. Create these roles privately first.
GRANT USAGE ON SCHEMA pf TO ap_api,ap_worker,ap_runner,ap_workspace_ops;
GRANT SELECT,INSERT,UPDATE ON pf.workspace_sandbox_slot,pf.workspace_environment,
    pf.workspace_environment_operation,pf.workspace_environment_idempotency,pf.workspace_environment_outbox TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.workspace_environment_inbox,pf.workspace_environment_event TO ap_api,ap_worker;
GRANT SELECT ON pf.workspace_environment_command,pf.workspace_environment_receipt,
    pf.workspace_environment_stop_receipt TO ap_api,ap_worker;
REVOKE SELECT ON pf.workspace_environment_use,pf.workspace_environment_use_result FROM ap_api;
GRANT SELECT(id,environment_id,status,kind) ON pf.workspace_environment_use TO ap_api;
GRANT SELECT,INSERT,UPDATE ON pf.workspace_environment_use TO ap_worker;
GRANT SELECT ON pf.workspace_environment_use_result,pf.workspace_runner_instance,pf.workspace_runner_stop_proof TO ap_worker;
GRANT INSERT ON pf.workspace_environment_stop_receipt TO ap_worker;

GRANT SELECT ON pf.workspace_sandbox_slot,pf.workspace_environment,pf.workspace_environment_operation,
    pf.workspace_environment_use,pf.workspace_runner_instance,pf.workspace_runner_stop_proof TO ap_runner;
-- Only lock columns, never lifecycle/lease/owner/fence or a physical identity.
GRANT UPDATE(revision) ON pf.workspace_sandbox_slot TO ap_runner;
GRANT UPDATE(version) ON pf.workspace_environment,pf.workspace_environment_operation TO ap_runner;
GRANT SELECT,INSERT ON pf.workspace_runner_instance,pf.workspace_environment_command,
    pf.workspace_environment_receipt,pf.workspace_environment_stop_receipt TO ap_runner;
GRANT UPDATE(state,result_revision,result_runner_instance_id) ON pf.workspace_environment_command TO ap_runner;
GRANT SELECT,INSERT ON pf.workspace_environment_use_result TO ap_runner;
GRANT SELECT ON pf.workspace_file_helper,pf.workspace_file_helper_stop TO ap_runner;
GRANT INSERT(use_id,space_id,workspace_id,environment_id,owner_id,generation,fence,runner_instance_id,image_id,policy_hash,container_name,intent_hash)
    ON pf.workspace_file_helper TO ap_runner;
GRANT UPDATE(container_id,start_requested,started_at,exec_id,version) ON pf.workspace_file_helper TO ap_runner;
GRANT INSERT(use_id,observer_runner_id,prior_runner_proof_id,container_id,image_id,started_at,exec_id,never_started,finished_at,
    exec_stopped,callbacks_stopped,stop_text,content_hash,observed_at) ON pf.workspace_file_helper_stop TO ap_runner;
GRANT UPDATE(file_execution_mode,version) ON pf.workspace_environment_use TO ap_runner;
REVOKE UPDATE(status) ON pf.workspace_environment_use FROM ap_runner;
GRANT UPDATE(runner_instance_id,exec_id,version) ON pf.workspace_environment_use TO ap_runner;

-- The proof importer has metadata/lock access only; no user content or runtime secret access.
GRANT SELECT ON pf.workspace_sandbox_slot,pf.workspace_runner_instance,pf.workspace_runner_stop_proof TO ap_workspace_ops;
GRANT UPDATE(revision) ON pf.workspace_sandbox_slot TO ap_workspace_ops;
GRANT UPDATE(registered_at) ON pf.workspace_runner_instance TO ap_workspace_ops;
GRANT INSERT ON pf.workspace_runner_stop_proof TO ap_workspace_ops;
REVOKE INSERT,UPDATE,DELETE ON pf.workspace_runner_stop_proof FROM ap_api,ap_worker,ap_runner;
REVOKE ALL ON pf.app_user,pf.membership,pf.credential,pf.message,pf.task,pf_runtime.state_slot FROM ap_workspace_ops;

-- V14 cleanup is append-only runner evidence; lifecycle projection stays with worker.
GRANT SELECT ON pf.workspace_environment_cleanup TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.workspace_environment_cleanup TO ap_runner;
