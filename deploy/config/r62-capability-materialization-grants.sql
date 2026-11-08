-- Runner may inspect only the selected capability metadata and its immutable command receipt.
-- It receives no connector ciphertext, chat body, checkpoint, or SDK state privileges.
GRANT USAGE ON SCHEMA pf TO ap_runner;
GRANT EXECUTE ON FUNCTION pf.authorize_workspace_actor(uuid,uuid) TO ap_runner;
GRANT SELECT(id,type,owner_user_id) ON pf.space TO ap_runner;
GRANT SELECT(id,space_id,creator_id,conversation_id,status) ON pf.task TO ap_runner;
GRANT SELECT(space_id,task_id,actor_id,workspace_id) ON pf.task_resource_binding TO ap_runner;
GRANT SELECT(id,space_id,task_id,owner_id,fencing_token,dispatch_generation,status,ended_at) ON pf.attempt TO ap_runner;
GRANT SELECT(space_id,conversation_id,task_id,attempt_id,owner_id,generation,fencing_token,lease_until) ON pf.conversation_lease TO ap_runner;
GRANT SELECT(task_id,generation,lease_owner,fencing_token,lease_until) ON pf.execution_dispatch TO ap_runner;
GRANT SELECT(id,task_id,owner_id,fencing_token,lease_until) ON pf.execution_slot TO ap_runner;
GRANT SELECT ON pf.capability,pf.capability_binding_set,pf.capability_binding_selection,
    pf.capability_version_status,pf.skill_package_version,pf.asset_object,
    pf.mcp_connector_activation,pf.mcp_connector_version TO ap_runner;
GRANT UPDATE(revision) ON pf.capability_version_status TO ap_runner;
GRANT SELECT(id,space_id,owner_user_id,revision,state) ON pf.connector_credential TO ap_runner;
GRANT SELECT ON pf.capability_materialization_command TO ap_runner;
GRANT INSERT ON pf.capability_materialization_command TO ap_runner;
GRANT UPDATE(state,receipt_json,receipt_hash,error_code,revision,updated_at)
    ON pf.capability_materialization_command TO ap_runner;
-- No permission to read connector credential ciphertext or any chat/task/checkpoint content.
REVOKE SELECT(ciphertext,nonce,key_version) ON pf.connector_credential FROM ap_runner;
REVOKE ALL ON pf_runtime.state_slot,pf.message,pf.checkpoint,pf.tool_operation,pf.model_call FROM ap_runner;
