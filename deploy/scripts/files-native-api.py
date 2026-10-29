"""Real scoped file API acceptance over the encrypted R51 loopback tunnel; no model calls."""
from pathlib import Path
import argparse,hashlib,http.cookiejar,json,subprocess,time,urllib.error,urllib.request,uuid
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'.cache/r51-local';BASE='http://127.0.0.1:18151'
STAGE='r51'
def configure(stage):
 global DATA,BASE,STAGE
 assert stage in ['r51','r52','r53'];STAGE=stage;DATA=ROOT/('.cache/'+stage+'-local');BASE='http://127.0.0.1:'+str(18100+int(stage[1:]))
class Client:
 def __init__(self,name=None):
  name=name or STAGE+'_admin'
  self.jar=http.cookiejar.CookieJar();self.http=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(self.jar));self.space=None;self.csrf=None
  token=self.request('GET','/api/v1/auth/csrf')[1]['csrfToken'];self.csrf=token
  self.request('POST','/api/v1/auth/login',{'username':name,'password':(DATA/'native/accounts'/(name+'.password')).read_text()})
  self.csrf=self.request('GET','/api/v1/auth/csrf')[1]['csrfToken'];self.me=self.request('GET','/api/v1/me')[1];self.space=next(s['id'] for s in self.me['spaces'] if s['type']=='PERSONAL')
 def request(self,method,path,body=None,headers=None,binary=False):
  data=body if isinstance(body,bytes) else json.dumps(body,ensure_ascii=False).encode() if body is not None else b'' if method not in ['GET','HEAD'] else None
  h={'Origin':BASE,**({'X-Space-Id':self.space} if self.space else {}),**({'X-CSRF-Token':self.csrf} if self.csrf and method!='GET' else {}),**({'Content-Type':'application/json'} if data is not None else {}),**(headers or {})}
  req=urllib.request.Request(BASE+path,data=data,method=method,headers=h)
  try:
   with self.http.open(req,timeout=130) as response:raw=response.read();return response.status,raw if binary else json.loads(raw),dict(response.headers)
  except urllib.error.HTTPError as failure:
   raw=failure.read(65536)
   try:problem=json.loads(raw)
   except ValueError:problem={'code':'NON_JSON_HTTP','status':failure.code}
   raise ApiFailure(failure.code,problem.get('code','HTTP_ERROR')) from None
class ApiFailure(Exception):
 def __init__(self,status,code):self.status=status;self.code=code;super().__init__(str(status)+' '+code)
def wait_operation(client,id):
 for _ in range(90):
  view=client.request('GET','/api/v1/file-operations/'+id)[1]
  if view['state'] in ['COMMITTED','FAILED','UNKNOWN']:return view
  time.sleep(1)
 raise RuntimeError('Original file operation pending; do not resubmit')
def upload(client,workspace,path,content,base=None,key=None):
 body={'path':path,'bytes':len(content),'sha256':hashlib.sha256(content).hexdigest()};headers={'Idempotency-Key':key or str(uuid.uuid4())}
 if base:body.update(fileId=base['id'],baseVersionId=base['versionId']);headers['If-Match']='"'+str(base['revision'])+'"'
 status,row,_=client.request('POST','/api/v1/workspaces/'+workspace+'/uploads',body,headers);assert status==201
 client.request('PUT','/api/v1/uploads/'+row['id']+'/content',content,{'Content-Type':'application/octet-stream'})
 op=client.request('POST','/api/v1/uploads/'+row['id']+'/complete')[1];result=wait_operation(client,op['id']);assert result['state']=='COMMITTED',result.get('errorCode')
 file=client.request('GET','/api/v1/files/'+row['fileId'])[1];assert file['sha256']==body['sha256'] and file['bytes']==len(content)
 actual=client.request('GET','/api/v1/files/'+file['id']+'/content?versionId='+row['versionId'],binary=True)[1];assert actual==content
 return row,file,result
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['seed','verify']);args=p.parse_args();client=Client()
 record=DATA/'native-file-identities.json'
 if args.action=='seed':
  assert not record.exists(),'Seed already recorded; resume using original identities'
  original=DATA/'workspace-create-request.json'
  if original.exists():
   frozen=json.loads(original.read_text());assert frozen['name']=='R51 file editor acceptance';key=frozen['key']
  else:key=str(uuid.uuid4());original.write_text(json.dumps({'key':key,'name':'R51 file editor acceptance'}))
  _,workspace,_=client.request('POST','/api/v1/workspaces',{'name':'R51 file editor acceptance'},{'Idempotency-Key':key})
  for _ in range(90):
   view=client.request('GET','/api/v1/workspaces/'+workspace['id'])[1]
   if view['state']=='READY':break
   assert view['state'] not in ['ERROR','BLOCKED'],view.get('errorCode');time.sleep(1)
  else:raise RuntimeError('Workspace preparation pending; query original workspace')
  first,file,op=upload(client,workspace['id'],'editor.txt','initial 中文😀\r\n'.encode('utf-8'))
  zero,_,_=upload(client,workspace['id'],'empty.txt',b'')
  bom,_,_=upload(client,workspace['id'],'bom.txt','\ufefffirst\r\nsecond\r\n'.encode('utf-8'))
  record.write_text(json.dumps({'workspaceId':workspace['id'],'userId':client.me['id'],'spaceId':client.space,'file':file,'initial':first,'empty':zero,'bom':bom},indent=2))
  print('R51_REAL_PG_MQ_S3_RUNNER_SEED_HASH_PASS')
 else:
  info=json.loads(record.read_text());first=info['file'];path=first['path']
  _,current,_=upload(client,info['workspaceId'],path,b'api concurrent replacement\r\n',first)
  try:upload(client,info['workspaceId'],path,b'stale must not overwrite\n',first);raise AssertionError('Stale replacement unexpectedly accepted')
  except ApiFailure as error:assert error.status==412 and error.code=='FILE_VERSION_CONFLICT'
  foreign=Client('r51_user')
  for endpoint in ['/api/v1/files/'+first['id'],'/api/v1/files/'+first['id']+'/content?versionId='+first['versionId'],'/api/v1/workspaces/'+info['workspaceId']+'/files']:
   try:foreign.request('GET',endpoint);raise AssertionError('Cross-user resource disclosed')
   except ApiFailure as error:assert error.status in [403,404]
  old=client.request('GET','/api/v1/files/'+first['id']+'/content?versionId='+info['initial']['versionId'],binary=True)[1];assert hashlib.sha256(old).hexdigest()==info['initial']['sha256']
  result={'status':'PASS','root':json.loads((DATA/'native-plan.json').read_text())['root'],'scope':'actual API create/stream/MQ/runner/XFS/S3/hash, stale base rejection, foreign user denial and old immutable read','supplierCalls':0,'current':current}
  (DATA/'native-api-result.json').write_text(json.dumps(result,indent=2));print('R51_REAL_API_VERSION_AND_ISOLATION_PASS')
if __name__=='__main__':main()
