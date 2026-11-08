-- R6.3 additive schema. Canonical chat/task facts and V1-V23 remain unchanged.
CREATE EXTENSION IF NOT EXISTS vector;

-- The shared slot previously had no lease timestamp. Add it in this migration so
-- query/index work can never infer an expired owner from a stale process clock.
ALTER TABLE pf.heavy_runtime_slot ADD COLUMN IF NOT EXISTS lease_until timestamptz;
ALTER TABLE pf.heavy_runtime_slot DROP CONSTRAINT IF EXISTS heavy_runtime_slot_kind_check;
ALTER TABLE pf.heavy_runtime_slot ADD CONSTRAINT heavy_runtime_slot_kind_check
 CHECK(kind IN('SANDBOX','PREVIEW','EXTRACTION','KNOWLEDGE'));
CREATE OR REPLACE FUNCTION pf.coordinate_heavy_sandbox_slot() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pf AS $$
DECLARE h pf.heavy_runtime_slot;
BEGIN
 SELECT * INTO h FROM pf.heavy_runtime_slot WHERE id=1 FOR UPDATE;
 IF NEW.environment_id IS NOT NULL THEN
  IF h.kind IN('PREVIEW','EXTRACTION','KNOWLEDGE') THEN RAISE EXCEPTION 'HEAVY_RUNTIME_BUSY' USING ERRCODE='55P03'; END IF;
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
 IF h.kind IN('PREVIEW','EXTRACTION','KNOWLEDGE') AND s.environment_id IS NULL THEN
  UPDATE pf.workspace_environment_operation o SET waiting_heavy=true
   WHERE o.id=selected_operation AND o.space_id=selected_space AND o.workspace_id=selected_workspace
    AND o.environment_id=selected_environment AND o.generation=selected_generation AND o.status='PENDING' AND o.effect_state='NOT_STARTED'
    AND EXISTS(SELECT 1 FROM pf.workspace_environment e WHERE e.id=o.environment_id AND e.operation_id=o.id AND e.generation=o.generation);
  GET DIAGNOSTICS changed=ROW_COUNT;RETURN changed;
 END IF;
 UPDATE pf.workspace_sandbox_slot SET space_id=selected_space,workspace_id=selected_workspace,environment_id=selected_environment,
  generation=selected_generation,revision=revision+1 WHERE id=1 AND revision=expected_revision;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed=1 THEN UPDATE pf.workspace_environment_operation SET waiting_heavy=false WHERE id=selected_operation;END IF;
 RETURN changed;
END $$;
REVOKE ALL ON FUNCTION pf.coordinate_heavy_sandbox_slot(),pf.reserve_heavy_sandbox_slot(uuid,uuid,uuid,bigint,uuid,bigint) FROM PUBLIC;

CREATE TABLE pf.knowledge_mutation_request (
 space_id uuid NOT NULL,
 actor_id uuid NOT NULL,
 request_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN('BASE_CREATE','DOCUMENT_ADD','DOCUMENT_REBUILD','DOCUMENT_DELETE')),
 request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 resource_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(space_id,actor_id,request_id),
 FOREIGN KEY(space_id,actor_id) REFERENCES pf.membership(space_id,user_id)
);
CREATE TRIGGER knowledge_mutation_request_immutable BEFORE UPDATE OR DELETE ON pf.knowledge_mutation_request
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

CREATE TABLE pf.knowledge_base (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 workspace_id uuid,
 name text NOT NULL CHECK(octet_length(name) BETWEEN 1 AND 512),
 normalized_name text NOT NULL CHECK(octet_length(normalized_name) BETWEEN 1 AND 512),
 state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN('ACTIVE','DELETED')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(space_id,owner_user_id,id), UNIQUE(space_id,owner_user_id,normalized_name),
 FOREIGN KEY(space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id)
);

