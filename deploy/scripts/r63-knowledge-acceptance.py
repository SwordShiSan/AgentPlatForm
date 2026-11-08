"""Isolated R6.3 real API acceptance for knowledge indexing and memory lifecycle.

Uses only the R63 local fixture. Request bodies and credentials are never written
to the journal; mutations are frozen by identity and are never replayed blindly.
"""
from pathlib import Path
import hashlib, http.cookiejar, json, os, subprocess, time, urllib.error, urllib.request, uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r63-local'
PRIVATE = DATA / 'fixture'
DATABASE = json.loads((PRIVATE/'manifest.json').read_text(encoding='utf-8'))['database']
BASE = 'http://127.0.0.1:' + str(json.loads((PRIVATE/'manifest.json').read_text())['gatewayPort'])
JOURNAL_PATH = DATA / f'knowledge-acceptance-{DATABASE}.json'
RESULT_PATH = DATA / f'knowledge-acceptance-result-{DATABASE}.json'
NETWORK = 'agentplatform-r63-local_r63'
POSTGRES = 'agentplatform-r63-local-postgres-1'

class Failure(RuntimeError):
    def __init__(self, status, code): self.status=status; self.code=code; super().__init__(f'{status} {code}')

class Client:
    def __init__(self, username='r63_admin'):
        self.jar=http.cookiejar.CookieJar()
        self.http=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf=None; self.space=None
        self.call('GET','/api/v1/auth/csrf')
        self.csrf=self.last['csrfToken']
        self.call('POST','/api/v1/auth/login',{'username':username,'password':(PRIVATE/(('admin' if username=='r63_admin' else 'user')+'-password')).read_text().strip()})
        self.csrf=self.call('GET','/api/v1/auth/csrf')['csrfToken']
        self.me=self.call('GET','/api/v1/me')
        self.space=next(item['id'] for item in self.me['spaces'] if item['type']=='PERSONAL')
        self.last=self.me
    def call(self, method, path, body=None, headers=None, binary=False):
        data=body if isinstance(body,bytes) else json.dumps(body,ensure_ascii=False).encode() if body is not None else None
        h={'Origin':BASE, **({'X-Space-Id':self.space} if self.space else {}), **({'X-CSRF-Token':self.csrf} if self.csrf and method!='GET' else {}),
           **({'Content-Type':'application/json'} if body is not None and not isinstance(body,bytes) else {}), **(headers or {})}
        req=urllib.request.Request(BASE+path,data=data,method=method,headers=h)
        try:
            with self.http.open(req,timeout=130) as res:
                raw=res.read(); self.last=dict(res.headers)
                if binary:return res.status,raw,self.last
                self.last=json.loads(raw) if raw else None; return self.last
        except urllib.error.HTTPError as err:
            raw=err.read(65536)
            try: code=json.loads(raw).get('code','HTTP_ERROR')
            except ValueError: code='NON_JSON_HTTP'
            raise Failure(err.code,code) from None

