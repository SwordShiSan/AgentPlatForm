"""R54 real attachment/extraction/chat acceptance through the existing encrypted tunnel.

Does not start/stop production, create a workspace for chat, or call a supplier.
Each mutation is journaled before transmission; uncertain operations are queried.
"""
from pathlib import Path
import hashlib, importlib.util, json, os, time, uuid

ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'.cache/r54-local'

def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'deploy/scripts'/file)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

common=module('r54_asset_acceptance','r53-native-acceptance.py')
common.DATA=DATA;common.FIXTURES=DATA/'preview-fixtures';common.api.configure('r54')
api=common.api

def wait(client,path,field='status',terminal=('READY','FAILED','BLOCKED')):
    for _ in range(145):
        value=common.get(client,path)[1]
        if value[field] in terminal:return value
        time.sleep(1)
    raise RuntimeError('Original operation unresolved; query its persisted identity: '+path)

def attachment(client,journal,name,content):
    digest=common.sha(content);namekey='attachment:'+name
    seed=journal.freeze(namekey,{'name':name,'bytes':len(content),'sha256':digest})
    if 'draftId' not in seed:seed['draftId']=str(uuid.uuid4());journal.write()
    body={'draftId':seed['draftId'],'filename':name,'bytes':len(content),'sha256':digest}
    created=journal.once(client,namekey+':create',{'method':'POST','path':'/api/v1/attachment-uploads','body':body},body)
    upload=common.get(client,'/api/v1/attachment-uploads/'+created['id'])[1]
    transport=journal.freeze(namekey+':content',{'uploadId':upload['id'],'bytes':len(content),'sha256':digest})
    if upload['status']=='CREATED':
        assert transport['phase']=='FROZEN','Uncertain transfer cannot be repeated'
        transport['phase']='SENT';journal.write()
        client.request('PUT','/api/v1/attachment-uploads/'+upload['id']+'/content',content,{'Content-Type':'application/octet-stream'})
        transport['phase']='ACKNOWLEDGED';journal.write()
        upload=common.get(client,'/api/v1/attachment-uploads/'+upload['id'])[1]
    if upload['status']=='VERIFIED':
        journal.once(client,namekey+':complete',{'method':'POST','path':'/api/v1/attachment-uploads/'+upload['id']+'/complete'})
        upload=common.get(client,'/api/v1/attachment-uploads/'+upload['id'])[1]
    assert upload['status']=='COMMITTED',upload['status']
    result=common.get(client,'/api/v1/attachments/'+upload['attachmentId'])[1]
    assert result['bytes']==len(content) and result['sha256']==digest and result['conversationId'] is None
    actual=common.get(client,'/api/v1/attachments/'+result['id']+'/content',binary=True)
    assert actual[1]==content and 'attachment' in common.header(actual[2],'Content-Disposition')
    return result

def extraction(client,journal,name,source):
    created=journal.once(client,'extract:'+name,{'method':'POST','path':'/api/v1/file-extractions','body':source},source)
    result=wait(client,'/api/v1/file-extractions/'+created['id'])
    assert result['status']=='READY' and 0<result['bytes']<=2097152,(name,result['status'],result['errorCode'])
    return result

