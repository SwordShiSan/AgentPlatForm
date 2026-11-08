GRANT SELECT ON pf.capability,pf.capability_version,pf.skill_package_version,pf.asset_object TO ap_api;
GRANT SELECT ON pf.capability_version_status TO ap_api;
GRANT UPDATE(state,revision,updated_at) ON pf.capability_version_status TO ap_api;
GRANT SELECT,INSERT ON pf.capability_version_command TO ap_api;
GRANT INSERT ON pf.capability_audit TO ap_api;
