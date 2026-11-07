-- Skill validation and immutable version settlement; no access to connector secrets.
GRANT SELECT ON pf.space_storage TO ap_worker;
GRANT UPDATE(reserved_bytes,committed_bytes,version) ON pf.space_storage TO ap_worker;
GRANT SELECT,UPDATE ON pf.skill_import,pf.skill_import_reservation,pf.skill_import_outbox TO ap_worker;
GRANT SELECT,INSERT ON pf.skill_import_inbox TO ap_worker;
GRANT SELECT,INSERT ON pf.capability,pf.capability_version,pf.skill_package_version,pf.capability_version_status,pf.asset_object TO ap_worker;
GRANT UPDATE(kind) ON pf.capability TO ap_worker; -- row lock, not a status transition
GRANT INSERT ON pf.capability_audit,pf.skill_import_quarantine TO ap_worker;
