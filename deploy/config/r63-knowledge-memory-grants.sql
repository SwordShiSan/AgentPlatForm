-- R6.3 least-privilege runtime grants. Apply with the protected role-bootstrap channel.
GRANT SELECT,INSERT,UPDATE ON pf.knowledge_base,pf.knowledge_document,pf.knowledge_index_version,
 pf.knowledge_index_build,pf.knowledge_index_outbox,pf.knowledge_mutation_request TO ap_api;
GRANT SELECT ON pf.knowledge_index_chunk TO ap_api;
GRANT SELECT,INSERT,UPDATE ON pf.personal_memory TO ap_api;
GRANT SELECT,INSERT ON pf.personal_memory_audit,pf.memory_mutation_request TO ap_api;
GRANT SELECT ON pf.task_context_projection,pf.task_context_projection_current TO ap_api;
GRANT EXECUTE ON FUNCTION pf.authorize_workspace_actor(uuid,uuid) TO ap_api;

GRANT SELECT,INSERT,UPDATE ON pf.knowledge_document,pf.knowledge_index_version,pf.knowledge_index_build,pf.knowledge_index_outbox TO ap_worker;
GRANT SELECT,INSERT ON pf.knowledge_index_chunk,pf.knowledge_index_inbox,pf.knowledge_index_outbox TO ap_worker;
GRANT INSERT ON pf.knowledge_index_delivery_quarantine TO ap_worker;
GRANT SELECT,INSERT ON pf.knowledge_mutation_request,pf.memory_mutation_request,pf.personal_memory_audit TO ap_worker;
GRANT UPDATE ON pf.personal_memory TO ap_worker;
GRANT SELECT ON pf.knowledge_base,pf.personal_memory,pf.file_version,pf.chat_attachment,pf.document_extraction,pf.asset_object TO ap_worker;
GRANT INSERT,UPDATE ON pf.task_context_projection,pf.task_context_projection_current TO ap_worker;
GRANT SELECT ON pf.task_context_projection,pf.task_context_projection_current TO ap_worker;
GRANT EXECUTE ON FUNCTION pf.authorize_workspace_actor(uuid,uuid) TO ap_worker;
