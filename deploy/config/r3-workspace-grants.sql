-- Run after V10 using the migration operator; roles must already exist.
-- Never grant the runner memberships in ap_api/ap_worker or broad schema privileges.
GRANT USAGE ON SCHEMA pf TO ap_api,ap_worker,ap_runner;
REVOKE ALL ON pf.app_user,pf.space,pf.membership,pf.space_storage,pf.credential,pf.message,pf.task,pf_runtime.state_slot FROM ap_runner;
-- Table-level revocation does not replace explicit column revocation on an older candidate.
REVOKE SELECT(id,status) ON pf.app_user FROM ap_runner;
REVOKE SELECT(id,type,owner_user_id) ON pf.space FROM ap_runner;
REVOKE SELECT(user_id,space_id,status,role) ON pf.membership FROM ap_runner;
REVOKE SELECT(space_id,status) ON pf.space_storage FROM ap_runner;
GRANT EXECUTE ON FUNCTION pf.authorize_workspace_actor(uuid,uuid) TO ap_api,ap_worker,ap_runner;
GRANT SELECT,INSERT,UPDATE ON pf.workspace_capacity,pf.workspace,pf.workspace_operation,
    pf.workspace_reservation,pf.workspace_idempotency,pf.workspace_outbox TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.workspace_inbox,pf.workspace_event,pf.workspace_quarantine TO ap_api,ap_worker;
GRANT SELECT ON pf.runner_command,pf.runner_receipt TO ap_api,ap_worker;

GRANT SELECT ON pf.workspace_capacity,pf.workspace,pf.workspace_operation TO ap_runner;
-- PostgreSQL SELECT FOR UPDATE requires UPDATE on at least one column.
-- Only lock/version columns are granted, never lifecycle state, lease, owner or fence.
GRANT UPDATE(revision) ON pf.workspace_capacity TO ap_runner;
GRANT UPDATE(version) ON pf.workspace,pf.workspace_operation TO ap_runner;
GRANT SELECT,INSERT ON pf.runner_command,pf.runner_receipt TO ap_runner;
GRANT UPDATE(state,result_revision) ON pf.runner_command TO ap_runner;
-- No credential, message, SDK-state, task or chat-history privileges.
