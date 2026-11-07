-- Scoped binding control plane only; this does not grant MCP credentials or package bodies.
GRANT SELECT, INSERT, UPDATE ON pf.capability_binding_set TO ap_api;
GRANT SELECT, INSERT, DELETE ON pf.capability_binding_selection TO ap_api;
GRANT SELECT, INSERT ON pf.capability_binding_request,pf.capability_binding_request_selection TO ap_api;
GRANT INSERT ON pf.capability_audit TO ap_api;
GRANT SELECT ON pf.capability_version_status TO ap_api;
GRANT SELECT ON pf.capability,pf.mcp_connector_activation,pf.mcp_connector_version TO ap_api;
GRANT INSERT, UPDATE ON pf.mcp_connector_activation TO ap_api;
GRANT SELECT, INSERT ON pf.mcp_activation_command TO ap_api;
GRANT SELECT ON pf.mcp_connector_tool TO ap_api;
-- PostgreSQL requires an UPDATE privilege for SELECT FOR SHARE.
GRANT UPDATE(revision) ON pf.capability_version_status TO ap_api;
