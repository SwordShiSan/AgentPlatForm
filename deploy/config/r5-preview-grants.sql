-- Apply after V20 and r5-file-grants.sql. Real source ACLs remain in authorize_workspace_actor.
GRANT SELECT ON pf.heavy_runtime_slot TO ap_api,ap_worker,ap_runner,ap_workspace_ops;
GRANT UPDATE ON pf.heavy_runtime_slot TO ap_api,ap_worker;
GRANT UPDATE(revision) ON pf.heavy_runtime_slot TO ap_runner,ap_workspace_ops;
GRANT EXECUTE ON FUNCTION pf.lock_heavy_sandbox_slot(),pf.coordinate_heavy_sandbox_slot() TO ap_api,ap_worker,ap_runner,ap_workspace_ops;
GRANT EXECUTE ON FUNCTION pf.reserve_heavy_sandbox_slot(uuid,uuid,uuid,bigint,uuid,bigint) TO ap_api,ap_worker;
GRANT SELECT,INSERT,UPDATE ON pf.preview_job,pf.preview_request,pf.preview_reservation,pf.preview_outbox TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.preview_inbox,pf.preview_asset_reference,pf.preview_event,pf.preview_delivery_quarantine TO ap_api,ap_worker;
GRANT SELECT ON pf.preview_attempt TO ap_api;
GRANT SELECT,INSERT ON pf.preview_attempt TO ap_worker;
GRANT SELECT ON pf.preview_job,pf.preview_attempt,pf.heavy_runtime_slot TO ap_runner;
GRANT UPDATE(runner_id,status,source_closed_at,container_id,start_requested,started_at,exec_id,receipt_json,receipt_hash) ON pf.preview_attempt TO ap_runner;
GRANT SELECT ON pf.preview_read_exec TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.preview_read_exec TO ap_runner;
-- Transport receipts use a real preview command FK. No quota/asset/queue writes for runner.
GRANT SELECT,INSERT ON pf.file_transfer_release TO ap_runner;
REVOKE ALL ON pf.preview_reservation,pf.preview_request,pf.preview_outbox,pf.preview_inbox,pf.preview_asset_reference,pf.preview_event,pf.preview_delivery_quarantine FROM ap_runner;
REVOKE ALL ON pf.credential,pf.message,pf.task,pf_runtime.state_slot FROM ap_runner;
