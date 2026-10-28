-- Local R4.1 opt-in only; do not apply to production R3 during this small stage.
BEGIN;
GRANT SELECT,INSERT,UPDATE ON pf.task_plan,pf.task_interaction,pf.control_input,pf.interaction_resume TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.plan_revision,pf.interaction_answer,pf.control_input_consumption TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.interaction_resume_binding TO ap_worker;
-- API resume projections use immutable identities only, never private SDK state.
GRANT SELECT ON pf.interaction_resume_binding TO ap_api;
-- Immutable table triggers reject mutation, including for the migration owner.
GRANT SELECT ON pf.quota_policy TO ap_api,ap_worker;
GRANT SELECT,INSERT ON pf.quota_principal TO ap_api,ap_worker;
GRANT UPDATE(user_id) ON pf.quota_principal TO ap_api,ap_worker;
GRANT SELECT,INSERT,UPDATE ON pf.quota_bucket TO ap_api,ap_worker;
GRANT SELECT,INSERT,UPDATE ON pf.quota_reservation TO ap_worker;
GRANT SELECT,INSERT,UPDATE ON pf.acceptance_run TO ap_worker;
GRANT SELECT,INSERT ON pf.acceptance_run_call TO ap_worker;
GRANT SELECT ON pf.quota_reservation TO ap_api;
GRANT INSERT,UPDATE ON pf.checkpoint_recovery TO ap_api;
-- API only sees checkpoint identity metadata already granted by R2; no state/manifests.
-- Quota policy mutation/audit uses the trusted chat-ops migration/operator role, never API/worker.
COMMIT;