CREATE TABLE pf.knowledge_document (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 knowledge_base_id uuid NOT NULL,
 source_kind text NOT NULL CHECK(source_kind IN('FILE_VERSION','CHAT_ATTACHMENT')),
 workspace_id uuid,
 file_id uuid,
 version_id uuid,
 attachment_id uuid,
 source_asset_id uuid NOT NULL,
 source_sha256 varchar(64) NOT NULL CHECK(source_sha256 ~ '^[a-f0-9]{64}$'),
 extraction_id uuid NOT NULL,
 display_name text NOT NULL CHECK(octet_length(display_name) BETWEEN 1 AND 1024),
 extraction_sha256 varchar(64) NOT NULL CHECK(extraction_sha256 ~ '^[a-f0-9]{64}$'),
 status text NOT NULL CHECK(status IN('QUEUED','BUILDING','READY','FAILED','BLOCKED','DELETED')),
 active_index_version uuid,
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(space_id,owner_user_id,id), UNIQUE(space_id,knowledge_base_id,id),
 FOREIGN KEY(space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY(space_id,owner_user_id,knowledge_base_id) REFERENCES pf.knowledge_base(space_id,owner_user_id,id),
 FOREIGN KEY(space_id,source_asset_id) REFERENCES pf.asset_object(space_id,id),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id,source_asset_id)
   REFERENCES pf.file_version(space_id,workspace_id,file_id,id,asset_id),
 FOREIGN KEY(space_id,attachment_id,source_asset_id) REFERENCES pf.chat_attachment(space_id,id,asset_id),
 CHECK((source_kind='FILE_VERSION' AND workspace_id IS NOT NULL AND file_id IS NOT NULL AND version_id IS NOT NULL AND attachment_id IS NULL)
    OR (source_kind='CHAT_ATTACHMENT' AND workspace_id IS NULL AND file_id IS NULL AND version_id IS NULL AND attachment_id IS NOT NULL))
);

-- Extraction may be for either supported source kind; the typed source identity is kept on the document.
ALTER TABLE pf.knowledge_document ADD CONSTRAINT knowledge_document_extraction_fk
 FOREIGN KEY(space_id,extraction_id,source_kind,source_asset_id,source_sha256,extraction_sha256)
 REFERENCES pf.document_extraction(space_id,id,source_kind,source_asset_id,source_sha256,output_sha256)
 DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX knowledge_document_source_once ON pf.knowledge_document(space_id,knowledge_base_id,extraction_id) WHERE status<>'DELETED';
CREATE INDEX knowledge_document_acl ON pf.knowledge_document(space_id,knowledge_base_id,status,owner_user_id);

