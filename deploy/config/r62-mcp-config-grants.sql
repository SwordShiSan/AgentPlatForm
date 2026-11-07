-- Connector configuration and probe admission; credentials remain in the separate protected table.
GRANT SELECT,INSERT ON pf.capability TO ap_api;
GRANT SELECT,INSERT,UPDATE ON pf.mcp_connector_configuration TO ap_api;
GRANT SELECT,INSERT ON pf.mcp_connector_request,pf.mcp_probe,pf.mcp_probe_outbox TO ap_api;
GRANT INSERT ON pf.capability_audit TO ap_api;
