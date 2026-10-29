-- Apply after V19, in addition to existing R3/R4 grants. No financial writes for runner.
GRANT SELECT ON pf.file_version_origin,pf.artifact TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.file_mutation_intent,pf.file_mutation_item,pf.file_version_origin TO ap_api,ap_worker;
GRANT SELECT ON pf.file_mutation_intent,pf.file_mutation_item TO ap_runner;
GRANT SELECT,INSERT ON pf.file_restore_upload TO ap_api,ap_worker;
GRANT SELECT ON pf.file_restore_upload TO ap_runner;
GRANT SELECT,INSERT ON pf.file_tree_restore_item TO ap_api,ap_worker;
GRANT SELECT,INSERT,UPDATE ON pf.file_tree_reservation TO ap_api,ap_worker;
GRANT SELECT ON pf.file_tree_restore_item TO ap_runner;
REVOKE ALL ON pf.artifact,pf.file_version_origin FROM ap_runner;
GRANT EXECUTE ON FUNCTION pf.queue_file_delete_cleanup(uuid,uuid,uuid,uuid) TO ap_worker;