CREATE TABLE pf.knowledge_index_version (
 space_id uuid NOT NULL,
 document_id uuid NOT NULL,
 index_version uuid NOT NULL,
 embedding_profile text NOT NULL CHECK(embedding_profile='multilingual-e5-small-int8-v1'),
 model_sha256 varchar(64) NOT NULL CHECK(model_sha256 ~ '^[a-f0-9]{64}$'),
 tokenizer_sha256 varchar(64) NOT NULL CHECK(tokenizer_sha256 ~ '^[a-f0-9]{64}$'),
 parser_bundle text NOT NULL,
 chunker_version text NOT NULL,
 state text NOT NULL CHECK(state IN('BUILDING','READY','FAILED','RETIRED')),
 chunk_count integer NOT NULL DEFAULT 0 CHECK(chunk_count BETWEEN 0 AND 10000),
 content_manifest_sha256 varchar(64) CHECK(content_manifest_sha256 ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 completed_at timestamptz,
 PRIMARY KEY(space_id,document_id,index_version),
 FOREIGN KEY(space_id,document_id) REFERENCES pf.knowledge_document(space_id,id),
 CHECK((state IN('READY','RETIRED'))=(completed_at IS NOT NULL AND content_manifest_sha256 IS NOT NULL))
);
ALTER TABLE pf.knowledge_document ADD CONSTRAINT knowledge_document_active_index_fk
 FOREIGN KEY(space_id,id,active_index_version) REFERENCES pf.knowledge_index_version(space_id,document_id,index_version)
 DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE pf.knowledge_index_build (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 document_id uuid NOT NULL,
 index_version uuid NOT NULL,
 generation bigint NOT NULL CHECK(generation>0),
 state text NOT NULL CHECK(state IN('QUEUED','RUNNING','COMMITTED','FAILED','BLOCKED')),
 owner_id uuid,
 fence bigint CHECK(fence>0),
 lease_until timestamptz,
 next_ordinal integer NOT NULL DEFAULT 0 CHECK(next_ordinal>=0),
 error_code varchar(64),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(document_id,index_version),
 FOREIGN KEY(space_id,document_id,index_version) REFERENCES pf.knowledge_index_version(space_id,document_id,index_version),
 CHECK(state<>'RUNNING' OR (owner_id IS NOT NULL AND fence IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX knowledge_build_work ON pf.knowledge_index_build(state,created_at);
CREATE TABLE pf.knowledge_index_chunk (
 space_id uuid NOT NULL,
 document_id uuid NOT NULL,
 index_version uuid NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal>=0),
 chunk_sha256 varchar(64) NOT NULL CHECK(chunk_sha256 ~ '^[a-f0-9]{64}$'),
 body text NOT NULL CHECK(octet_length(body) BETWEEN 1 AND 8192),
 normalized tsvector GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED,
 embedding vector(384) NOT NULL,
 source_location jsonb NOT NULL CHECK(jsonb_typeof(source_location)='object'),
 source_start bigint NOT NULL CHECK(source_start>=0),
 source_end bigint NOT NULL CHECK(source_end>source_start),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(space_id,document_id,index_version,ordinal),
 FOREIGN KEY(space_id,document_id,index_version) REFERENCES pf.knowledge_index_version(space_id,document_id,index_version),
 CHECK(vector_dims(embedding)=384)
);
CREATE INDEX knowledge_chunk_lexical ON pf.knowledge_index_chunk USING gin(normalized);
CREATE INDEX knowledge_chunk_embedding_exact ON pf.knowledge_index_chunk(space_id,document_id,index_version);
CREATE TRIGGER knowledge_index_chunk_immutable BEFORE UPDATE OR DELETE ON pf.knowledge_index_chunk
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

CREATE TABLE pf.knowledge_index_outbox (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 build_id uuid NOT NULL,
 generation bigint NOT NULL,
 state text NOT NULL DEFAULT 'PENDING' CHECK(state IN('PENDING','PUBLISHING','PUBLISHED')),
 relay_owner uuid,
 relay_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(build_id,generation),
 FOREIGN KEY(space_id,build_id) REFERENCES pf.knowledge_index_build(space_id,id)
);
CREATE TABLE pf.knowledge_index_inbox (
 event_id uuid PRIMARY KEY REFERENCES pf.knowledge_index_outbox(id),
 build_id uuid NOT NULL REFERENCES pf.knowledge_index_build(id),
 received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pf.knowledge_index_delivery_quarantine(
 id uuid PRIMARY KEY, body_hash varchar(64) NOT NULL CHECK(body_hash ~ '^[a-f0-9]{64}$'), created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE pf.personal_memory (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 scope_kind text NOT NULL CHECK(scope_kind IN('PERSONAL','WORKSPACE')),
 workspace_id uuid,
 category text NOT NULL CHECK(category IN('PREFERENCE','FACT','PROCEDURE')),
 topic_key text NOT NULL CHECK(octet_length(topic_key) BETWEEN 1 AND 256),
 content text NOT NULL CHECK(octet_length(content) BETWEEN 1 AND 8192),
 content_sha256 varchar(64) NOT NULL CHECK(content_sha256 ~ '^[a-f0-9]{64}$'),
 source_kind text NOT NULL CHECK(source_kind IN('USER_PROVIDED','FILE_VERSION','CHAT_MESSAGE')),
 source_id uuid,
 status text NOT NULL CHECK(status IN('PENDING','ACTIVE','REJECTED','SUPERSEDED','REVIEW_REQUIRED','EXPIRED','DELETED')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 confirmed_at timestamptz,
 UNIQUE(space_id,owner_user_id,id),
 FOREIGN KEY(space_id,owner_user_id) REFERENCES pf.membership(space_id,user_id),
 FOREIGN KEY(space_id,workspace_id) REFERENCES pf.workspace(space_id,id),
 CHECK((scope_kind='PERSONAL' AND workspace_id IS NULL) OR (scope_kind='WORKSPACE' AND workspace_id IS NOT NULL)),
 CHECK(status<>'ACTIVE' OR confirmed_at IS NOT NULL)
);
CREATE UNIQUE INDEX personal_memory_active_topic ON pf.personal_memory(space_id,owner_user_id,scope_kind,COALESCE(workspace_id,'00000000-0000-0000-0000-000000000000'::uuid),topic_key) WHERE status='ACTIVE';
CREATE INDEX personal_memory_recall ON pf.personal_memory(space_id,owner_user_id,scope_kind,status,expires_at);
CREATE TABLE pf.memory_mutation_request (
 space_id uuid NOT NULL,
 actor_id uuid NOT NULL,
 request_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN('CANDIDATE','CONFIRM','REJECT','EDIT','SUPERSEDE','DELETE')),
 request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 memory_id uuid NOT NULL,
 result_revision bigint NOT NULL CHECK(result_revision>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(space_id,actor_id,request_id),
 FOREIGN KEY(space_id,actor_id,memory_id) REFERENCES pf.personal_memory(space_id,owner_user_id,id)
);
CREATE TRIGGER memory_mutation_request_immutable BEFORE UPDATE OR DELETE ON pf.memory_mutation_request
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.personal_memory_audit (
 id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 memory_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN('CANDIDATE','CONFIRM','REJECT','EDIT','SUPERSEDE','DELETE','EXPIRE','REVIEW')),
 revision bigint NOT NULL CHECK(revision>0),
 content_sha256 varchar(64) NOT NULL CHECK(content_sha256 ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,owner_user_id,memory_id) REFERENCES pf.personal_memory(space_id,owner_user_id,id),
 UNIQUE(memory_id,revision,action)
);
CREATE TRIGGER personal_memory_audit_immutable BEFORE UPDATE OR DELETE ON pf.personal_memory_audit
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();

CREATE TABLE pf.task_context_projection (
 space_id uuid NOT NULL,
 task_id uuid NOT NULL,
 context_epoch bigint NOT NULL CHECK(context_epoch>0),
 bundle_hash varchar(64) NOT NULL CHECK(bundle_hash ~ '^[a-f0-9]{64}$'),
 ledger_watermark jsonb NOT NULL CHECK(jsonb_typeof(ledger_watermark)='object'),
 source_manifest_sha256 varchar(64) NOT NULL CHECK(source_manifest_sha256 ~ '^[a-f0-9]{64}$'),
 summary text NOT NULL CHECK(octet_length(summary) BETWEEN 1 AND 2097152),
 summary_sha256 varchar(64) NOT NULL CHECK(summary_sha256 ~ '^[a-f0-9]{64}$'),
 input_estimated_tokens bigint NOT NULL CHECK(input_estimated_tokens>=0),
 policy_version text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(task_id,context_epoch),
 UNIQUE(space_id,task_id,context_epoch),
 FOREIGN KEY(space_id,task_id) REFERENCES pf.task(space_id,id)
);
CREATE TRIGGER task_context_projection_immutable BEFORE UPDATE OR DELETE ON pf.task_context_projection
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.task_context_projection_current (
 space_id uuid NOT NULL,
 task_id uuid PRIMARY KEY,
 context_epoch bigint NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,task_id,context_epoch) REFERENCES pf.task_context_projection(space_id,task_id,context_epoch)
);

REVOKE ALL ON pf.knowledge_base,pf.knowledge_document,pf.knowledge_index_version,pf.knowledge_index_build,
 pf.knowledge_index_chunk,pf.knowledge_index_outbox,pf.knowledge_index_inbox,pf.knowledge_index_delivery_quarantine,pf.personal_memory,
 pf.personal_memory_audit,pf.task_context_projection,pf.task_context_projection_current,
 pf.knowledge_mutation_request,pf.memory_mutation_request FROM PUBLIC;