def main():
    DATA.mkdir(parents=True,exist_ok=True)
    assert (DATA/'native-plan.json').is_file(),'Use the fixed prepare/start entry first'
    client=api.Client();foreign=api.Client('r54_user');journal=common.Journal();results={}
    baseline=common.get(client,'/api/v1/me/storage-usage')[1]
    for name in ['sample.docx','sample.xlsx','sample.pptx','sample.txt']:
        content=(common.FIXTURES/name).read_bytes()
        original=attachment(client,journal,name,content)
        text=extraction(client,journal,name,{'attachmentId':original['id']})
        assert text['attachmentId']==original['id'] and text['fileId'] is None and text['versionId'] is None
        results[name]={'attachment':original,'extraction':text}
        for path in ['/api/v1/attachments/'+original['id'],'/api/v1/attachments/'+original['id']+'/content','/api/v1/file-extractions/'+text['id']]:
            common.expect_error(lambda:foreign.request('GET',path),[403,404])
    # This body exceeds the removed 8K window; the real SDK/provider must receive its fixed source.
    large=('R54_FIXED_SOURCE_666\n'+'大上下文资料 abcdefg 1234567890\n'*9000).encode()
    original=attachment(client,journal,'large-context.txt',large)
    text=extraction(client,journal,'large-context',{'attachmentId':original['id']})
    refs=[{'kind':'CHAT_ATTACHMENT','attachmentId':original['id'],'extractionId':text['id']}]
    body={'title':'R54 attachment acceptance','initialTask':{'input':{'text':'fixture:r54-document 请核对固定资料标记','attachments':refs},'options':{'modelId':'0199e000-0000-7000-8000-000000000002','credentialSource':'PLATFORM','planMode':False,'permissionMode':'FULL'}}}
    request_identity='conversation:document:'+common.sha(json.dumps(body,sort_keys=True).encode())
    conversation=journal.once(client,request_identity,{'method':'POST','path':'/api/v1/conversations','body':body},body)
    task=wait(client,'/api/v1/tasks/'+conversation['initialTask']['taskId'],terminal=('SUCCEEDED','FAILED','BLOCKED','CANCELLED'))
    assert task['status']=='SUCCEEDED',task.get('reasonCode')
    snapshot=common.get(client,'/api/v1/conversations/'+conversation['id']+'/snapshot')[1]
    public=json.dumps(snapshot,ensure_ascii=False)
    assert 'R54_FIXED_SOURCE_666' in public and 'FILE_REFERENCE' in public
    assert 'FIXTURE_HIDDEN_REASONING' not in public and 'textObjectKey' not in public
    config=common.get(client,'/api/v1/tasks/'+task['taskId']+'/configuration')[1]
    assert config['values']['window']==262144 and config['values']['maxOutput']==4096
    assert config['contextUsage']['estimatedInputTokens']>8192
    # The server preferences remain metadata-only and use their original CAS revision.
    prefs=common.get(client,'/api/v1/me/preferences')[1]
    patched=client.request('PATCH','/api/v1/me/preferences',{'workbenchLayout':{'view':'SPLIT','sidebarWidth':384}}, {'If-Match':'"'+str(prefs['version'])+'"'})[1]
    assert patched['workbenchLayout']=={'view':'SPLIT','sidebarWidth':384}
    common.expect_error(lambda:client.request('PATCH','/api/v1/me/preferences',{'workbenchLayout':{'view':'FILES','sidebarWidth':384}},{'If-Match':'"'+str(prefs['version'])+'"'}),[412])
    # Ordinary attachment upload/extraction never allocates an XFS workspace or an environment.
    workspaces=common.get(client,'/api/v1/workspaces')[1]
    assert workspaces['items']==[]
    current=common.get(client,'/api/v1/me/storage-usage')[1]
    assert current['usedBytes']>=baseline['usedBytes'] and current['reservedBytes']==0
    common.atomic(DATA/'native-attachment-result.json',{'status':'PASS','supplierCalls':0,'source':json.loads((DATA/'native-plan.json').read_text())['source'],
        'scope':'Actual PG/MQ/S3/runner extraction for DOCX/XLSX/PPTX/text, no workspace allocation, large SDK chat, pinned public source, ACL and preference CAS',
        'attachmentIds':[row['attachment']['id'] for row in results.values()]+[original['id']],
        'taskId':task['taskId'],'conversationId':conversation['id'],'sourceBytes':len(large),'estimatedInputTokens':config['contextUsage']['estimatedInputTokens']})
    print('R54_NATIVE_ATTACHMENT_CONTEXT_PASS')

if __name__=='__main__':main()
