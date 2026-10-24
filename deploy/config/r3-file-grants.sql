-- Apply after V13 and existing workspace/environment grants, using the protected owner role.
GRANT USAGE ON SCHEMA pf TO ap_api,ap_worker,ap_runner;
-- Quota reservation/settlement also takes SELECT FOR UPDATE on this existing row.
-- Remove any historical table-wide UPDATE before granting only the counters/version.
REVOKE UPDATE ON pf.space_storage FROM ap_api,ap_worker;
GRANT UPDATE(reserved_bytes,committed_bytes,version) ON pf.space_storage TO ap_api,ap_worker;
GRANT SELECT ON pf.file_transfer_slot TO ap_api,ap_worker,ap_runner;
GRANT UPDATE(owner_id,object_key_hash,process_role,process_id,started_at,revision) ON pf.file_transfer_slot TO ap_api,ap_worker,ap_runner;
GRANT SELECT,INSERT ON pf.file_transfer_release TO ap_api,ap_worker,ap_runner;
GRANT SELECT,INSERT,UPDATE ON pf.file_entry,pf.asset_object,pf.upload_session,pf.object_reservation,
    pf.file_operation,pf.workspace_file_lease,pf.file_outbox TO ap_api,ap_worker;
GRANT SELECT,INSERT,UPDATE ON pf.file_scan_directory TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.file_version,pf.asset_reference,pf.file_inbox,pf.file_event,
    pf.file_delivery_quarantine TO ap_api,ap_worker;
GRANT SELECT ON pf.file_runner_command,pf.file_runner_receipt TO ap_api,ap_worker;

-- Runner receives metadata and narrow physical facts, never financial or ownership write permissions.
GRANT SELECT ON pf.file_entry,pf.file_version,pf.asset_object,pf.upload_session,pf.file_operation,
    pf.workspace_file_lease,pf.file_runner_command,pf.file_runner_receipt TO ap_runner;
GRANT UPDATE(revision) ON pf.file_operation,pf.workspace_file_lease TO ap_runner;
GRANT INSERT ON pf.file_runner_command,pf.file_runner_receipt TO ap_runner;
GRANT UPDATE(container_id,start_requested,started_at,exec_id) ON pf.file_runner_command TO ap_runner;
REVOKE ALL ON pf.credential,pf.message,pf.task,pf_runtime.state_slot FROM ap_runner;
-- Private S3 transport credentials are mounted only in the approved service roles, never in a file helper.
