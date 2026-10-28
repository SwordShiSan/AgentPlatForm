from pathlib import Path
import http.cookiejar,urllib.request,urllib.error,json,time,uuid,hashlib,subprocess,shutil,sys,traceback
ROOT=None;ORIGIN='http://agentplatform.lailihao.cloud';MIB=1024**2
def save(name,value):(ROOT/(name+'.json')).write_text(json.dumps(value,indent=2))
def cmd(args):return subprocess.check_output(args,text=True,timeout=45).strip()
class Api:
 def __init__(self,account):
  self.client=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf=''
  self.csrf=self.get('/auth/csrf')['csrfToken'];self.get('/auth/login',{'username':account['username'],'password':account['password']});self.csrf=self.get('/auth/csrf')['csrfToken']
 def get(self,path,body=None,method=None,headers=None,raw=False):
  binary=isinstance(body,bytes);data=body if binary else json.dumps(body).encode() if body is not None else None
  request=urllib.request.Request('http://127.0.0.1/api/v1'+path,data=data,method=method,headers={'Host':'agentplatform.lailihao.cloud','Origin':ORIGIN,'X-CSRF-Token':self.csrf,'Idempotency-Key':str(uuid.uuid4()),'Content-Type':'application/octet-stream' if binary else 'application/json',**(headers or {})})
  try:
   with self.client.open(request,timeout=135) as r:
    value=r.read(150000);return value if raw else json.loads(value) if value else {}
  except urllib.error.HTTPError as e:
   text=e.read(65536)
   try:value=json.loads(text)
   except ValueError:value={'sha256':hashlib.sha256(text).hexdigest()}
   save('observation-api-error',{'path':path,'status':e.code,'problem':value});raise
def wait(fn,seconds=120):
 end=time.time()+seconds
 while time.time()<end:
  value=fn()
  if value:return value
  time.sleep(1)
 raise RuntimeError('Operation did not converge; inspect original receipt')
def live_resources(rows,required):
 assert required.issubset({r['Id'] for r in rows}),'Required service disappeared'
 for r in rows:
  assert not r['State']['OOMKilled'],'Observed OOM'
  if r['Id'] in required:assert r['State']['Running'],'Required service stopped'
  if r['State']['Running']:assert r['HostConfig']['MemorySwap']==r['HostConfig']['Memory'],'Swap limit differs'
 # Temporary helper/sandbox exit is governed by its durable operation receipt,
 # not by the health rule for the eleven required long-running services.
 return [r for r in rows if r['State']['Running']]
def resources(required):
 rows=json.loads(cmd(['docker','inspect',*cmd(['docker','ps','-q']).split()]))
 own=[r for r in rows if r['Config'].get('Labels',{}).get('com.docker.compose.project') in ['agentplatform-env','agentplatform-r4-app'] or any(m.get('Source','').startswith('/srv/agentplatform/workspaces/') for m in r.get('Mounts',[]))]
 save('last-resource-lifecycle',{'containers':[{'id':r['Id'],'image':r['Image'],'required':r['Id'] in required,'running':r['State']['Running'],'exitCode':r['State']['ExitCode'],'oom':r['State']['OOMKilled'],'memory':r['HostConfig']['Memory'],'memorySwap':r['HostConfig']['MemorySwap']} for r in own]})
 own=live_resources(own,required)
 memory={l.split(':')[0]:int(l.split()[1])*1024 for l in Path('/proc/meminfo').read_text().splitlines() if l.startswith(('MemAvailable:','SwapTotal:','SwapFree:'))}
 total=sum(r['HostConfig']['Memory'] for r in own)+(128*MIB if any(r['HostConfig']['Runtime']=='runsc' for r in own) else 0)
 assert total<=2887*MIB and memory['MemAvailable']>=768*MIB and shutil.disk_usage('/').free>=5*1024**3
 values=[]
 for r in own:
  v={'id':r['Id'],'image':r['Image'],'cap':r['HostConfig']['Memory'],'restartCount':r['RestartCount']};cg=Path('/sys/fs/cgroup/system.slice/docker-'+r['Id']+'.scope')
  for name in ['memory.current','memory.peak','memory.swap.current','pids.current']:
   if (cg/name).exists():v[name]=int((cg/name).read_text())
  status=Path('/proc/'+str(r['State']['Pid'])+'/status')
  if status.exists():v['process']={l.split(':')[0]:l.split(':')[1].strip() for l in status.read_text().splitlines() if l.startswith(('VmRSS:','VmHWM:','Threads:'))}
  values.append(v)
 with (ROOT/'production-resources.jsonl').open('a') as f:f.write(json.dumps({'time':time.time(),**memory,'limitBytes':total,'rootFreeBytes':shutil.disk_usage('/').free,'containers':values})+'\n')
