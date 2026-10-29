-- Previews have real source-version identities and independent quota/operation facts.
CREATE TABLE pf.heavy_runtime_slot(
 id integer PRIMARY KEY CHECK(id=1),kind text CHECK(kind IN('SANDBOX','PREVIEW')),reference_id uuid,
 generation bigint CHECK(generation>0),owner_id uuid,fence bigint NOT NULL DEFAULT 0 CHECK(fence>=0),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),CHECK((kind IS NULL)=(reference_id IS NULL)),CHECK((kind IS NULL)=(generation IS NULL))
);
INSERT INTO pf.heavy_runtime_slot(id,kind,reference_id,generation)
 SELECT 1,CASE WHEN environment_id IS NOT NULL THEN 'SANDBOX' END,environment_id,generation FROM pf.workspace_sandbox_slot WHERE id=1;
CREATE FUNCTION pf.lock_heavy_sandbox_slot() RETURNS TABLE(space_id uuid,workspace_id uuid,environment_id uuid,generation bigint,revision bigint)
 LANGUAGE plpgsql SET search_path=pg_catalog,pf AS $$
BEGIN
 PERFORM id FROM pf.heavy_runtime_slot WHERE id=1 FOR UPDATE;
 RETURN QUERY SELECT s.space_id,s.workspace_id,s.environment_id,s.generation,s.revision FROM pf.workspace_sandbox_slot s WHERE s.id=1 FOR UPDATE;
END $$;
CREATE FUNCTION pf.coordinate_heavy_sandbox_slot() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pf AS $$
DECLARE h pf.heavy_runtime_slot;
BEGIN
 SELECT * INTO h FROM pf.heavy_runtime_slot WHERE id=1 FOR UPDATE;
 IF NEW.environment_id IS NOT NULL THEN
  IF h.kind='PREVIEW' THEN RAISE EXCEPTION 'HEAVY_RUNTIME_BUSY' USING ERRCODE='55P03'; END IF;
  UPDATE pf.heavy_runtime_slot SET kind='SANDBOX',reference_id=NEW.environment_id,generation=NEW.generation,owner_id=NULL,revision=revision+1 WHERE id=1;
 ELSIF OLD.environment_id IS NOT NULL AND h.kind='SANDBOX' AND h.reference_id=OLD.environment_id THEN
  UPDATE pf.heavy_runtime_slot SET kind=NULL,reference_id=NULL,generation=NULL,owner_id=NULL,revision=revision+1 WHERE id=1;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER coordinate_heavy_sandbox BEFORE UPDATE ON pf.workspace_sandbox_slot FOR EACH ROW EXECUTE FUNCTION pf.coordinate_heavy_sandbox_slot();
ALTER TABLE pf.workspace_environment_operation ADD COLUMN waiting_heavy boolean NOT NULL DEFAULT false;
-- Accepted lifecycle work may wait on the original outbox without owning physical capacity.
CREATE FUNCTION pf.reserve_heavy_sandbox_slot(selected_space uuid,selected_workspace uuid,selected_environment uuid,
 selected_generation bigint,selected_operation uuid,expected_revision bigint) RETURNS integer
 LANGUAGE plpgsql SET search_path=pg_catalog,pf AS $$
DECLARE h pf.heavy_runtime_slot; s pf.workspace_sandbox_slot; changed integer;
BEGIN
 SELECT * INTO h FROM pf.heavy_runtime_slot WHERE id=1 FOR UPDATE;
 SELECT * INTO s FROM pf.workspace_sandbox_slot WHERE id=1 FOR UPDATE;
 IF s.revision<>expected_revision OR(s.environment_id IS NOT NULL AND s.environment_id<>selected_environment) THEN RETURN 0; END IF;
 IF h.kind='PREVIEW' AND s.environment_id IS NULL THEN
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

