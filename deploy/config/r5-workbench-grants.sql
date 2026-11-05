-- Apply after V21 and after creating the named runtime roles. Neither API nor worker
-- receives runner socket privileges; runner receives no chat/model credential access.
REVOKE ALL ON pf.observed_usage,pf.chat_attachment,pf.chat_attachment_upload,pf.chat_attachment_reservation,
 pf.document_extraction,pf.document_extraction_request,pf.document_extraction_reservation,
 pf.document_extraction_outbox,pf.document_extraction_inbox,pf.document_extraction_attempt,
 pf.document_extraction_read_exec,pf.document_extraction_event,pf.task_attachment FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON pf.chat_attachment,pf.chat_attachment_upload,pf.chat_attachment_reservation TO ap_api;
GRANT SELECT,INSERT,UPDATE ON pf.document_extraction,pf.document_extraction_request,pf.document_extraction_reservation,
 pf.document_extraction_outbox,pf.document_extraction_event TO ap_api;
GRANT SELECT,INSERT ON pf.task_attachment TO ap_api;
GRANT SELECT ON pf.chat_attachment,pf.chat_attachment_upload,pf.task_attachment TO ap_worker;
GRANT SELECT,INSERT,UPDATE ON pf.document_extraction,pf.document_extraction_reservation,pf.document_extraction_outbox,
 pf.document_extraction_inbox,pf.document_extraction_attempt,pf.document_extraction_read_exec,pf.document_extraction_event TO ap_worker;
GRANT SELECT,INSERT ON pf.observed_usage TO ap_worker;
GRANT SELECT ON pf.chat_attachment TO ap_runner;
GRANT SELECT,UPDATE ON pf.document_extraction_attempt TO ap_runner;
GRANT SELECT,INSERT ON pf.document_extraction_read_exec TO ap_runner;
GRANT SELECT ON pf.document_extraction TO ap_runner;
GRANT SELECT,INSERT ON pf.document_extraction_asset_reference,pf.document_extraction_delivery_quarantine TO ap_worker;
GRANT SELECT,INSERT ON pf.attachment_submission TO ap_api;
GRANT SELECT ON pf.task_attachment,pf.document_extraction_asset_reference TO ap_api;
