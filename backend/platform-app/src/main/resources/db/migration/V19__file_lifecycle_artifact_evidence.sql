-- Additive lifecycle facts; immutable content and runtime baseline remain unchanged.
ALTER TABLE pf.file_entry ADD COLUMN deleted_at timestamptz;
ALTER TABLE pf.file_entry ADD COLUMN deletion_operation_id uuid;
ALTER TABLE pf.file_entry ADD COLUMN retain_until timestamptz;
ALTER TABLE pf.file_entry ADD CONSTRAINT file_tombstone_consistent CHECK
 ((deleted_at IS NULL AND deletion_operation_id IS NULL AND retain_until IS NULL)
  OR (deleted_at IS NOT NULL AND deletion_operation_id IS NOT NULL AND retain_until>=deleted_at+interval '30 days'));
ALTER TABLE pf.file_entry ADD CONSTRAINT file_deletion_operation_fk
 FOREIGN KEY(space_id,workspace_id,deletion_operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id);
ALTER TABLE pf.file_entry DROP CONSTRAINT file_entry_space_id_workspace_id_relative_path_key;
CREATE UNIQUE INDEX file_entry_active_path_unique ON pf.file_entry(space_id,workspace_id,relative_path) WHERE deleted_at IS NULL;
CREATE INDEX file_entry_deleted_page ON pf.file_entry(space_id,workspace_id,deleted_at,id) WHERE deleted_at IS NOT NULL;