ALTER TABLE pf.asset_object DROP CONSTRAINT asset_object_category_check;
ALTER TABLE pf.asset_object ADD CONSTRAINT asset_object_category_check CHECK(category IN('file-version','preview'));
ALTER TABLE pf.asset_object DROP CONSTRAINT asset_object_check;
ALTER TABLE pf.asset_object ADD CONSTRAINT asset_object_check CHECK(object_key LIKE 'spaces/'||space_id::text||'/'||category||'/'||id::text||'/'||version_id::text||'/%');
CREATE TABLE pf.preview_job(
 id uuid PRIMARY KEY,space_id uuid NOT NULL,workspace_id uuid NOT NULL,actor_id uuid NOT NULL REFERENCES pf.app_user(id),
 file_id uuid NOT NULL,version_id uuid NOT NULL,name text NOT NULL,requested_format text NOT NULL CHECK(requested_format IN('AUTO','PDF','IMAGE','HTML_STATIC')),
 source_format text NOT NULL CHECK(source_format IN('PDF','DOCX','XLSX','PPTX','PNG','JPEG','GIF','WEBP','TEXT','HTML','SVG')),
 source_object_key text NOT NULL,source_bytes bigint NOT NULL CHECK(source_bytes BETWEEN 0 AND 104857600),source_sha256 varchar(64) NOT NULL CHECK(source_sha256~'^[a-f0-9]{64}$'),
 image_id text NOT NULL CHECK(image_id~'^sha256:[a-f0-9]{64}$'),bundle text NOT NULL,policy_hash varchar(64) NOT NULL CHECK(policy_hash~'^[a-f0-9]{64}$'),
 state text NOT NULL CHECK(state IN('QUEUED','RUNNING','READY','FAILED','BLOCKED')),revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 generation bigint NOT NULL DEFAULT 1 CHECK(generation>0),command_id uuid NOT NULL UNIQUE,owner_id uuid,fence bigint,
 lease_until timestamptz,deadline_at timestamptz,attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 format text CHECK(format IN('PDF','IMAGE','TEXT','HTML_STATIC')),output_object_key text NOT NULL,bytes bigint CHECK(bytes BETWEEN 0 AND 104857600),
 sha256 varchar(64) CHECK(sha256~'^[a-f0-9]{64}$'),media_type text,pages integer CHECK(pages BETWEEN 1 AND 200),
 error_code varchar(64),retryable boolean NOT NULL DEFAULT false,asset_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 UNIQUE(space_id,id),UNIQUE(space_id,version_id,bundle,policy_hash,requested_format),
 CHECK(state<>'RUNNING' OR(owner_id IS NOT NULL AND fence IS NOT NULL AND lease_until IS NOT NULL AND deadline_at IS NOT NULL)),
 CHECK(state<>'READY' OR(format IS NOT NULL AND bytes IS NOT NULL AND sha256 IS NOT NULL AND media_type IS NOT NULL))
);
CREATE TABLE pf.preview_request(space_id uuid NOT NULL,actor_id uuid NOT NULL,request_id uuid NOT NULL,request_hash varchar(64) NOT NULL,
 preview_id uuid NOT NULL,kind text NOT NULL CHECK(kind IN('CREATE','RETRY')),PRIMARY KEY(space_id,actor_id,request_id),
 FOREIGN KEY(space_id,preview_id) REFERENCES pf.preview_job(space_id,id));
CREATE TABLE pf.preview_reservation(preview_id uuid PRIMARY KEY REFERENCES pf.preview_job(id),space_id uuid NOT NULL REFERENCES pf.space_storage(space_id),
 bytes bigint NOT NULL CHECK(bytes BETWEEN 0 AND 104857600),status text NOT NULL CHECK(status IN('RESERVED','SETTLED','RELEASED','RECONCILING')),revision bigint NOT NULL DEFAULT 1);
