CREATE TABLE pf.user_preference_revision (
 user_id uuid PRIMARY KEY REFERENCES pf.app_user(id), version bigint NOT NULL DEFAULT 0 CHECK(version>=0)
);
CREATE TABLE pf.user_preference (
 user_id uuid NOT NULL REFERENCES pf.app_user(id), key text NOT NULL CHECK(key IN ('defaultModelId','defaultCredentialSource')),
 value_json jsonb NOT NULL, version bigint NOT NULL, PRIMARY KEY(user_id,key)
);
CREATE TABLE pf.user_model_credential (
 space_id uuid NOT NULL, user_id uuid NOT NULL, provider text NOT NULL CHECK(provider='DEEPSEEK'),
 credential_id uuid REFERENCES pf.credential(id), version bigint NOT NULL DEFAULT 0,
 PRIMARY KEY(space_id,user_id,provider), FOREIGN KEY(space_id,user_id) REFERENCES pf.membership(space_id,user_id)
);
CREATE TABLE pf.chat_audit (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, user_id uuid NOT NULL, kind text NOT NULL,
 resource_id uuid NOT NULL, trace_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(space_id,user_id) REFERENCES pf.membership(space_id,user_id)
);
CREATE INDEX chat_audit_resource ON pf.chat_audit(space_id,resource_id,created_at);
-- PLATFORM grants remain model_binding; USER selection is a separate owner-scoped pointer.
INSERT INTO pf.user_model_credential(space_id,user_id,provider,credential_id,version)
SELECT DISTINCT ON(b.space_id,b.user_id) b.space_id,b.user_id,'DEEPSEEK',b.credential_id,1
FROM pf.model_binding b JOIN pf.credential k ON k.id=b.credential_id
WHERE b.source_policy='USER' AND k.scope_type='USER' AND k.space_id=b.space_id AND k.owner_user_id=b.user_id AND k.status='ACTIVE'
ORDER BY b.space_id,b.user_id,k.created_at DESC,k.id DESC;
