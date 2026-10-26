-- R4.2 local opt-in. Production R3 is not modified by this file.
BEGIN;
GRANT SELECT,INSERT ON pf.task_resource_binding TO ap_api,ap_worker;
GRANT SELECT,INSERT,UPDATE ON pf.task_permission,pf.task_directory_grant TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.task_permission_command,pf.task_permission_audit TO ap_api,ap_worker;
GRANT SELECT ON pf.workspace,pf.file_entry TO ap_api,ap_worker;
COMMIT;
