-- Apply after V18 and the earlier R1/R2/R3/R4 grants, using the migration owner.
-- Production use is gated by the R4 release; this file alone enables no tools.
BEGIN;
GRANT SELECT,INSERT ON pf.task_tool_catalog,pf.plan_execution_acceptance TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.workspace_tool_intent,pf.workspace_tool_job_binding,pf.workspace_tool_file_admission TO ap_worker;
GRANT SELECT,INSERT ON pf.workspace_tool_shell_admission,pf.workspace_tool_stop_request TO ap_worker;
GRANT SELECT,INSERT,UPDATE ON pf.tool_output_capture,pf.tool_result_object TO ap_worker;
GRANT SELECT,INSERT,UPDATE ON pf.tool_result_reservation TO ap_worker;
GRANT EXECUTE ON FUNCTION pf.current_tool_resource_hash(uuid,uuid,uuid,uuid) TO ap_api,ap_worker;
GRANT EXECUTE ON FUNCTION pf.file_tool_parent_allowed(uuid,uuid,boolean) TO ap_api,ap_worker,ap_runner;
GRANT EXECUTE ON FUNCTION pf.workspace_shell_admission_allowed(uuid,uuid,uuid,jsonb) TO ap_worker;
GRANT EXECUTE ON FUNCTION pf.workspace_shell_parent_allowed(uuid,boolean) TO ap_worker,ap_runner;
GRANT EXECUTE ON FUNCTION pf.workspace_tool_public_operations(uuid,uuid,uuid,uuid,integer) TO ap_api,ap_worker;
GRANT EXECUTE ON FUNCTION pf.workspace_tool_public_result(uuid,uuid,uuid) TO ap_api,ap_worker;
-- Runner can evaluate the parent fence predicate without reading chat or keys.
REVOKE ALL ON pf.workspace_tool_intent,pf.workspace_tool_job_binding,pf.workspace_tool_file_admission,
    pf.task_tool_catalog,pf.plan_execution_acceptance,pf.tool_output_capture,pf.tool_result_object,
    pf.workspace_tool_shell_admission,pf.workspace_tool_stop_request FROM ap_runner;
REVOKE ALL ON pf.tool_result_reservation FROM ap_runner;
COMMIT;
