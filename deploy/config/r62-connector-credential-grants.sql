-- API can encrypt and store a new credential, but cannot read ciphertext or nonce back.
GRANT SELECT(id,space_id,owner_user_id,request_id,request_fingerprint,revision,state,created_at)
 ON pf.connector_credential TO ap_api;
GRANT INSERT(id,space_id,owner_user_id,request_id,request_fingerprint,ciphertext,nonce,key_version,state)
 ON pf.connector_credential TO ap_api;
GRANT UPDATE(state,revision) ON pf.connector_credential TO ap_api;
GRANT SELECT,INSERT ON pf.connector_credential_revocation TO ap_api;
GRANT INSERT ON pf.capability_audit TO ap_api;
-- The trusted connector worker reads ciphertext only for a currently authorized call.
GRANT SELECT(id,space_id,owner_user_id,ciphertext,nonce,key_version,revision,state) ON pf.connector_credential TO ap_worker;
