-- Local R4.3 opt-in only. Answers cannot mutate operations; worker owns consumption and results.
BEGIN;
GRANT SELECT ON pf.tool_approval_binding,pf.tool_approval_consumption TO ap_api;
GRANT SELECT(id,space_id,task_id,call_id,call_hash,status) ON pf.tool_operation TO ap_api;
GRANT SELECT,INSERT ON pf.tool_approval_binding,pf.tool_approval_consumption TO ap_worker;
GRANT EXECUTE ON FUNCTION pf.lock_approval_workspace(uuid,uuid,uuid) TO ap_worker;
COMMIT;
