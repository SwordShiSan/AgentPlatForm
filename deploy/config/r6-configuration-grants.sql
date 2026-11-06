-- API owns settings mutations. Workers consume frozen task snapshots only.
GRANT SELECT, INSERT, UPDATE ON pf.workspace_configuration TO ap_api;
-- Clearing an override removes its row while retaining the shared preference revision.
GRANT DELETE ON pf.user_preference TO ap_api;
GRANT EXECUTE ON FUNCTION pf.lock_r61_model(uuid) TO ap_api;
-- PostgreSQL row-level sharing locks require an UPDATE privilege on a column.
GRANT UPDATE(version) ON pf.workspace TO ap_api;