CREATE TABLE pf.file_mutation_intent (
 operation_id uuid PRIMARY KEY,
 space_id uuid NOT NULL,workspace_id uuid NOT NULL,actor_id uuid NOT NULL REFERENCES pf.app_user(id),
 request_id uuid NOT NULL,request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 kind text NOT NULL CHECK(kind IN('MOVE','DELETE','RESTORE_FILE','RESTORE_TREE','DELETE_CLEANUP')),
 root_file_id uuid NOT NULL,source_path text NOT NULL,target_path text,
 expected_revision bigint NOT NULL CHECK(expected_revision>0),
 manifest_json text NOT NULL CHECK(octet_length(manifest_json)<=8388608),
 manifest_hash varchar(64) NOT NULL CHECK(manifest_hash ~ '^[a-f0-9]{64}$'),
 entry_count integer NOT NULL CHECK(entry_count BETWEEN 1 AND 1000),
 source_version_id uuid,source_deletion_operation_id uuid,parent_operation_id uuid,
 target_parent_id uuid,target_parent_revision bigint CHECK(target_parent_revision>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,workspace_id,operation_id),UNIQUE(space_id,actor_id,request_id),
 FOREIGN KEY(space_id,workspace_id,operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id),
 FOREIGN KEY(space_id,workspace_id,root_file_id) REFERENCES pf.file_entry(space_id,workspace_id,id),
 FOREIGN KEY(space_id,workspace_id,root_file_id,source_version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 FOREIGN KEY(space_id,workspace_id,source_deletion_operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id),
 FOREIGN KEY(space_id,workspace_id,parent_operation_id) REFERENCES pf.file_mutation_intent(space_id,workspace_id,operation_id),
 FOREIGN KEY(space_id,workspace_id,target_parent_id) REFERENCES pf.file_entry(space_id,workspace_id,id),
 CHECK((target_parent_id IS NULL)=(target_parent_revision IS NULL)),
 CHECK((kind='MOVE' AND target_path IS NOT NULL) OR kind<>'MOVE'),
 CHECK((kind='RESTORE_FILE')=(source_version_id IS NOT NULL)),
 CHECK((kind='RESTORE_TREE')=(source_deletion_operation_id IS NOT NULL)),
 CHECK((kind='DELETE_CLEANUP')=(parent_operation_id IS NOT NULL))
);
CREATE TABLE pf.file_mutation_item (
 space_id uuid NOT NULL,workspace_id uuid NOT NULL,operation_id uuid NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal BETWEEN 0 AND 999),file_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN('FILE','DIRECTORY')),source_path text NOT NULL,target_path text,
 expected_revision bigint NOT NULL CHECK(expected_revision>0),version_id uuid,
 sha256 varchar(64),size_bytes bigint,
 PRIMARY KEY(operation_id,ordinal),UNIQUE(operation_id,file_id),
 FOREIGN KEY(space_id,workspace_id,operation_id) REFERENCES pf.file_mutation_intent(space_id,workspace_id,operation_id),
 FOREIGN KEY(space_id,workspace_id,file_id) REFERENCES pf.file_entry(space_id,workspace_id,id),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 CHECK((kind='FILE' AND version_id IS NOT NULL AND sha256 ~ '^[a-f0-9]{64}$' AND size_bytes BETWEEN 0 AND 104857600)
 OR(kind='DIRECTORY' AND version_id IS NULL AND sha256 IS NULL AND size_bytes IS NULL))
);
CREATE TABLE pf.file_version_origin (
 space_id uuid NOT NULL,workspace_id uuid NOT NULL,file_id uuid NOT NULL,
 version_id uuid PRIMARY KEY,source_version_id uuid NOT NULL,restore_operation_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 FOREIGN KEY(space_id,workspace_id,file_id,source_version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 FOREIGN KEY(space_id,workspace_id,restore_operation_id) REFERENCES pf.file_operation(space_id,workspace_id,id),
 CHECK(version_id<>source_version_id)
);
CREATE TABLE pf.file_restore_upload (
 upload_id uuid PRIMARY KEY REFERENCES pf.upload_session(id),operation_id uuid NOT NULL UNIQUE,
 space_id uuid NOT NULL,workspace_id uuid NOT NULL,file_id uuid NOT NULL,source_version_id uuid NOT NULL,
 source_path text NOT NULL,source_missing boolean NOT NULL,manifest_json text NOT NULL CHECK(octet_length(manifest_json)<=8388608),
 manifest_hash varchar(64) NOT NULL CHECK(manifest_hash ~ '^[a-f0-9]{64}$'),
 target_parent_id uuid,target_parent_revision bigint CHECK(target_parent_revision>0),
 FOREIGN KEY(space_id,workspace_id,file_id,source_version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 FOREIGN KEY(space_id,workspace_id,target_parent_id) REFERENCES pf.file_entry(space_id,workspace_id,id),
 CHECK((target_parent_id IS NULL)=(target_parent_revision IS NULL))
);
CREATE TRIGGER file_restore_upload_immutable BEFORE UPDATE OR DELETE ON pf.file_restore_upload
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TABLE pf.file_tree_restore_item (
 operation_id uuid NOT NULL,space_id uuid NOT NULL,workspace_id uuid NOT NULL,file_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN('FILE','DIRECTORY')),source_path text NOT NULL,target_path text NOT NULL,
 expected_revision bigint NOT NULL CHECK(expected_revision>0),parent_version_id uuid,source_version_id uuid,
 version_id uuid UNIQUE,asset_id uuid UNIQUE,object_key text,source_object_key text,size_bytes bigint,sha256 varchar(64),
 PRIMARY KEY(operation_id,file_id),
 FOREIGN KEY(space_id,workspace_id,operation_id) REFERENCES pf.file_mutation_intent(space_id,workspace_id,operation_id),
 FOREIGN KEY(space_id,workspace_id,file_id) REFERENCES pf.file_entry(space_id,workspace_id,id),
 FOREIGN KEY(space_id,workspace_id,file_id,source_version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 FOREIGN KEY(space_id,workspace_id,file_id,parent_version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 FOREIGN KEY(space_id,version_id,asset_id) REFERENCES pf.asset_object(space_id,version_id,id),
 CHECK((kind='DIRECTORY' AND num_nonnulls(version_id,asset_id,object_key,source_object_key,size_bytes,sha256,source_version_id,parent_version_id)=0)
    OR(kind='FILE' AND num_nonnulls(version_id,asset_id,object_key,source_object_key,size_bytes,sha256,source_version_id,parent_version_id)=8
    AND size_bytes BETWEEN 0 AND 104857600 AND sha256 ~ '^[a-f0-9]{64}$'))
);
CREATE TABLE pf.file_tree_reservation (
 operation_id uuid PRIMARY KEY REFERENCES pf.file_mutation_intent(operation_id),space_id uuid NOT NULL REFERENCES pf.space(id),
 bytes bigint NOT NULL CHECK(bytes BETWEEN 0 AND 2147483648),
 status text NOT NULL DEFAULT 'RESERVED' CHECK(status IN('RESERVED','SETTLED','RELEASED')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0)
);
CREATE TRIGGER file_tree_restore_item_immutable BEFORE UPDATE OR DELETE ON pf.file_tree_restore_item
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
REVOKE ALL ON pf.file_tree_restore_item,pf.file_tree_reservation FROM PUBLIC;
CREATE TABLE pf.artifact (
 id uuid PRIMARY KEY,space_id uuid NOT NULL,task_id uuid NOT NULL,operation_id uuid NOT NULL,
 workspace_id uuid NOT NULL,file_id uuid NOT NULL,version_id uuid NOT NULL,asset_id uuid NOT NULL,
 title text NOT NULL CHECK(octet_length(title) BETWEEN 1 AND 4096),
 sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 0 AND 104857600),
 evidence_status text NOT NULL CHECK(evidence_status IN('PASS','FAIL','NOT_RUN')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id),UNIQUE(task_id,operation_id,version_id),
 FOREIGN KEY(space_id,task_id,operation_id) REFERENCES pf.tool_operation(space_id,task_id,id),
 FOREIGN KEY(space_id,workspace_id,file_id,version_id) REFERENCES pf.file_version(space_id,workspace_id,file_id,id),
 FOREIGN KEY(space_id,version_id,asset_id) REFERENCES pf.asset_object(space_id,version_id,id)
);
CREATE TRIGGER file_mutation_intent_immutable BEFORE UPDATE OR DELETE ON pf.file_mutation_intent
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TRIGGER file_mutation_item_immutable BEFORE UPDATE OR DELETE ON pf.file_mutation_item
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TRIGGER file_version_origin_immutable BEFORE UPDATE OR DELETE ON pf.file_version_origin
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
CREATE TRIGGER artifact_immutable BEFORE UPDATE OR DELETE ON pf.artifact
 FOR EACH ROW EXECUTE FUNCTION pf.reject_checkpoint_mutation();
DROP TRIGGER file_content_change ON pf.file_entry;
CREATE TRIGGER file_content_change AFTER UPDATE ON pf.file_entry FOR EACH ROW
 WHEN (ROW(OLD.current_version_id,OLD.collection_state,OLD.observed_sha256,OLD.observed_bytes,OLD.relative_path,OLD.deleted_at)
 IS DISTINCT FROM ROW(NEW.current_version_id,NEW.collection_state,NEW.observed_sha256,NEW.observed_bytes,NEW.relative_path,NEW.deleted_at))
 EXECUTE FUNCTION pf.advance_workspace_content_revision();
REVOKE ALL ON pf.file_mutation_intent,pf.file_mutation_item,pf.file_version_origin,pf.file_restore_upload,pf.artifact FROM PUBLIC;

-- Public artifacts are derived only from committed version facts, never result JSON.
CREATE FUNCTION pf.seal_operation_artifacts(selected_operation uuid) RETURNS void
 LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
 INSERT INTO pf.artifact(id,space_id,task_id,operation_id,workspace_id,file_id,version_id,asset_id,title,sha256,size_bytes,evidence_status)
 SELECT v.id,i.space_id,i.task_id,i.operation_id,i.workspace_id,v.file_id,v.id,v.asset_id,proof.path,v.sha256,v.size_bytes,'NOT_RUN'
 FROM pf.workspace_tool_intent i JOIN pf.tool_operation o ON o.id=i.operation_id AND o.space_id=i.space_id AND o.task_id=i.task_id
 JOIN LATERAL (
   SELECT c.file_id,c.version_id,c.relative_path AS path FROM pf.tool_output_capture c
    WHERE c.operation_id=i.operation_id AND c.space_id=i.space_id AND c.workspace_id=i.workspace_id AND c.status='COMMITTED'
   UNION ALL
   SELECT u.file_id,u.version_id,u.relative_path FROM pf.workspace_tool_job_binding b
    JOIN pf.file_operation f ON f.id=b.file_operation_id AND f.space_id=b.space_id AND f.workspace_id=b.workspace_id AND f.status='COMMITTED'
    JOIN pf.upload_session u ON u.id=f.upload_id AND u.space_id=f.space_id AND u.workspace_id=f.workspace_id AND u.status='COMMITTED'
    WHERE b.operation_id=i.operation_id AND b.space_id=i.space_id AND b.task_id=i.task_id AND i.tool_name='workspace_write' AND o.status='SUCCEEDED'
 ) proof ON true
 JOIN pf.file_version v ON v.space_id=i.space_id AND v.workspace_id=i.workspace_id AND v.file_id=proof.file_id AND v.id=proof.version_id
 JOIN pf.asset_object a ON a.space_id=v.space_id AND a.id=v.asset_id AND a.version_id=v.id AND a.status='AVAILABLE' AND a.sha256=v.sha256 AND a.size_bytes=v.size_bytes
 WHERE i.operation_id=selected_operation AND o.status IN('SUCCEEDED','FAILED')
 ON CONFLICT DO NOTHING;
$$;
CREATE FUNCTION pf.seal_completed_operation_artifacts() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
BEGIN
 PERFORM pf.seal_operation_artifacts(NEW.id);
 RETURN NULL;
END $$;
CREATE TRIGGER completed_operation_artifacts AFTER UPDATE OF status ON pf.tool_operation
 FOR EACH ROW WHEN (NEW.status IN('SUCCEEDED','FAILED') AND OLD.status IS DISTINCT FROM NEW.status)
 EXECUTE FUNCTION pf.seal_completed_operation_artifacts();
SELECT pf.seal_operation_artifacts(id) FROM pf.tool_operation WHERE status IN('SUCCEEDED','FAILED');
REVOKE ALL ON FUNCTION pf.seal_operation_artifacts(uuid),pf.seal_completed_operation_artifacts() FROM PUBLIC;
CREATE OR REPLACE FUNCTION pf.workspace_tool_public_artifacts(actor uuid,selected_space uuid,selected_operation uuid)
 RETURNS TABLE(file_id uuid,version_id uuid,path text,bytes bigint,sha256 text)
 LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,pf AS $$
 SELECT a.file_id,a.version_id,a.title,a.size_bytes,a.sha256::text FROM pf.artifact a
 JOIN pf.task t ON t.id=a.task_id AND t.space_id=a.space_id AND t.creator_id=actor
 JOIN pf.file_version v ON v.space_id=a.space_id AND v.id=a.version_id AND v.file_id=a.file_id AND v.workspace_id=a.workspace_id
 JOIN pf.asset_object b ON b.space_id=a.space_id AND b.id=a.asset_id AND b.status='AVAILABLE' AND b.version_id=v.id AND b.sha256=a.sha256 AND b.size_bytes=a.size_bytes
 WHERE a.operation_id=selected_operation AND a.space_id=selected_space AND pf.authorize_workspace_actor(actor,selected_space)
 ORDER BY a.id
$$;
ALTER TABLE pf.file_operation DROP CONSTRAINT file_operation_kind_check;
ALTER TABLE pf.file_operation ADD CONSTRAINT file_operation_kind_check CHECK(kind IN('APPLY_UPLOAD','SCAN','CAPTURE','MOVE','DELETE','DELETE_CLEANUP','RESTORE_FILE','RESTORE_TREE'));
ALTER TABLE pf.file_operation DROP CONSTRAINT file_operation_check;
ALTER TABLE pf.file_operation ADD CONSTRAINT file_operation_check CHECK((kind IN('APPLY_UPLOAD','RESTORE_FILE'))=(upload_id IS NOT NULL));
ALTER TABLE pf.file_runner_receipt DROP CONSTRAINT file_runner_receipt_result_state_check;
ALTER TABLE pf.file_runner_receipt ADD CONSTRAINT file_runner_receipt_result_state_check CHECK(result_state IN('APPLIED','NOT_APPLIED','UNKNOWN','SCANNED','CAPTURED','MUTATED'));

CREATE FUNCTION pf.queue_file_delete_cleanup(parent_id uuid,child_id uuid,child_command uuid,child_request uuid) RETURNS integer
 LANGUAGE plpgsql SET search_path=pg_catalog,pf AS $$
DECLARE parent pf.file_operation; intent pf.file_mutation_intent;
BEGIN
 SELECT * INTO parent FROM pf.file_operation WHERE id=parent_id AND kind='DELETE' AND status='COMMITTED' FOR UPDATE;
 IF NOT FOUND THEN RETURN 0; END IF;
 SELECT * INTO intent FROM pf.file_mutation_intent WHERE operation_id=parent_id;
 IF NOT EXISTS(SELECT 1 FROM pf.file_runner_receipt r WHERE r.command_id=parent.command_id AND r.result_state='MUTATED'
  AND(r.stop_text::jsonb->>'execStopped')::boolean AND(r.stop_text::jsonb->>'callbacksStopped')::boolean)
  OR NOT EXISTS(SELECT 1 FROM pf.workspace_file_lease l WHERE l.operation_id=parent.id AND l.owner_id=parent.owner_id
  AND l.fence=parent.fence AND l.status IN('ACTIVE','BLOCKED')) THEN RETURN 0; END IF;
 INSERT INTO pf.file_operation(id,space_id,workspace_id,actor_id,kind,command_id,generation,status,helper_image_id,helper_policy_hash)
 VALUES(child_id,parent.space_id,parent.workspace_id,parent.actor_id,'DELETE_CLEANUP',child_command,1,'PENDING',parent.helper_image_id,parent.helper_policy_hash);
 INSERT INTO pf.file_mutation_intent(operation_id,space_id,workspace_id,actor_id,request_id,request_hash,kind,root_file_id,
  source_path,target_path,expected_revision,manifest_json,manifest_hash,entry_count,parent_operation_id)
 VALUES(child_id,intent.space_id,intent.workspace_id,intent.actor_id,child_request,intent.request_hash,'DELETE_CLEANUP',intent.root_file_id,
  intent.source_path,NULL,intent.expected_revision,intent.manifest_json,intent.manifest_hash,intent.entry_count,parent_id);
 INSERT INTO pf.file_mutation_item(space_id,workspace_id,operation_id,ordinal,file_id,kind,source_path,target_path,expected_revision,version_id,sha256,size_bytes)
 SELECT space_id,workspace_id,child_id,ordinal,file_id,kind,source_path,target_path,expected_revision,version_id,sha256,size_bytes FROM pf.file_mutation_item WHERE operation_id=parent_id;
 INSERT INTO pf.file_outbox(id,space_id,workspace_id,operation_id,generation,status)
 VALUES(child_request,parent.space_id,parent.workspace_id,child_id,1,'PENDING');
 UPDATE pf.workspace_file_lease SET operation_id=child_id,status='BLOCKED',lease_until=clock_timestamp()+interval '60 seconds',revision=revision+1
 WHERE operation_id=parent_id AND owner_id=parent.owner_id AND fence=parent.fence;
 RETURN 1;
END $$;
REVOKE ALL ON FUNCTION pf.queue_file_delete_cleanup(uuid,uuid,uuid,uuid) FROM PUBLIC;
