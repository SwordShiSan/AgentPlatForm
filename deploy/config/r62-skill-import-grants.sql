-- Import admission and queue; object bytes remain in private storage.
GRANT SELECT ON pf.capability TO ap_api;
GRANT UPDATE(kind) ON pf.capability TO ap_api; -- needed for the row share lock
GRANT SELECT,INSERT,UPDATE ON pf.skill_import,pf.skill_import_reservation,pf.skill_import_outbox TO ap_api;
GRANT SELECT,INSERT ON pf.skill_import_completion_request TO ap_api;
