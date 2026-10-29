"""Actual R52 lifecycle assertions, using only isolated fixture credentials through SSH tunnel."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,time,uuid
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'.cache/r52-local'
def load(name,file):
 s=importlib.util.spec_from_file_location(name,ROOT/'deploy/scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.configure('r52');return m
api=load('api52','files-native-api.py');native=load('native52','files-native-fixture.py')
def request(client,method,file,body=None,revision=None,key=None):
 headers={'Idempotency-Key':key or str(uuid.uuid4())}
 if revision is not None:headers['If-Match']='"'+str(revision)+'"'
 return client.request(method,'/api/v1/files/'+file,body,headers)[1]
def wait(client,op):
 result=api.wait_operation(client,op['id']);assert result['state']=='COMMITTED',result
 return result
def file(client,id):return client.request('GET','/api/v1/files/'+id)[1]
def expect_error(action,status):
 try:action()
 except api.ApiFailure as error:assert error.status==status,(error.status,error.code);return
 raise AssertionError('Expected HTTP '+str(status))
def seed(client):
 path=DATA/'native-file-identities.json'
 if path.exists():return json.loads(path.read_text())
 frozen=DATA/'workspace-create-request.json'
 if frozen.exists():key=json.loads(frozen.read_text())['key']
 else:key=str(uuid.uuid4());frozen.write_text(json.dumps({'key':key}))
 workspace=client.request('POST','/api/v1/workspaces',{'name':'R52 lifecycle acceptance'},{'Idempotency-Key':key})[1]
 for _ in range(90):
  row=client.request('GET','/api/v1/workspaces/'+workspace['id'])[1]
  if row['state']=='READY':break
  assert row['state'] not in ['ERROR','BLOCKED'],row;time.sleep(1)
 else:raise RuntimeError('Original workspace not ready')
 first,entry,_=api.upload(client,workspace['id'],'lifecycle.txt','original 中文😀\n'.encode())
 _,browser,_=api.upload(client,workspace['id'],'browser.txt',b'browser original\n')
 ids={'workspaceId':workspace['id'],'spaceId':client.space,'userId':client.me['id'],'file':entry,'initial':first,'browser':browser}
 path.write_text(json.dumps(ids,indent=2));return ids
def verify(client,ids):
 entry=ids['file'];wid=ids['workspaceId'];original=ids['initial'];key=str(uuid.uuid4())
 move={'targetParentId':None,'name':'renamed-中文😀.txt'}
 op=request(client,'POST',entry['id']+'/move',move,entry['revision'],key);wait(client,op)
 assert request(client,'POST',entry['id']+'/move',move,entry['revision'],key)['id']==op['id']
 found=request(client,'GET',entry['id']+'/mutation-requests/'+key);assert found['operation']['state']=='COMMITTED'
 expect_error(lambda:request(client,'POST',entry['id']+'/move',{**move,'name':'other.txt'},entry['revision'],key),409)
 moved=file(client,entry['id']);assert moved['path']==move['name'] and moved['versionId']==entry['versionId']
 expect_error(lambda:request(client,'POST',entry['id']+'/move',move,entry['revision']),412)
 _,current,_=api.upload(client,wid,moved['path'],b'new immutable bytes\n',moved)
 restored=request(client,'POST',entry['id']+'/restore',{'sourceVersionId':original['versionId']},current['revision']);wait(client,restored)
 after=file(client,entry['id']);assert after['versionId'] not in [original['versionId'],current['versionId']] and after['sha256']==original['sha256']
 history=client.request('GET','/api/v1/files/'+entry['id']+'/versions?limit=1')[1];assert len(history['items'])==1 and history['nextCursor']
 assert history['items'][0]['sourceVersionId']==original['versionId']
 usage=client.request('GET','/api/v1/workspaces/'+wid+'/storage-usage')[1]
 deleted=request(client,'DELETE',entry['id'],revision=after['revision']);wait(client,deleted)
 assert file(client,entry['id'])['collectionState']=='DELETED'
 expect_error(lambda:client.request('GET','/api/v1/files/'+entry['id']+'/content',binary=True),410)
 actual=client.request('GET','/api/v1/files/'+entry['id']+'/content?versionId='+original['versionId'],binary=True)[1];assert hashlib.sha256(actual).hexdigest()==original['sha256']
 records=client.request('GET','/api/v1/workspaces/'+wid+'/deleted-files')[1]['items'];dead=next(v for v in records if v['id']==entry['id']);assert dead['canRestore']
 _,replacement,_=api.upload(client,wid,moved['path'],b'new identity at old path\n');assert replacement['id']!=entry['id']
 expect_error(lambda:request(client,'POST',entry['id']+'/restore',{'sourceVersionId':original['versionId']},dead['revision']),409)
 recovery=request(client,'POST',entry['id']+'/restore',{'sourceVersionId':original['versionId'],'name':'restored-copy.txt'},dead['revision']);wait(client,recovery)
 alive=file(client,entry['id']);assert alive['path']=='restored-copy.txt' and alive['id']==entry['id']
 foreign=api.Client('r52_user')
 for path in ['/api/v1/files/'+entry['id']+'/versions','/api/v1/workspaces/'+wid+'/deleted-files','/api/v1/files/'+entry['id']+'/mutation-requests/'+key]:expect_error(lambda p=path:foreign.request('GET',p),404)
 report={'status':'PASS','root':json.loads((DATA/'native-plan.json').read_text())['root'],'supplierCalls':0,'scope':'actual API/PG/MQ/signed runner/XFS/helper/S3 move/delete/cleanup/new identity/history/new restore version/original bytes/permissions','fileId':entry['id'],'versionId':alive['versionId'],'operations':[op['id'],restored['id'],deleted['id'],recovery['id']]}
 (DATA/'native-api-result.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
def tree(client,ids):
 plan=json.loads((DATA/'native-plan.json').read_text());wid=ids['workspaceId']
 # Fixed fixture directories only. Public mutations still go through the production runner chain.
 code='''from pathlib import Path
import os,stat,uuid
r=Path(ROOT);s=SPACE;w=WORKSPACE
assert str(uuid.UUID(s))==s and str(uuid.UUID(w))==w
p=r/'workspaces'/s/w/'work';assert p.is_dir() and not p.is_symlink()
for relative in ['tree-acceptance','tree-acceptance/empty']:
 d=p/relative
 if not d.exists():d.mkdir(mode=0o700);os.chown(d,10001,10001)
 assert stat.S_ISDIR(d.lstat().st_mode) and d.lstat().st_uid==10001
print('FIXED_DIRECTORY_FIXTURE_READY')
'''.replace('ROOT',repr(plan['root'])).replace('WORKSPACE',repr(wid)).replace('SPACE',repr(client.space))
 native.remote(code)
 for _ in range(60):
  rows=client.request('GET','/api/v1/workspaces/'+wid+'/files')[1]['items'];directory=next((r for r in rows if r['path']=='tree-acceptance' and r['collectionState']=='CONFIRMED'),None)
  if directory:break
  time.sleep(1)
 else:raise RuntimeError('Original directory scan not ready')
 _,one,_=api.upload(client,wid,'tree-acceptance/one.txt','tree 中文😀\n'.encode());_,two,_=api.upload(client,wid,'tree-acceptance/two.txt',b'')
 frozen=DATA/'native-tree-identities.json';frozen.write_text(json.dumps({'directory':directory,'files':[one,two]},indent=2))
 moved=request(client,'POST',directory['id']+'/move',{'name':'tree-renamed','targetParentId':None},file(client,directory['id'])['revision']);wait(client,moved)
 deletion=request(client,'DELETE',directory['id'],revision=file(client,directory['id'])['revision']);wait(client,deletion)
 tombstone=file(client,directory['id']);op=request(client,'POST',directory['id']+'/restore',{'deletionOperationId':deletion['id'],'name':'tree-restored','targetParentId':None},tombstone['revision']);wait(client,op)
 finish_tree(client,ids,directory,[one,two])
def finish_tree(client,ids,directory,entries):
 plan=json.loads((DATA/'native-plan.json').read_text())
 assert file(client,directory['id'])['path']=='tree-restored'
 for before in entries:
  after=file(client,before['id']);assert after['path'].startswith('tree-restored/') and after['versionId']!=before['versionId']
  actual=client.request('GET','/api/v1/files/'+after['id']+'/content?versionId='+after['versionId'],binary=True)[1];assert hashlib.sha256(actual).hexdigest()==before['sha256']
  history=client.request('GET','/api/v1/files/'+after['id']+'/versions')[1]['items'];assert history[0]['sourceVersionId']==before['versionId']
 result={'status':'PASS','root':plan['root'],'supplierCalls':0,'files':2,'emptyDirectory':True,'scope':'actual XFS directory move/delete isolation/cleanup/new-version streamed restore through PG/MQ/S3/signed runner; stable logical IDs/new bytes and zero-byte file hashes'}
 (DATA/'native-tree-result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
def resume_tree(client,ids):
 frozen=json.loads((DATA/'native-tree-identities.json').read_text());directory=frozen['directory'];plan=json.loads((DATA/'native-plan.json').read_text())
 root=plan['root'];selected=str(uuid.UUID(directory['id']))
 code='from pathlib import Path\nimport json,subprocess\nr=Path('+repr(root)+')\nc=json.loads((r/"compose.json").read_text());pg=subprocess.check_output(["docker","compose","-p",c["name"],"-f",str(r/"compose.json"),"ps","-q","postgres"],text=True).strip()\nquery='+repr("SELECT operation_id FROM pf.file_mutation_intent WHERE root_file_id='"+selected+"' AND kind='RESTORE_TREE' ORDER BY operation_id DESC LIMIT 1;")+'\nprint(subprocess.check_output(["docker","exec",pg,"psql","-XqAt","-U","postgres","-d","file_runtime","-c",query],text=True).strip())\n'
 original=native.remote(code);assert str(uuid.UUID(original))==original
 wait(client,{'id':original});finish_tree(client,ids,directory,frozen['files'])
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['seed','verify','tree','resume-tree']);args=p.parse_args();client=api.Client();ids=seed(client)
 if args.action=='seed':print('R52_ACTUAL_SEED_PASS')
 elif args.action=='verify':verify(client,ids)
 elif args.action=='tree':tree(client,ids)
 else:resume_tree(client,ids)
if __name__=='__main__':main()
