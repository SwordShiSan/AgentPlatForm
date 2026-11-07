-- Read-only discovery, fenced job ownership and immutable version creation.
GRANT SELECT,UPDATE ON pf.mcp_probe,pf.mcp_probe_outbox TO ap_worker;
GRANT SELECT,INSERT ON pf.mcp_probe_inbox TO ap_worker;
GRANT SELECT ON pf.mcp_connector_configuration TO ap_worker;
GRANT UPDATE(revision) ON pf.mcp_connector_configuration TO ap_worker;
GRANT SELECT(id,space_id,owner_user_id,ciphertext,nonce,key_version,revision,state) ON pf.connector_credential TO ap_worker;
GRANT UPDATE(revision) ON pf.connector_credential TO ap_worker;
GRANT SELECT ON pf.capability TO ap_worker;
GRANT UPDATE(kind) ON pf.capability TO ap_worker;
GRANT SELECT,INSERT ON pf.capability_version,pf.mcp_connector_version,pf.mcp_connector_tool,pf.capability_version_status TO ap_worker;
GRANT INSERT ON pf.capability_audit,pf.mcp_probe_quarantine TO ap_worker;
