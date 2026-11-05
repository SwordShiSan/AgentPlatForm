"""Journaled R5 HTTP smoke inside the closed maintenance window.

Uses existing protected production probe accounts and encrypted credentials.
No local accounts/keys/databases are imported. Real Flash calls are opt-in and
counted separately from business limits; an uncertain request is never replayed.
"""
from pathlib import Path
import argparse,hashlib,http.cookiejar,importlib.util,json,subprocess,time,urllib.error,urllib.request,uuid

ORIGIN='http://agentplatform.lailihao.cloud'


class Api:
    def __init__(self,account):
        self.http=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf='';self.space=None
        self.csrf=self.request('GET','/auth/csrf')[1]['csrfToken']
        self.request('POST','/auth/login',{'username':account['username'],'password':account['password']})
        self.csrf=self.request('GET','/auth/csrf')[1]['csrfToken'];self.me=self.request('GET','/me')[1]
        self.space=next(s['id'] for s in self.me['spaces'] if s['type']=='PERSONAL')
    def request(self,method,path,body=None,headers=None,binary=False):
        raw=isinstance(body,bytes);data=body if raw else json.dumps(body,ensure_ascii=False).encode() if body is not None else b'' if method not in ['GET','HEAD'] else None
        h={'Host':'agentplatform.lailihao.cloud','Origin':ORIGIN,'X-CSRF-Token':self.csrf,**({'X-Space-Id':self.space} if self.space else {}),'Content-Type':'application/octet-stream' if raw else 'application/json',**(headers or {})}
        try:
            with self.http.open(urllib.request.Request('http://127.0.0.1/api/v1'+path,data=data,method=method,headers=h),timeout=135) as response:
                value=response.read(104857601);assert len(value)<=104857600
                return response.status,value if binary else json.loads(value) if value else {},dict(response.headers)
        except urllib.error.HTTPError as failure:
            data=failure.read(65536)
            try:code=json.loads(data).get('code','HTTP_ERROR')
            except ValueError:code='NON_JSON_HTTP'
            raise Failure(failure.code,code) from None


class Failure(Exception):
    def __init__(self,status,code):self.status=status;self.code=code;super().__init__(str(status)+' '+code)


class Journal:
    def __init__(self,root):
        self.path=root/'smoke-journal.private.json';self.rows=json.loads(self.path.read_text()) if self.path.exists() else {}
    def save(self):
        temp=self.path.with_suffix('.tmp');temp.write_text(json.dumps(self.rows,indent=2));temp.chmod(0o600);temp.replace(self.path)
    def freeze(self,name,request):
        old=self.rows.get(name)
        if old:assert old['request']==request;return old
        row={'request':request,'key':str(uuid.uuid4()),'phase':'FROZEN'};self.rows[name]=row;self.save();return row
    def once(self,api,name,method,path,body=None,headers=None):
        request={'method':method,'path':path,'body':body,'headers':headers or {}}
        row=self.freeze(name,request)
        if 'response' in row:return row['response']
        assert row['phase']=='FROZEN','Query the original uncertain smoke mutation: '+name
        row['phase']='SENT';self.save()
        try:status,value,_=api.request(method,path,body,{'Idempotency-Key':row['key'],**(headers or {})})
        except Exception as failure:
            row['phase']='REJECTED' if isinstance(failure,Failure) and 400<=failure.status<500 else 'UNKNOWN';self.save();raise
        row.update(phase='ACKNOWLEDGED',response=value,status=status);self.save();return value


