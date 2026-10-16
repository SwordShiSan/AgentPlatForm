CREATE TABLE pf.credential (
 id uuid PRIMARY KEY, scope_type text NOT NULL CHECK(scope_type IN ('PLATFORM','USER')),
 space_id uuid REFERENCES pf.space(id), owner_user_id uuid REFERENCES pf.app_user(id),
 ciphertext bytea NOT NULL CHECK(octet_length(ciphertext)>16), nonce bytea NOT NULL CHECK(octet_length(nonce)=12), key_version integer NOT NULL DEFAULT 1 CHECK(key_version>0),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')), revision bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id),
 CHECK((scope_type='PLATFORM' AND space_id IS NULL AND owner_user_id IS NULL) OR
       (scope_type='USER' AND space_id IS NOT NULL AND owner_user_id IS NOT NULL))
);
CREATE TABLE pf.model_definition (
 id uuid PRIMARY KEY, model_name text NOT NULL, endpoint_alias text NOT NULL CHECK(endpoint_alias='DEEPSEEK'),
 status text NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 1,
 input_micros_per_million bigint NOT NULL DEFAULT 2000000, output_micros_per_million bigint NOT NULL DEFAULT 8000000,
 pricing_version text NOT NULL DEFAULT 'deepseek-flash-2026-10-16-peak-bound', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION pf.authorize_chat_actor(actor uuid, selected_space uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pf AS $$
BEGIN
 PERFORM u.id FROM pf.app_user u JOIN pf.membership m ON m.user_id=u.id JOIN pf.space s ON s.id=m.space_id
 WHERE u.id=actor AND m.space_id=selected_space AND u.status='ACTIVE' AND m.status='ACTIVE'
 AND s.type='PERSONAL' AND s.owner_user_id=u.id FOR SHARE OF u,m;
 RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION pf.authorize_chat_actor(uuid,uuid) FROM PUBLIC;
CREATE FUNCTION pf.authorize_chat_credential(selected_id uuid, selected_revision bigint, selected_space uuid, actor uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pf AS $$
BEGIN
 PERFORM k.id FROM pf.credential k WHERE k.id=selected_id AND k.revision=selected_revision AND k.status='ACTIVE'
 AND ((k.scope_type='PLATFORM' AND k.space_id IS NULL AND k.owner_user_id IS NULL)
 OR (k.scope_type='USER' AND k.space_id=selected_space AND k.owner_user_id=actor)) FOR SHARE;
 RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION pf.authorize_chat_credential(uuid,bigint,uuid,uuid) FROM PUBLIC;
CREATE TABLE pf.model_binding (
 space_id uuid NOT NULL REFERENCES pf.space(id), user_id uuid NOT NULL REFERENCES pf.app_user(id),
 model_id uuid NOT NULL REFERENCES pf.model_definition(id), credential_id uuid NOT NULL REFERENCES pf.credential(id),
 source_policy text NOT NULL CHECK(source_policy IN ('PLATFORM','USER')), version bigint NOT NULL DEFAULT 1,
 PRIMARY KEY(space_id,user_id,model_id), FOREIGN KEY(space_id,user_id) REFERENCES pf.membership(space_id,user_id)
);
CREATE TABLE pf.budget_account (
 space_id uuid NOT NULL, user_id uuid NOT NULL, token_limit bigint NOT NULL DEFAULT 1000000,
 cost_limit_micros bigint NOT NULL DEFAULT 1000000, reserved_tokens bigint NOT NULL DEFAULT 0,
 consumed_tokens bigint NOT NULL DEFAULT 0, reserved_micros bigint NOT NULL DEFAULT 0, consumed_micros bigint NOT NULL DEFAULT 0,
 version bigint NOT NULL DEFAULT 1, PRIMARY KEY(space_id,user_id),
 FOREIGN KEY(space_id,user_id) REFERENCES pf.membership(space_id,user_id),
 CHECK(reserved_tokens>=0 AND consumed_tokens>=0 AND reserved_micros>=0 AND consumed_micros>=0),
 CHECK(reserved_tokens+consumed_tokens<=token_limit AND reserved_micros+consumed_micros<=cost_limit_micros)
);
CREATE TABLE pf.conversation (
 id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES pf.space(id), creator_id uuid NOT NULL REFERENCES pf.app_user(id),
 workspace_id uuid CHECK(workspace_id IS NULL), title text NOT NULL, next_seq bigint NOT NULL DEFAULT 0,
 version bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(space_id,id), FOREIGN KEY(space_id,creator_id) REFERENCES pf.membership(space_id,user_id)
);
CREATE INDEX conversation_list ON pf.conversation(space_id,updated_at,id);
CREATE TABLE pf.runtime_bundle (
 id uuid PRIMARY KEY, contract_version text NOT NULL, manifest_hash text NOT NULL UNIQUE, manifest_json jsonb NOT NULL
);
CREATE TABLE pf.chat_config_snapshot (
 id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES pf.space(id), model_id uuid NOT NULL REFERENCES pf.model_definition(id),
 credential_id uuid NOT NULL REFERENCES pf.credential(id), credential_revision bigint NOT NULL,
 config_json jsonb NOT NULL, config_hash text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(space_id,id)
);
CREATE TABLE pf.task (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, conversation_id uuid NOT NULL, creator_id uuid NOT NULL,
 status text NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','BLOCKED','SUCCEEDED','FAILED','CANCELLED')),
 config_snapshot_id uuid NOT NULL, bundle_id uuid NOT NULL REFERENCES pf.runtime_bundle(id),
 cancel_requested boolean NOT NULL DEFAULT false, reason_code text, version bigint NOT NULL DEFAULT 1,
 reserved_tokens bigint NOT NULL DEFAULT 36864, reserved_micros bigint NOT NULL DEFAULT 98304,
 remaining_tokens bigint NOT NULL DEFAULT 36864, remaining_micros bigint NOT NULL DEFAULT 98304,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(space_id,id), FOREIGN KEY(space_id,conversation_id) REFERENCES pf.conversation(space_id,id),
 FOREIGN KEY(space_id,config_snapshot_id) REFERENCES pf.chat_config_snapshot(space_id,id),
 FOREIGN KEY(space_id,creator_id) REFERENCES pf.membership(space_id,user_id)
);
CREATE INDEX task_order ON pf.task(conversation_id,created_at,id);
CREATE TABLE pf.message (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, conversation_id uuid NOT NULL, task_id uuid NOT NULL,
 role text NOT NULL CHECK(role IN ('USER','ASSISTANT')), blocks_json jsonb NOT NULL,
 status text NOT NULL CHECK(status IN ('COMPLETED','STREAMING','PARTIAL','FAILED')),
 version bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(space_id,id), UNIQUE(task_id,role), FOREIGN KEY(space_id,conversation_id) REFERENCES pf.conversation(space_id,id),
 FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id)
);
CREATE TABLE pf.execution_dispatch (
 task_id uuid PRIMARY KEY REFERENCES pf.task(id), generation bigint NOT NULL DEFAULT 1,
 status text NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','CLAIMED','DEFERRED','IDLE')),
 available_at timestamptz NOT NULL DEFAULT now(), lease_owner uuid, lease_until timestamptz, fencing_token bigint NOT NULL DEFAULT 0
);
CREATE TABLE pf.execution_slot (
 id integer PRIMARY KEY CHECK(id=1), task_id uuid REFERENCES pf.task(id), owner_id uuid,
 fencing_token bigint NOT NULL DEFAULT 0, lease_until timestamptz
);
INSERT INTO pf.execution_slot(id) VALUES(1);
CREATE TABLE pf.attempt (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, ordinal integer NOT NULL,
 owner_id uuid NOT NULL, fencing_token bigint NOT NULL, status text NOT NULL DEFAULT 'RUNNING',
 started_at timestamptz NOT NULL DEFAULT now(), ended_at timestamptz, reason_code text,
 UNIQUE(task_id,ordinal), FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id)
);
CREATE TABLE pf.platform_event (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, conversation_id uuid NOT NULL, task_id uuid,
 seq bigint NOT NULL, type text NOT NULL, schema_version integer NOT NULL DEFAULT 1,
 entity_version bigint NOT NULL, payload_json jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(conversation_id,seq), FOREIGN KEY(space_id,conversation_id) REFERENCES pf.conversation(space_id,id),
 FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id)
);
CREATE TABLE pf.event_outbox (
 id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES pf.space(id), event_id uuid NOT NULL UNIQUE,
 topic text NOT NULL DEFAULT 'execution.ready', aggregate_id uuid NOT NULL REFERENCES pf.task(id), generation bigint NOT NULL,
 status text NOT NULL DEFAULT 'PENDING', available_at timestamptz NOT NULL DEFAULT now(), publish_owner uuid,
 publish_lease_until timestamptz, attempts integer NOT NULL DEFAULT 0, last_error text, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_due ON pf.event_outbox(status,available_at,publish_lease_until);
CREATE TABLE pf.consumer_inbox (
 consumer_name text NOT NULL, event_id uuid NOT NULL, space_id uuid NOT NULL REFERENCES pf.space(id),
 aggregate_id uuid NOT NULL REFERENCES pf.task(id), generation bigint NOT NULL, disposition text NOT NULL,
 processed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(consumer_name,event_id)
);
CREATE TABLE pf.dispatch_quarantine (id uuid PRIMARY KEY, reason_code text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE pf.idempotency_record (
 user_id uuid NOT NULL REFERENCES pf.app_user(id), space_id uuid NOT NULL REFERENCES pf.space(id),
 method text NOT NULL, canonical_path text NOT NULL, key uuid NOT NULL, body_hash text NOT NULL,
 resource_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,space_id,method,canonical_path,key)
);
CREATE TABLE pf.model_call (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL, attempt_id uuid NOT NULL REFERENCES pf.attempt(id),
 ordinal integer NOT NULL, status text NOT NULL DEFAULT 'STARTED', provider_request_id text,
 input_tokens bigint, output_tokens bigint, cost_micros bigint, estimated boolean NOT NULL DEFAULT true,
 price_version text NOT NULL, error_code text, created_at timestamptz NOT NULL DEFAULT now(), ended_at timestamptz,
 UNIQUE(task_id,ordinal), FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id)
);
CREATE TABLE pf.budget_reservation (
 call_id uuid PRIMARY KEY REFERENCES pf.model_call(id), task_id uuid NOT NULL REFERENCES pf.task(id),
 tokens bigint NOT NULL DEFAULT 9216, amount_micros bigint NOT NULL DEFAULT 24576,
 state text NOT NULL DEFAULT 'RESERVED' CHECK(state IN ('RESERVED','SETTLED','RELEASED','RECONCILING'))
);
CREATE TABLE pf.credential_access_audit (
 id uuid PRIMARY KEY, credential_id uuid NOT NULL REFERENCES pf.credential(id), call_id uuid NOT NULL REFERENCES pf.model_call(id),
 endpoint_alias text NOT NULL DEFAULT 'DEEPSEEK', result text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE pf.acceptance_model_meter (
 id integer PRIMARY KEY CHECK(id=1), physical_calls integer NOT NULL DEFAULT 0,
 reserved_micros bigint NOT NULL DEFAULT 0, consumed_micros bigint NOT NULL DEFAULT 0,
 CHECK(physical_calls BETWEEN 0 AND 20), CHECK(reserved_micros>=0 AND consumed_micros>=0 AND reserved_micros+consumed_micros<=1000000)
);
INSERT INTO pf.acceptance_model_meter(id) VALUES(1);
