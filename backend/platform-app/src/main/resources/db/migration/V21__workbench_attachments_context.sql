-- R5.4 is additive: existing frozen reservations and runtime bundles remain valid.
ALTER TABLE pf.budget_reservation ADD COLUMN estimated_input_tokens bigint;
ALTER TABLE pf.budget_reservation ADD COLUMN max_output_tokens integer;
ALTER TABLE pf.budget_reservation ADD COLUMN context_policy_version text;
ALTER TABLE pf.budget_reservation ADD CONSTRAINT budget_context_reservation_check CHECK (
 (estimated_input_tokens IS NULL AND max_output_tokens IS NULL AND context_policy_version IS NULL)
 OR (estimated_input_tokens IS NOT NULL AND estimated_input_tokens >= 0
     AND max_output_tokens IS NOT NULL AND max_output_tokens BETWEEN 1 AND 8192 AND context_policy_version IS NOT NULL
     AND length(context_policy_version) BETWEEN 1 AND 128
     AND tokens >= estimated_input_tokens + max_output_tokens));

-- Actual provider usage exceeding a reservation must remain auditable and reconcileable.
CREATE TABLE pf.observed_usage (
 call_id uuid PRIMARY KEY, space_id uuid NOT NULL, task_id uuid NOT NULL,
 input_tokens bigint NOT NULL CHECK(input_tokens>=0), output_tokens bigint NOT NULL CHECK(output_tokens>=0),
 cost_micros bigint NOT NULL CHECK(cost_micros>=0), provider_request_id text,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id,call_id) REFERENCES pf.model_call(space_id,task_id,id)
);
CREATE TRIGGER observed_usage_immutable BEFORE UPDATE OR DELETE ON pf.observed_usage
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
REVOKE ALL ON pf.observed_usage FROM PUBLIC;

ALTER TABLE pf.asset_object DROP CONSTRAINT asset_object_category_check;
ALTER TABLE pf.asset_object ADD CONSTRAINT asset_object_category_check
 CHECK(category IN('file-version','preview','chat-attachment','text-extraction'));