class Journal:
    def __init__(self): self.rows=json.loads(JOURNAL_PATH.read_text()) if JOURNAL_PATH.exists() else {}; self.flush()
    def flush(self):
        tmp=JOURNAL_PATH.with_suffix('.tmp');tmp.write_text(json.dumps(self.rows,indent=2)+'\n');tmp.replace(JOURNAL_PATH)
    def once(self, client, key, method, path, body=None):
        digest=hashlib.sha256(json.dumps(body,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
        row=self.rows.get(key)
        if row:
            assert row['requestSha256']==digest, 'Frozen request input changed'
            if row['phase']=='ACKNOWLEDGED': return row['response']
            raise RuntimeError('Original mutation has an unknown outcome; query its persisted identity, do not resend')
        row={'idempotencyKey':str(uuid.uuid4()),'requestSha256':digest,'phase':'SENT'};self.rows[key]=row;self.flush()
        value=client.call(method,path,body,{'Idempotency-Key':row['idempotencyKey']})
        row.update(phase='ACKNOWLEDGED',response=value);self.flush();return value

def wait(client,path,states,timeout=150):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        row=client.call('GET',path)
        if row['status'] in states:return row
        if row.get('status') in {'FAILED','BLOCKED'}: raise RuntimeError('Operation '+row['status']+': '+str(row.get('errorCode')))
        time.sleep(1)
    raise RuntimeError('Operation remains pending; original identity retained: '+path)

def seed_preextracted_fixture(source_record, source):
    """Seed only a visibly labeled text-extraction fact for the indexing pipeline.

    The physical document-renderer path is deliberately not represented by this fixture.
    """
    actor=str(uuid.UUID(source_record['actorId']));space=str(uuid.UUID(source_record['spaceId']))
    attachment=str(uuid.UUID(source_record['attachmentId']))
    digest=hashlib.sha256(source).hexdigest()
    extraction=str(uuid.uuid5(uuid.NAMESPACE_URL,'r63-extraction:'+digest))
    asset=str(uuid.uuid5(uuid.NAMESPACE_URL,'r63-extracted-asset:'+digest))
    command=str(uuid.uuid5(uuid.NAMESPACE_URL,'r63-extraction-command:'+digest))
    marker=DATA/f'knowledge-extraction-fixture-{DATABASE}.json'
    config_id=json.loads((DATA/'images.json').read_text())['images']['storage-probe']['configId']
    image_sha=config_id.removeprefix('sha256:')
    key=f'spaces/{space}/text-extraction/{asset}/{extraction}/r63-fixture.txt'
    text_path=DATA/'r63-extracted-text.txt';text_path.write_bytes(source)
    probe_id=config_id
    if not probe_id.startswith('sha256:') or len(image_sha)!=64:raise RuntimeError('Pinned fixture exporter identity is invalid')
    command_args=['docker','run','--rm','--network',NETWORK,'--memory','128m','--memory-swap','128m','--cpus','1','--pids-limit','64',
        '--cap-drop','ALL','--security-opt','no-new-privileges:true','--read-only','-e','R63_OBJECT_KEY='+key,
        '--mount','type=bind,src='+str((PRIVATE/'s3-access').resolve())+',dst=/fixture/s3-access,readonly',
        '--mount','type=bind,src='+str((PRIVATE/'s3-secret').resolve())+',dst=/fixture/s3-secret,readonly',
        '--mount','type=bind,src='+str(text_path.resolve())+',dst=/fixture/extracted.txt,readonly',
        '--entrypoint','python3',probe_id,'/r63-put-extracted.py']
    probe_script=(ROOT/'deploy/probes/r63-put-extracted.py').resolve()
    command_args[command_args.index('--entrypoint'):command_args.index('--entrypoint')]=[
        '--mount','type=bind,src='+str(probe_script)+',dst=/r63-put-extracted.py,readonly']
    outcome=subprocess.run(command_args,capture_output=True,text=True,timeout=90)
    if outcome.returncode:raise RuntimeError('R63 extracted-text object fixture failed; details are in the protected local process output')

    def lit(value):return "'"+str(value).replace("'","''")+"'"
    sql=subprocess.run(['docker','exec','-i',POSTGRES,'psql','-v','ON_ERROR_STOP=1','-X','-A','-F','|','-t','-U','postgres','-d',DATABASE],
                       input=f"SELECT a.id,a.object_key,a.sha256,a.size_bytes FROM pf.chat_attachment c JOIN pf.asset_object a ON a.space_id=c.space_id AND a.id=c.asset_id WHERE c.id={lit(attachment)};\n",
                       capture_output=True,text=True,timeout=30)
    if sql.returncode:raise RuntimeError('Cannot read the isolated source attachment identity')
    source_rows=[line.strip() for line in sql.stdout.splitlines() if line.strip()]
    if len(source_rows)!=1:raise RuntimeError('The source attachment is unavailable in the local fixture')
    source_asset,source_key,source_sha,source_bytes=source_rows[0].split('|')
    if source_sha!=source_record['sourceSha256'] or int(source_bytes)!=len(source):raise RuntimeError('Source attachment identity changed')
    sql_text=f"""
SELECT EXISTS(SELECT 1 FROM pf.document_extraction WHERE id={lit(extraction)});
"""
    check=subprocess.run(['docker','exec','-i',POSTGRES,'psql','-v','ON_ERROR_STOP=1','-X','-A','-t','-U','postgres','-d',DATABASE],
                         input=sql_text,capture_output=True,text=True,timeout=30)
    if check.returncode:raise RuntimeError('Cannot check local extraction fixture state')
    if check.stdout.strip() not in {'t','f'}:raise RuntimeError('Unexpected local extraction fixture state')
    if check.stdout.strip()=='f':
        out_bytes=len(source)
        insert=f"""
BEGIN;
INSERT INTO pf.asset_object(id,space_id,category,version_id,object_key,sha256,size_bytes,mime,status,created_by)
VALUES({lit(asset)},{lit(space)},'text-extraction',{lit(extraction)},{lit(key)},{lit(digest)},{out_bytes},'text/plain; charset=utf-8','AVAILABLE',{lit(actor)});
INSERT INTO pf.document_extraction(id,space_id,actor_id,source_kind,workspace_id,file_id,version_id,attachment_id,source_asset_id,source_sha256,source_bytes,
 source_format,source_object_key,output_object_key,image_id,parser_bundle,policy_hash,status,command_id,output_asset_id,output_sha256,output_bytes,
 name,requested_format,reserved_asset_id,format,media_type)
VALUES({lit(extraction)},{lit(space)},{lit(actor)},'CHAT_ATTACHMENT',NULL,NULL,NULL,{lit(attachment)},{lit(source_asset)},{lit(source_sha)},{int(source_bytes)},
 'TEXT',{lit(source_key)},{lit(key)},{lit('sha256:'+image_sha)},'r63-fixture-pre-extracted-text-v1',{lit(hashlib.sha256(b'r63-fixture-policy-v1').hexdigest())},'READY',{lit(command)},
 {lit(asset)},{lit(digest)},{out_bytes},{lit(source_record['filename'])},'TEXT_EXTRACT',{lit(asset)},'TEXT','text/plain');
INSERT INTO pf.document_extraction_asset_reference(space_id,extraction_id,asset_id,source_referenced)
VALUES({lit(space)},{lit(extraction)},{lit(asset)},false);
INSERT INTO pf.document_extraction_reservation(extraction_id,space_id,bytes,status)
VALUES({lit(extraction)},{lit(space)},{out_bytes},'SETTLED');
UPDATE pf.space_storage SET committed_bytes=committed_bytes+{out_bytes},version=version+1 WHERE space_id={lit(space)};
COMMIT;
"""
        seeded=subprocess.run(['docker','exec','-i',POSTGRES,'psql','-v','ON_ERROR_STOP=1','-X','-q','-U','postgres','-d',DATABASE],
                              input=insert,capture_output=True,text=True,timeout=30)
        if seeded.returncode:raise RuntimeError('Cannot seed the clearly labeled pre-extracted indexing fixture')
    else:
        refreshed=subprocess.run(['docker','exec','-i',POSTGRES,'psql','-v','ON_ERROR_STOP=1','-X','-q','-U','postgres','-d',DATABASE],
            input=f"UPDATE pf.document_extraction SET image_id={lit('sha256:'+image_sha)} WHERE id={lit(extraction)} AND parser_bundle='r63-fixture-pre-extracted-text-v1' AND status='READY' AND NOT EXISTS(SELECT 1 FROM pf.knowledge_index_build b JOIN pf.knowledge_document d ON d.id=b.document_id WHERE d.extraction_id={lit(extraction)} AND b.state='COMMITTED');\n",
            capture_output=True,text=True,timeout=30)
        if refreshed.returncode:raise RuntimeError('Cannot verify fixture exporter identity')
    row={'status':'FIXTURE_ONLY','extractionId':extraction,'attachmentId':attachment,'outputSha256':digest,
         'outputBytes':len(source),'objectKey':key,'parserBundle':'r63-fixture-pre-extracted-text-v1','exporterImageConfigId':probe_id,
         'physicalRendererExecuted':False}
    if marker.exists():
        prior=json.loads(marker.read_text())
        if {k:v for k,v in prior.items() if k!='exporterImageConfigId'}!={k:v for k,v in row.items() if k!='exporterImageConfigId'}:
            raise RuntimeError('Frozen indexing fixture identity changed')
    marker.write_text(json.dumps(row,indent=2)+'\n')
    return row

def main():
    client=Client(); foreign=Client('r63_user'); journal=Journal()
    source=('R63_NORTHSTAR_731\n北极星流程：先确认实时权限，再执行持久化修改。\n'
            'Knowledge indexing uses immutable source versions and must preserve page or text offsets.\n').encode()
    source_hash=hashlib.sha256(source).hexdigest(); name='r63-knowledge-evidence.txt'
    draft=str(uuid.uuid5(uuid.NAMESPACE_URL,'r63:'+source_hash))
    created=journal.once(client,'attachment-create-enabled','POST','/api/v1/attachment-uploads',
        {'draftId':draft,'filename':name,'bytes':len(source),'sha256':source_hash})
    upload_id=created['id']
    upload=client.call('GET','/api/v1/attachment-uploads/'+upload_id)
    if upload['status']=='CREATED':
        client.call('PUT','/api/v1/attachment-uploads/'+upload_id+'/content',source,{'Content-Type':'application/octet-stream'})
        upload=client.call('GET','/api/v1/attachment-uploads/'+upload_id)
    if upload['status']=='VERIFIED':client.call('POST','/api/v1/attachment-uploads/'+upload_id+'/complete')
    deadline=time.monotonic()+60
    while time.monotonic()<deadline:
        upload=client.call('GET','/api/v1/attachment-uploads/'+upload_id)
        if upload['status']=='COMMITTED':break
        if upload['status'] in {'ABORTED','EXPIRED','UNKNOWN'}:raise RuntimeError('Attachment upload '+upload['status'])
        time.sleep(1)
    else:raise RuntimeError('Attachment commit is still pending')
    attachment_id=upload['attachmentId']
    attachment=client.call('GET','/api/v1/attachments/'+attachment_id)
    assert attachment['status']=='AVAILABLE' and attachment['sha256']==source_hash and attachment['conversationId'] is None
    actual_status,actual_content,_=client.call('GET','/api/v1/attachments/'+attachment_id+'/content',binary=True)
    assert actual_status==200 and actual_content==source
    try: foreign.call('GET','/api/v1/attachments/'+attachment_id)
    except Failure as denied: assert denied.status in (403,404)
    else: raise AssertionError('Attachment crossed personal-space boundary')
    source_record={'attachmentId':attachment_id,'actorId':client.me['id'],'spaceId':client.space,'filename':name,
                   'sourceBytes':len(source),'sourceSha256':source_hash}
    (DATA/f'knowledge-source-{DATABASE}.json').write_text(json.dumps(source_record,indent=2)+'\n')
    extraction=seed_preextracted_fixture(source_record,source)
    base=journal.once(client,'knowledge-base','POST','/api/v1/knowledge-bases',{'name':'R63 acceptance '+source_hash[:12]})
    added=journal.once(client,'knowledge-document','POST','/api/v1/knowledge-bases/'+base['id']+'/documents',{'extractionId':extraction['extractionId']})
    document=None
    existing_docs=client.call('GET','/api/v1/knowledge-bases/'+base['id']+'/documents')
    existing=next((row for row in existing_docs if row['id']==added['id']),None)
    if existing and existing['state']=='FAILED':
        journal.once(client,'knowledge-document-rebuild-qualified-vector','POST','/api/v1/knowledge-documents/'+added['id']+'/rebuild')
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        docs=client.call('GET','/api/v1/knowledge-bases/'+base['id']+'/documents')
        document=next((row for row in docs if row['id']==added['id']),None)
        if document and document['state']=='READY':break
        if document and document['state'] in {'FAILED','BLOCKED'}:raise RuntimeError('Knowledge index '+document['state'])
        time.sleep(1)
    else:raise RuntimeError('Knowledge index build remains pending; original build identity retained')
    assert document['activeIndexVersion']
    relevant=client.call('POST','/api/v1/knowledge/search',{'query':'北极星流程 实时权限 持久化修改','baseIds':[base['id']],'limit':8,'semanticRequired':True})
    assert relevant['semanticAvailable'] and any('R63_NORTHSTAR_731' in hit['text'] for hit in relevant['hits'])
    no_answer=client.call('POST','/api/v1/knowledge/search',{'query':'How do I bake sourdough bread in a wood oven?','baseIds':[base['id']],'limit':8,'semanticRequired':True})
    assert no_answer['hits']==[], 'Unrelated query must not fabricate a knowledge reference'
    prior_result=json.loads(RESULT_PATH.read_text(encoding='utf-8')) if RESULT_PATH.exists() else None
    if prior_result and prior_result.get('status')=='PASS' and prior_result.get('memoryLifecycle')=='PENDING->ACTIVE->DELETED':
        # The journal deliberately reuses the original idempotency keys. Once that
        # lifecycle reached DELETED, replaying its response cannot recreate an
        # ACTIVE memory. Recheck the terminal state without inventing a new write.
        memory_id=prior_result['memoryId']
        visible=client.call('GET','/api/v1/me/memories?status=ACTIVE')
        foreign_rows=foreign.call('GET','/api/v1/me/memories?status=ACTIVE')
        assert all(row['id']!=memory_id for row in visible+foreign_rows)
        memory_lifecycle=prior_result['memoryLifecycle']
    else:
        candidate=journal.once(client,'memory-candidate-r4','POST','/api/v1/me/memories/candidates',
            {'scopeKind':'PERSONAL','workspaceId':None,'category':'PREFERENCE','topicKey':'r63-test-language','content':'Respond in concise Chinese for this isolated acceptance fixture.'})
        assert candidate['status']=='PENDING'
        confirmed=journal.once(client,'memory-confirm-r4','POST','/api/v1/me/memories/'+candidate['id']+'/actions',
            {'action':'CONFIRM','expectedRevision':candidate['revision']})
        assert confirmed['status']=='ACTIVE' and confirmed['confirmedAt']
        visible=client.call('GET','/api/v1/me/memories?status=ACTIVE')
        assert any(row['id']==confirmed['id'] for row in visible)
        foreign_rows=foreign.call('GET','/api/v1/me/memories?status=ACTIVE')
        assert all(row['id']!=confirmed['id'] for row in foreign_rows)
        memory_id=confirmed['id']
        memory_lifecycle='PENDING->ACTIVE'
    conversation=journal.once(client,'context-evidence-conversation-r2','POST','/api/v1/conversations',
        {'title':'R63 context evidence acceptance','initialTask':{'input':{'text':'fixture:r63-context-evidence 北极星流程 实时权限 执行持久化','attachments':[]},
            'options':{'planMode':False,'permissionMode':'RISK_BASED'}}})
    accepted=conversation['initialTask'];task_id=accepted['taskId']
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        task=client.call('GET','/api/v1/tasks/'+task_id)
        if task['status']=='SUCCEEDED':break
        if task['status'] in {'FAILED','BLOCKED','CANCELLED'}:raise RuntimeError('Context evidence task '+task['status']+': '+str(task.get('reasonCode')))
        time.sleep(1)
    else:raise RuntimeError('Context evidence task did not complete; original task identity retained')
    snapshot=client.call('GET','/api/v1/conversations/'+conversation['id']+'/snapshot')
    assistant_text='\n'.join(block.get('text','') for message in snapshot['messages'] if message['role']=='ASSISTANT' and message['taskId']==task_id
                              for block in message['blocks'] if block['type']=='TEXT')
    assert 'context-evidence-memory=1;knowledge=1' in assistant_text, 'Protected task must receive current personal memory and authorized knowledge evidence'
    if memory_lifecycle=='PENDING->ACTIVE':
        deleted=journal.once(client,'memory-delete-r4','POST','/api/v1/me/memories/'+confirmed['id']+'/actions',
            {'action':'DELETE','expectedRevision':confirmed['revision']})
        assert deleted['status']=='DELETED'
        memory_lifecycle='PENDING->ACTIVE->DELETED'
    result={'status':'PASS','database':DATABASE,'apiReadiness':'READY','attachmentState':'AVAILABLE','sourceBytes':len(source),'sourceSha256':source_hash,
            'attachmentRoundTripSha256':hashlib.sha256(actual_content).hexdigest(),'memoryLifecycle':memory_lifecycle,
            'taskContextEvidence':'PERSONAL_MEMORY+KNOWLEDGE',
            'supplierCalls':0,'attachmentId':attachment_id,'memoryId':memory_id,'knowledgeIndexState':document['state'],
            'indexVersion':document['activeIndexVersion'],'relevantSearchHits':len(relevant['hits']),'noAnswerHits':len(no_answer['hits']),
            'noAnswerFixtureEmpty':len(no_answer['hits'])==0,'extractionFixture':extraction}
    RESULT_PATH.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print('R63_REAL_ATTACHMENT_S3_ROUNDTRIP_MEMORY_LIFECYCLE_PASS')

if __name__=='__main__':main()