def main():
 global ROOT
 ROOT=Path(sys.argv[1]).resolve(strict=True)
 assert ROOT.parent==Path('/srv/agentplatform/operations') and ROOT.name.startswith('r4-release-')
 assert not (ROOT/'observation-started.json').exists();save('observation-started',{'time':time.time()})
 smoke=json.loads((ROOT/'smoke.json').read_text());assert smoke['status']=='PASS'
 account=json.loads((ROOT/'probe-accounts.private.json').read_text())[0];user=Api(account);wid=smoke['identities']['workspaceId'];base='/workspaces/'+wid
 ids=cmd(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-r4-app']).split();ids+=cmd(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-env']).split();rows=json.loads(cmd(['docker','inspect',*ids]));required={r['Id'] for r in rows};assert len(required)==11
 broker=next(r['Id'] for r in rows if r['Config']['Labels']['com.docker.compose.service']=='rabbitmq')
 started=time.time();rounds=0;file=None;name='r4-observation-'+uuid.uuid4().hex+'.txt';first=None
 try:
  while time.time()-started<1800:
   data=('R4 functional observation '+str(rounds)+' 666\n').encode();sha=hashlib.sha256(data).hexdigest();payload={'path':name,'bytes':len(data),'sha256':sha};headers={}
   if file:
    view=user.get('/files/'+file['id']);payload.update(fileId=file['id'],baseVersionId=view['versionId']);headers['If-Match']='"'+str(view['revision'])+'"'
   upload=user.get(base+'/uploads',payload,headers=headers);uid=upload['id'];user.get('/uploads/'+uid+'/content',data,method='PUT');operation=user.get('/uploads/'+uid+'/complete',method='POST')
   done=wait(lambda:(v if v['state'] in ['COMMITTED','FAILED','BLOCKED'] else None) if (v:=user.get('/file-operations/'+operation['id'])) else None)
   assert done['state']=='COMMITTED',done
   file=user.get('/files/'+upload['fileId']);version={'fileId':file['id'],'versionId':file['versionId'],'sha256':sha};first=first or version
   actual=user.get('/files/'+file['id']+'/content?versionId='+version['versionId'],raw=True);assert hashlib.sha256(actual).hexdigest()==sha
   old=user.get('/files/'+first['fileId']+'/content?versionId='+first['versionId'],raw=True);assert hashlib.sha256(old).hexdigest()==first['sha256']
   if rounds%4==0:
    env=wait(lambda:(v if any(a in v['allowedActions'] for a in ['PREPARE','WAKE','SLEEP']) else None) if (v:=user.get(base+'/environment')) else None)
    if env['state']!='READY':
     path='/wake' if 'WAKE' in env['allowedActions'] else '';user.get(base+'/environment'+path,{},method='POST',headers={'If-Match':'"'+str(env['version'])+'"'});wait(lambda:user.get(base+'/environment')['state']=='READY')
    env=wait(lambda:(v if 'SLEEP' in v['allowedActions'] else None) if (v:=user.get(base+'/environment')) else None);user.get(base+'/environment/sleep',{},method='POST',headers={'If-Match':'"'+str(env['version'])+'"'});wait(lambda:user.get(base+'/environment')['state']=='STOPPED')
   if rounds==3:
    subprocess.run(['docker','stop','-t','30',broker],check=True,capture_output=True,timeout=45)
    try:time.sleep(5)
    finally:subprocess.run(['docker','start',broker],check=True,capture_output=True,timeout=45)
    wait(lambda:json.loads(cmd(['docker','inspect',broker]))[0]['State']['Health']['Status']=='healthy')
   resources(required);rounds+=1;save('observation-progress',{'elapsedSeconds':time.time()-started,'rounds':rounds,'file':version});time.sleep(15)
  save('observation',{'status':'PASS','source':smoke['source'],'elapsedSeconds':time.time()-started,'rounds':rounds,'assertions':['serial-create-replace-current-and-historical-version-hash','environment-wake-sleep-files-preserved','broker-stop-and-recovery','fixed-candidate-resource-sampling'],'supplierCalls':0,'samplesNotAbsolutePeaks':True})
 except BaseException:
  (ROOT/'observation-failure.private.log').write_text(traceback.format_exc());save('observation',{'status':'FAIL','elapsedSeconds':time.time()-started,'rounds':rounds});raise
if __name__=='__main__':main()