-- An attachment has its own real source identity; no synthetic workspace or file.
CREATE TABLE pf.chat_attachment (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, actor_id uuid NOT NULL,
 asset_id uuid NOT NULL, draft_id uuid NOT NULL, conversation_id uuid,
 filename text NOT NULL CHECK(octet_length(filename) BETWEEN 1 AND 1024),
 size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 0 AND 104857600),
 sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
 status text NOT NULL CHECK(status IN('STAGING','AVAILABLE','FAILED','EXPIRED','BLOCKED')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(space_id,id,asset_id), UNIQUE(space_id,id,actor_id), UNIQUE(space_id,id,sha256,size_bytes),
 FOREIGN KEY(space_id,actor_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY(space_id,id,asset_id) REFERENCES pf.asset_object(space_id,version_id,id),
 FOREIGN KEY(space_id,conversation_id) REFERENCES pf.conversation(space_id,id)
);
CREATE TABLE pf.chat_attachment_upload (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, actor_id uuid NOT NULL, attachment_id uuid NOT NULL,
 request_id uuid NOT NULL, request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 status text NOT NULL CHECK(status IN('CREATED','UPLOADING','VERIFYING','VERIFIED','COMMITTED','ABORTED','EXPIRED','UNKNOWN')),
 transfer_owner uuid, transfer_until timestamptz, revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '24 hours',
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(space_id,actor_id,request_id), UNIQUE(attachment_id),
 FOREIGN KEY(space_id,actor_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY(space_id,attachment_id,actor_id) REFERENCES pf.chat_attachment(space_id,id,actor_id),
 CHECK((status='UPLOADING')=(transfer_owner IS NOT NULL AND transfer_until IS NOT NULL))
);
CREATE TABLE pf.chat_attachment_reservation (
 upload_id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES pf.space_storage(space_id),
 bytes bigint NOT NULL CHECK(bytes BETWEEN 0 AND 104857600),
 status text NOT NULL CHECK(status IN('RESERVED','COMMITTED','RELEASED','RECONCILING')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 FOREIGN KEY(space_id,upload_id) REFERENCES pf.chat_attachment_upload(space_id,id)
);
CREATE INDEX chat_attachment_upload_expiry ON pf.chat_attachment_upload(status,expires_at);

-- Extraction uses a typed source and its own physical command, not a fake preview/file/task.
ALTER TABLE pf.file_version ADD CONSTRAINT file_version_scoped_asset_unique UNIQUE(space_id,workspace_id,file_id,id,asset_id);
CREATE TABLE pf.document_extraction (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, actor_id uuid NOT NULL,
 source_kind text NOT NULL CHECK(source_kind IN('FILE_VERSION','CHAT_ATTACHMENT')),
 workspace_id uuid, file_id uuid, version_id uuid, attachment_id uuid,
 source_asset_id uuid NOT NULL, source_sha256 varchar(64) NOT NULL CHECK(source_sha256 ~ '^[a-f0-9]{64}$'),
 source_bytes bigint NOT NULL CHECK(source_bytes BETWEEN 0 AND 104857600),
 source_format text NOT NULL CHECK(source_format IN('PDF','DOCX','XLSX','PPTX','TEXT')),
 source_object_key text NOT NULL, output_object_key text NOT NULL,
 image_id text NOT NULL CHECK(image_id ~ '^sha256:[a-f0-9]{64}$'),
 parser_bundle text NOT NULL, policy_hash varchar(64) NOT NULL CHECK(policy_hash ~ '^[a-f0-9]{64}$'),
 status text NOT NULL CHECK(status IN('QUEUED','RUNNING','READY','FAILED','BLOCKED')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0), generation bigint NOT NULL DEFAULT 1 CHECK(generation>0),
 command_id uuid NOT NULL UNIQUE, attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 owner_id uuid, fence bigint, lease_until timestamptz, deadline_at timestamptz,
 output_asset_id uuid, output_sha256 varchar(64), output_bytes bigint CHECK(output_bytes BETWEEN 0 AND 2097152),
 error_code text, retryable boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(space_id,id,source_kind,source_asset_id,source_sha256,output_sha256),
 FOREIGN KEY(space_id,actor_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id,source_asset_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id,asset_id),
 FOREIGN KEY(space_id,attachment_id,source_asset_id) REFERENCES pf.chat_attachment(space_id,id,asset_id),
 FOREIGN KEY(space_id,source_asset_id) REFERENCES pf.asset_object(space_id,id),
 FOREIGN KEY(space_id,output_asset_id) REFERENCES pf.asset_object(space_id,id),
 CHECK((source_kind='FILE_VERSION' AND workspace_id IS NOT NULL AND file_id IS NOT NULL AND version_id IS NOT NULL AND attachment_id IS NULL)
    OR (source_kind='CHAT_ATTACHMENT' AND workspace_id IS NULL AND file_id IS NULL AND version_id IS NULL AND attachment_id IS NOT NULL)),
 CHECK(status<>'RUNNING' OR (owner_id IS NOT NULL AND fence IS NOT NULL AND lease_until IS NOT NULL AND deadline_at IS NOT NULL)),
 CHECK(status<>'READY' OR (output_asset_id IS NOT NULL AND output_sha256 IS NOT NULL AND output_sha256 ~ '^[a-f0-9]{64}$' AND output_bytes IS NOT NULL))
);
CREATE UNIQUE INDEX document_extraction_file_cache ON pf.document_extraction(space_id,version_id,parser_bundle,policy_hash) WHERE source_kind='FILE_VERSION';
CREATE UNIQUE INDEX document_extraction_attachment_cache ON pf.document_extraction(space_id,attachment_id,parser_bundle,policy_hash) WHERE source_kind='CHAT_ATTACHMENT';
CREATE INDEX document_extraction_work ON pf.document_extraction(status,created_at);
CREATE TABLE pf.document_extraction_request (
 space_id uuid NOT NULL, actor_id uuid NOT NULL, request_id uuid NOT NULL,
 request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'), extraction_id uuid NOT NULL,
 PRIMARY KEY(space_id,actor_id,request_id), FOREIGN KEY(space_id,actor_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY(space_id,extraction_id) REFERENCES pf.document_extraction(space_id,id)
);
CREATE TABLE pf.document_extraction_reservation (
 extraction_id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES pf.space_storage(space_id),
 bytes bigint NOT NULL CHECK(bytes BETWEEN 0 AND 2097152),
 status text NOT NULL CHECK(status IN('RESERVED','SETTLED','RELEASED','RECONCILING')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0), FOREIGN KEY(space_id,extraction_id) REFERENCES pf.document_extraction(space_id,id)
);
CREATE TABLE pf.document_extraction_outbox (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, extraction_id uuid NOT NULL, generation bigint NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','PUBLISHING','PUBLISHED')),
 relay_owner uuid, relay_until timestamptz, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,extraction_id) REFERENCES pf.document_extraction(space_id,id), UNIQUE(extraction_id,generation)
);
CREATE TABLE pf.document_extraction_inbox (
 event_id uuid PRIMARY KEY REFERENCES pf.document_extraction_outbox(id), extraction_id uuid NOT NULL REFERENCES pf.document_extraction(id),
 received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pf.document_extraction_attempt (
 command_id uuid PRIMARY KEY, extraction_id uuid NOT NULL REFERENCES pf.document_extraction(id),
 generation bigint NOT NULL, owner_id uuid NOT NULL, fence bigint NOT NULL, command_json jsonb NOT NULL,
 runner_id uuid, status text NOT NULL CHECK(status IN('CLAIMED','STARTED','STOPPED','UNKNOWN','NEVER_ACCEPTED')),
 source_closed_at timestamptz, container_id text, start_requested boolean NOT NULL DEFAULT false,
 started_at text, exec_id text, receipt_json jsonb, receipt_hash varchar(64),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(extraction_id,generation),
 CHECK((receipt_json IS NULL)=(receipt_hash IS NULL))
);
CREATE TRIGGER document_extraction_receipt_immutable BEFORE UPDATE OR DELETE ON pf.document_extraction_attempt
 FOR EACH ROW WHEN(OLD.receipt_json IS NOT NULL) EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.document_extraction_read_exec (
 command_id uuid NOT NULL REFERENCES pf.document_extraction_attempt(command_id), exec_id text NOT NULL CHECK(exec_id ~ '^[a-f0-9]{64}$'),
 ordinal integer NOT NULL CHECK(ordinal>0), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(command_id,exec_id), UNIQUE(command_id,ordinal)
);
CREATE TRIGGER document_extraction_read_exec_immutable BEFORE UPDATE OR DELETE ON pf.document_extraction_read_exec
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.document_extraction_event (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, extraction_id uuid NOT NULL, revision bigint NOT NULL,
 event_type text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,extraction_id) REFERENCES pf.document_extraction(space_id,id), UNIQUE(extraction_id,revision)
);

-- Frozen task references are append-only and identify exact extraction/source bytes.
ALTER TABLE pf.message ADD CONSTRAINT message_task_scope UNIQUE(space_id,task_id,id);
CREATE TABLE pf.task_attachment (
 space_id uuid NOT NULL, task_id uuid NOT NULL, message_id uuid NOT NULL, ordinal integer NOT NULL CHECK(ordinal BETWEEN 0 AND 9),
 extraction_id uuid NOT NULL, source_kind text NOT NULL CHECK(source_kind IN('FILE_VERSION','CHAT_ATTACHMENT')),
 workspace_id uuid, file_id uuid, version_id uuid, attachment_id uuid, source_asset_id uuid NOT NULL,
 source_sha256 varchar(64) NOT NULL CHECK(source_sha256 ~ '^[a-f0-9]{64}$'),
 text_sha256 varchar(64) NOT NULL CHECK(text_sha256 ~ '^[a-f0-9]{64}$'), display_name text NOT NULL,
 PRIMARY KEY(task_id,ordinal), FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id),
 FOREIGN KEY(space_id,task_id,message_id) REFERENCES pf.message(space_id,task_id,id),
 FOREIGN KEY(space_id,extraction_id) REFERENCES pf.document_extraction(space_id,id),
 FOREIGN KEY(space_id,extraction_id,source_kind,source_asset_id,source_sha256,text_sha256)
  REFERENCES pf.document_extraction(space_id,id,source_kind,source_asset_id,source_sha256,output_sha256),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id,source_asset_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id,asset_id),
 FOREIGN KEY(space_id,attachment_id,source_asset_id) REFERENCES pf.chat_attachment(space_id,id,asset_id),
 FOREIGN KEY(space_id,source_asset_id) REFERENCES pf.asset_object(space_id,id),
 CHECK((source_kind='FILE_VERSION' AND workspace_id IS NOT NULL AND file_id IS NOT NULL AND version_id IS NOT NULL AND attachment_id IS NULL)
    OR (source_kind='CHAT_ATTACHMENT' AND workspace_id IS NULL AND file_id IS NULL AND version_id IS NULL AND attachment_id IS NOT NULL))
);
CREATE TRIGGER task_attachment_immutable BEFORE UPDATE OR DELETE ON pf.task_attachment
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

-- Preview keeps its original public identity and supports an independently owned attachment source.
ALTER TABLE pf.preview_job ADD COLUMN source_kind text NOT NULL DEFAULT 'FILE_VERSION'
 CHECK(source_kind IN('FILE_VERSION','CHAT_ATTACHMENT'));
ALTER TABLE pf.preview_job ADD COLUMN attachment_id uuid;
ALTER TABLE pf.preview_job ALTER COLUMN workspace_id DROP NOT NULL;
ALTER TABLE pf.preview_job ALTER COLUMN file_id DROP NOT NULL;
ALTER TABLE pf.preview_job ALTER COLUMN version_id DROP NOT NULL;
ALTER TABLE pf.preview_job ADD CONSTRAINT preview_attachment_source_fk
 FOREIGN KEY(space_id,attachment_id) REFERENCES pf.chat_attachment(space_id,id);
ALTER TABLE pf.preview_job ADD CONSTRAINT preview_attachment_content_fk
 FOREIGN KEY(space_id,attachment_id,source_sha256,source_bytes) REFERENCES pf.chat_attachment(space_id,id,sha256,size_bytes);
ALTER TABLE pf.preview_job ADD CONSTRAINT preview_source_kind_check CHECK(
 (source_kind='FILE_VERSION' AND workspace_id IS NOT NULL AND file_id IS NOT NULL AND version_id IS NOT NULL AND attachment_id IS NULL)
 OR (source_kind='CHAT_ATTACHMENT' AND workspace_id IS NULL AND file_id IS NULL AND version_id IS NULL AND attachment_id IS NOT NULL));
CREATE UNIQUE INDEX preview_attachment_cache ON pf.preview_job(space_id,attachment_id,bundle,policy_hash,requested_format)
 WHERE source_kind='CHAT_ATTACHMENT';

-- Distinct processing identity, same persisted global heavy slot as sandbox and previews.
ALTER TABLE pf.heavy_runtime_slot DROP CONSTRAINT heavy_runtime_slot_kind_check;
ALTER TABLE pf.heavy_runtime_slot ADD CONSTRAINT heavy_runtime_slot_kind_check CHECK(kind IN('SANDBOX','PREVIEW','EXTRACTION'));
CREATE OR REPLACE FUNCTION pf.coordinate_heavy_sandbox_slot() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pf AS $$
DECLARE h pf.heavy_runtime_slot;
BEGIN
 SELECT * INTO h FROM pf.heavy_runtime_slot WHERE id=1 FOR UPDATE;
 IF NEW.environment_id IS NOT NULL THEN
  IF h.kind IN('PREVIEW','EXTRACTION') THEN RAISE EXCEPTION 'HEAVY_RUNTIME_BUSY' USING ERRCODE='55P03'; END IF;
  UPDATE pf.heavy_runtime_slot SET kind='SANDBOX',reference_id=NEW.environment_id,generation=NEW.generation,owner_id=NULL,revision=revision+1 WHERE id=1;
 ELSIF OLD.environment_id IS NOT NULL AND h.kind='SANDBOX' AND h.reference_id=OLD.environment_id THEN
  UPDATE pf.heavy_runtime_slot SET kind=NULL,reference_id=NULL,generation=NULL,owner_id=NULL,revision=revision+1 WHERE id=1;
 END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION pf.reserve_heavy_sandbox_slot(selected_space uuid,selected_workspace uuid,selected_environment uuid,
 selected_generation bigint,selected_operation uuid,expected_revision bigint) RETURNS integer
 LANGUAGE plpgsql SET search_path=pg_catalog,pf AS $$
DECLARE h pf.heavy_runtime_slot; s pf.workspace_sandbox_slot; changed integer;
BEGIN
 SELECT * INTO h FROM pf.heavy_runtime_slot WHERE id=1 FOR UPDATE;
 SELECT * INTO s FROM pf.workspace_sandbox_slot WHERE id=1 FOR UPDATE;
 IF s.revision<>expected_revision OR(s.environment_id IS NOT NULL AND s.environment_id<>selected_environment) THEN RETURN 0; END IF;
 IF h.kind IN('PREVIEW','EXTRACTION') AND s.environment_id IS NULL THEN
  UPDATE pf.workspace_environment_operation o SET waiting_heavy=true
   WHERE o.id=selected_operation AND o.space_id=selected_space AND o.workspace_id=selected_workspace
    AND o.environment_id=selected_environment AND o.generation=selected_generation AND o.status='PENDING' AND o.effect_state='NOT_STARTED'
    AND EXISTS(SELECT 1 FROM pf.workspace_environment e WHERE e.id=o.environment_id AND e.operation_id=o.id AND e.generation=o.generation);
  GET DIAGNOSTICS changed=ROW_COUNT; RETURN changed;
 END IF;
 UPDATE pf.workspace_sandbox_slot SET space_id=selected_space,workspace_id=selected_workspace,environment_id=selected_environment,
  generation=selected_generation,revision=revision+1 WHERE id=1 AND revision=expected_revision;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed=1 THEN UPDATE pf.workspace_environment_operation SET waiting_heavy=false WHERE id=selected_operation;END IF;
 RETURN changed;
END $$;

ALTER TABLE pf.file_transfer_release ADD COLUMN extraction_command_id uuid REFERENCES pf.document_extraction_attempt(command_id);
ALTER TABLE pf.file_transfer_release DROP CONSTRAINT file_transfer_release_proof_kind_check;
ALTER TABLE pf.file_transfer_release ADD CONSTRAINT file_transfer_release_proof_kind_check
 CHECK(proof_kind IN('TRANSPORT_CLOSED','RUNNER_RECEIPT','PREVIEW_TRANSPORT_CLOSED','EXTRACTION_TRANSPORT_CLOSED'));
ALTER TABLE pf.file_transfer_release ADD CONSTRAINT extraction_transport_proof_check
 CHECK((proof_kind='EXTRACTION_TRANSPORT_CLOSED')=(extraction_command_id IS NOT NULL));

REVOKE ALL ON pf.chat_attachment,pf.chat_attachment_upload,pf.chat_attachment_reservation,pf.document_extraction,
 pf.document_extraction_request,pf.document_extraction_reservation,pf.document_extraction_outbox,pf.document_extraction_inbox,
 pf.document_extraction_attempt,pf.document_extraction_read_exec,pf.document_extraction_event,pf.task_attachment FROM PUBLIC;

-- The common asset-job protocol projects independent extraction facts.
ALTER TABLE pf.document_extraction ADD COLUMN name text NOT NULL;
ALTER TABLE pf.document_extraction ADD COLUMN requested_format text NOT NULL DEFAULT 'TEXT_EXTRACT' CHECK(requested_format='TEXT_EXTRACT');
ALTER TABLE pf.document_extraction ADD COLUMN reserved_asset_id uuid NOT NULL;
ALTER TABLE pf.document_extraction ADD COLUMN format text CHECK(format='TEXT');
ALTER TABLE pf.document_extraction ADD COLUMN media_type text CHECK(media_type='text/plain');
ALTER TABLE pf.document_extraction ADD COLUMN pages integer;
ALTER TABLE pf.document_extraction_request ADD COLUMN kind text NOT NULL DEFAULT 'CREATE' CHECK(kind IN('CREATE','RETRY'));
CREATE TABLE pf.document_extraction_asset_reference(
 space_id uuid NOT NULL,extraction_id uuid NOT NULL,asset_id uuid NOT NULL,source_referenced boolean NOT NULL CHECK(NOT source_referenced),
 PRIMARY KEY(space_id,extraction_id),FOREIGN KEY(space_id,extraction_id) REFERENCES pf.document_extraction(space_id,id),
 FOREIGN KEY(space_id,asset_id) REFERENCES pf.asset_object(space_id,id));
CREATE TRIGGER document_extraction_reference_immutable BEFORE UPDATE OR DELETE ON pf.document_extraction_asset_reference
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.document_extraction_delivery_quarantine(id uuid PRIMARY KEY,body_hash varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
REVOKE ALL ON pf.document_extraction_asset_reference,pf.document_extraction_delivery_quarantine FROM PUBLIC;
CREATE TABLE pf.attachment_submission(
 space_id uuid NOT NULL,actor_id uuid NOT NULL,canonical_path text NOT NULL,request_id uuid NOT NULL,
 request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),task_id uuid NOT NULL,
 PRIMARY KEY(space_id,actor_id,canonical_path,request_id),FOREIGN KEY(space_id,actor_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id));
CREATE TRIGGER attachment_submission_immutable BEFORE UPDATE OR DELETE ON pf.attachment_submission
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
REVOKE ALL ON pf.attachment_submission FROM PUBLIC;
ALTER TABLE pf.user_preference DROP CONSTRAINT user_preference_key_check;
ALTER TABLE pf.user_preference ADD CONSTRAINT user_preference_key_check
 CHECK(key IN('defaultModelId','defaultCredentialSource','workbenchLayout','recentFiles'));