def wait(api,path,field='status',terminal=('READY','FAILED','BLOCKED')):
    for _ in range(140):
        value=api.request('GET',path)[1]
        if value[field] in terminal:return value
        time.sleep(1)
    raise RuntimeError('Query the original pending operation: '+path)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('folder',type=Path);parser.add_argument('--real-model',action='store_true');parser.add_argument('--final',action='store_true');args=parser.parse_args()
    root=args.folder.resolve(strict=True);assert root.parent==Path('/srv/agentplatform/operations') and root.name.startswith('r5-release-')
    assert not (root/'writes-open-intent.json').exists(),'Smoke mutations run before real writes open'
    spec=importlib.util.spec_from_file_location('release',root/'r5-release-control.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);window=mod.Window(root)
    accounts=json.loads((root/'probe-accounts.private.json').read_text());api=Api(accounts[0]);foreign=Api(accounts[1]);journal=Journal(root)
    assert window.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;')=='21'
    if args.final:
        prior=json.loads((root/'smoke.json').read_text());assert prior['status']=='PASS'
        source=api.request('GET','/attachments/'+prior['attachmentId'])[1];raw=api.request('GET','/attachments/'+source['id']+'/content',binary=True)[1]
        assert hashlib.sha256(raw).hexdigest()==source['sha256']
        assert api.request('GET','/tasks/'+prior['taskId'])[1]['status']=='SUCCEEDED'
        preview=api.request('GET','/previews/'+prior['previewId'])[1];assert preview['state']=='READY'
        window.receipt('final-smoke',{'status':'PASS','scope':'Same restored R5 identity, pinned attachment/task/preview hashes; no new model request','supplierCalls':0});return
    original=b'R5_FIXED_SOURCE_666\nAn immutable attachment acceptance source.\n'
    digest=hashlib.sha256(original).hexdigest();seed=journal.freeze('attachment-seed',{'name':'r5-release-source.txt','sha256':digest,'bytes':len(original)})
    if 'draftId' not in seed:seed['draftId']=str(uuid.uuid4());journal.save()
    upload=journal.once(api,'attachment','POST','/attachment-uploads',{'draftId':seed['draftId'],'filename':'r5-release-source.txt','sha256':digest,'bytes':len(original)})
    state=api.request('GET','/attachment-uploads/'+upload['id'])[1]
    transport=journal.freeze('attachment-content',{'uploadId':upload['id'],'sha256':digest})
    if state['status']=='CREATED':
        assert transport['phase']=='FROZEN';transport['phase']='SENT';journal.save()
        api.request('PUT','/attachment-uploads/'+upload['id']+'/content',original)
        transport['phase']='ACKNOWLEDGED';journal.save()
    attachment=journal.once(api,'attachment-complete','POST','/attachment-uploads/'+upload['id']+'/complete')
    aid=attachment.get('attachmentId') or api.request('GET','/attachment-uploads/'+upload['id'])[1]['attachmentId']
    extracted=journal.once(api,'extract','POST','/file-extractions',{'attachmentId':aid});extracted=wait(api,'/file-extractions/'+extracted['id']);assert extracted['status']=='READY'
    actual=api.request('GET','/attachments/'+aid+'/content',binary=True)[1];assert actual==original
    for path in ['/attachments/'+aid,'/attachments/'+aid+'/content','/file-extractions/'+extracted['id']]:
        try:foreign.request('GET',path);raise AssertionError('Foreign account read the source')
        except Failure as failure:assert failure.status in [403,404]
    docx=(root/'sample.docx').read_bytes();docsha=hashlib.sha256(docx).hexdigest()
    dseed=journal.freeze('office-seed',{'sha256':docsha,'bytes':len(docx)})
    if 'draftId' not in dseed:dseed['draftId']=str(uuid.uuid4());journal.save()
    office=journal.once(api,'office-upload','POST','/attachment-uploads',{'draftId':dseed['draftId'],'filename':'release.docx','sha256':docsha,'bytes':len(docx)})
    current=api.request('GET','/attachment-uploads/'+office['id'])[1]
    sent=journal.freeze('office-content',{'uploadId':office['id'],'sha256':docsha})
    if current['status']=='CREATED':
        assert sent['phase']=='FROZEN';sent['phase']='SENT';journal.save();api.request('PUT','/attachment-uploads/'+office['id']+'/content',docx);sent['phase']='ACKNOWLEDGED';journal.save()
    office=journal.once(api,'office-complete','POST','/attachment-uploads/'+office['id']+'/complete');office_id=office['id']
    preview=journal.once(api,'office-preview','POST','/attachments/'+office_id+'/previews',{'format':'PDF'})
    preview=wait(api,'/previews/'+preview['id'],field='state');assert preview['state']=='READY',preview.get('errorCode')
    content=api.request('GET','/previews/'+preview['id']+'/content',binary=True)[1];assert content.startswith(b'%PDF-') and hashlib.sha256(content).hexdigest()==preview['sha256']
    task_id=None;physical=0
    if args.real_model:
        meter=journal.freeze('real-model-meter',{'maximum':20,'scope':'R54 local and cloud real supplier calls; deterministic calls excluded'})
        if 'baseline' not in meter:meter['baseline']=int(window.sql("SELECT count(*) FROM pf.model_call WHERE provider_kind='REAL';"));journal.save()
        def calls():
            observed=int(window.sql("SELECT count(*) FROM pf.model_call WHERE provider_kind='REAL';"))-meter['baseline'];assert 0<=observed<=20;meter['physicalCalls']=max(meter.get('physicalCalls',0),observed);journal.save();return observed
        assert calls()<=16
        body={'title':'R5 release attachment','initialTask':{'input':{'text':'请阅读附件，只回答其中的标记 R5_FIXED_SOURCE_666，不调用工具。','attachments':[{'kind':'CHAT_ATTACHMENT','attachmentId':aid,'extractionId':extracted['id']}]},'options':{'modelId':'0199e000-0000-7000-8000-000000000002','credentialSource':'PLATFORM','planMode':False,'permissionMode':'FULL'}}}
        conversation=journal.once(api,'real-conversation','POST','/conversations',body);task_id=conversation['initialTask']['taskId']
        task=wait(api,'/tasks/'+task_id,terminal=('SUCCEEDED','FAILED','BLOCKED','CANCELLED'));physical=calls()
        assert task['status']=='SUCCEEDED',task.get('reasonCode')
        configuration=api.request('GET','/tasks/'+task_id+'/configuration')[1]
        assert configuration['values']['window']==262144 and configuration['values']['maxOutput']==4096
        snapshot=api.request('GET','/conversations/'+conversation['id']+'/snapshot')[1];assert 'R5_FIXED_SOURCE_666' in json.dumps(snapshot)
        second={'input':{'text':'沿用上一轮附件，只回复其中的数字标记，不重新上传。'},'options':{'modelId':'0199e000-0000-7000-8000-000000000002','credentialSource':'PLATFORM','planMode':False,'permissionMode':'FULL'}}
        assert calls()<=16
        follow=journal.once(api,'real-followup','POST','/conversations/'+conversation['id']+'/tasks',second)
        task=wait(api,'/tasks/'+follow['taskId'],terminal=('SUCCEEDED','FAILED','BLOCKED','CANCELLED'));physical=calls();assert task['status']=='SUCCEEDED',task.get('reasonCode')
        assert physical>=2
    assert task_id is not None,'Full release smoke requires necessary real Flash acceptance exactly once'
    window.receipt('smoke',{'status':'PASS','source':window.stage['source'],'migration':21,'attachmentId':aid,'extractionId':extracted['id'],'previewId':preview['id'],'taskId':task_id,'supplierCalls':physical,'assertions':['existing-production-identity-and-history','standalone-source-no-workspace','real-extraction-original-byte-hash','actual-runsc-office-pdf','cross-user-denial','real-flash-attachment-and-multiturn','frozen-source-and-context-policy']})
    print(json.dumps({'status':'PASS','supplierCalls':physical,'scope':'Maintenance fixture writes only; real users still closed'}))


if __name__=='__main__':main()