CREATE TABLE pf.preview_outbox(id uuid PRIMARY KEY,space_id uuid NOT NULL,preview_id uuid NOT NULL,generation bigint NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','PUBLISHING','PUBLISHED')),relay_owner uuid,relay_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(space_id,preview_id) REFERENCES pf.preview_job(space_id,id),UNIQUE(preview_id,generation));
CREATE TABLE pf.preview_inbox(event_id uuid PRIMARY KEY REFERENCES pf.preview_outbox(id),preview_id uuid NOT NULL REFERENCES pf.preview_job(id),received_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE pf.preview_attempt(command_id uuid PRIMARY KEY,preview_id uuid NOT NULL REFERENCES pf.preview_job(id),generation bigint NOT NULL,
 owner_id uuid NOT NULL,fence bigint NOT NULL,command_json jsonb NOT NULL,runner_id uuid,status text NOT NULL CHECK(status IN('CLAIMED','STARTED','STOPPED','UNKNOWN','NEVER_ACCEPTED')),
 source_closed_at timestamptz,container_id text,start_requested boolean NOT NULL DEFAULT false,started_at text,exec_id text,
 receipt_json jsonb,receipt_hash varchar(64),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(preview_id,generation),CHECK((receipt_json IS NULL)=(receipt_hash IS NULL)));
CREATE TABLE pf.preview_read_exec(command_id uuid NOT NULL REFERENCES pf.preview_attempt(command_id),exec_id text NOT NULL CHECK(exec_id~'^[a-f0-9]{64}$'),
 ordinal integer NOT NULL CHECK(ordinal>0),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(command_id,exec_id),UNIQUE(command_id,ordinal));
CREATE TABLE pf.preview_asset_reference(space_id uuid NOT NULL,preview_id uuid NOT NULL,asset_id uuid NOT NULL,source_referenced boolean NOT NULL,
 PRIMARY KEY(space_id,preview_id),FOREIGN KEY(space_id,preview_id) REFERENCES pf.preview_job(space_id,id),FOREIGN KEY(space_id,asset_id) REFERENCES pf.asset_object(space_id,id));
CREATE TABLE pf.preview_event(id uuid PRIMARY KEY,space_id uuid NOT NULL,preview_id uuid NOT NULL,revision bigint NOT NULL,event_type text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(space_id,preview_id) REFERENCES pf.preview_job(space_id,id),UNIQUE(preview_id,revision));
CREATE TABLE pf.preview_delivery_quarantine(id uuid PRIMARY KEY,body_hash varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE INDEX preview_work ON pf.preview_job(state,created_at);
CREATE TRIGGER preview_receipt_immutable BEFORE UPDATE OR DELETE ON pf.preview_asset_reference FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TRIGGER preview_attempt_receipt_immutable BEFORE UPDATE OR DELETE ON pf.preview_attempt FOR EACH ROW
 WHEN(OLD.receipt_json IS NOT NULL) EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TRIGGER preview_read_exec_immutable BEFORE UPDATE OR DELETE ON pf.preview_read_exec FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
ALTER TABLE pf.file_transfer_release ADD COLUMN preview_command_id uuid REFERENCES pf.preview_attempt(command_id);
ALTER TABLE pf.file_transfer_release DROP CONSTRAINT file_transfer_release_proof_kind_check;
ALTER TABLE pf.file_transfer_release DROP CONSTRAINT file_transfer_release_check;
ALTER TABLE pf.file_transfer_release ADD CHECK(proof_kind IN('TRANSPORT_CLOSED','RUNNER_RECEIPT','PREVIEW_TRANSPORT_CLOSED'));
ALTER TABLE pf.file_transfer_release ADD CHECK((proof_kind='RUNNER_RECEIPT')=(runner_command_id IS NOT NULL));
ALTER TABLE pf.file_transfer_release ADD CHECK((proof_kind='PREVIEW_TRANSPORT_CLOSED')=(preview_command_id IS NOT NULL));
REVOKE ALL ON pf.heavy_runtime_slot,pf.preview_job,pf.preview_request,pf.preview_reservation,pf.preview_outbox,pf.preview_inbox,pf.preview_attempt,pf.preview_read_exec,pf.preview_asset_reference,pf.preview_event,pf.preview_delivery_quarantine FROM PUBLIC;
REVOKE ALL ON FUNCTION pf.lock_heavy_sandbox_slot(),pf.coordinate_heavy_sandbox_slot(),pf.reserve_heavy_sandbox_slot(uuid,uuid,uuid,bigint,uuid,bigint) FROM PUBLIC;
