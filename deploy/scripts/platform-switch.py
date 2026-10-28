"""Host-only reversible switch of retained containers. Default is a read-only plan."""
import argparse,datetime,fcntl,hashlib,json,os,pathlib,re,shutil,subprocess,time,urllib.request
ROOT=pathlib.Path('/srv/agentplatform');OPS=ROOT/'operations/platform-switch'
LEGACY=['mysql','ai-agent-pgvector','redis','rabbitmq','zookeeper','ai-agent-xxl-job-admin','big-market-app','ai-agent-app','ai-agent_big-market-front-app','nginx']
NEW_ENV=['postgres','rabbitmq','redis','master','volume','filer','s3']
NEW_APP=['api','worker','gateway']
CRON_MARK='# platform-switch-inactive-legacy '

def release_profile():
 path=OPS/'release-selection.json'
 if not path.exists():return 'agentplatform-app',NEW_APP,None
 profile=json.loads(path.read_text())
 assert profile['appProject'] in ['agentplatform-r3-app','agentplatform-r4-app'] and profile['appServices']==['runner','api','worker','gateway']
 return profile['appProject'],profile['appServices'],hashlib.sha256(path.read_bytes()).hexdigest()

def expected_services():
 project,services,_=release_profile()
 return {('agentplatform-env',s) for s in NEW_ENV}|{(project,s) for s in services}

def runner_start_identity(value):
 match=re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z',value)
 assert match,'UTC runner start identity required'
 return match[1]+'.'+(match[2] or '').ljust(9,'0')+'Z'
def call(args,**kwargs):
 p=subprocess.run(args,text=True,capture_output=True,timeout=kwargs.pop('timeout',180),**kwargs)
 if p.returncode:raise RuntimeError('Command failed: '+args[0]+' '+args[1]+' (exit '+str(p.returncode)+')')
 return p.stdout.strip()
def inspect(ids):return json.loads(call(['docker','inspect',*ids]))
def mounts(x):return sorted([{k:m.get(k) for k in ['Type','Source','Destination','RW']} for m in x['Mounts']],key=lambda m:(m['Destination'],m['Source']))
def inventory():
 app,_,_=release_profile()
 rows=inspect(call(['docker','ps','-aq']).split());new=[];old=[]
 for x in rows:
  labels=x['Config'].get('Labels',{});name=x['Name'].lstrip('/')
  if name not in LEGACY and labels.get('com.docker.compose.project') not in ['agentplatform-env',app]:continue
  rec={'id':x['Id'],'name':name,'image':x['Image'],'running':x['State']['Running'],'restart':x['HostConfig']['RestartPolicy'],'project':labels.get('com.docker.compose.project'),'service':labels.get('com.docker.compose.service'),'mounts':mounts(x)}
  rec['limits']={k:x['HostConfig'][k] for k in ['Memory','MemorySwap','PidsLimit']}
  (old if name in LEGACY else new).append(rec)
 assert {x['name'] for x in old}==set(LEGACY),'Legacy container set incomplete'
 return new,old
def write(path,data):
 tmp=path.with_name(path.name+'.tmp');tmp.write_text(json.dumps(data,indent=2)+'\n');tmp.chmod(0o600);os.replace(tmp,path)
def exact(row):
 x=inspect([row['id']])[0]
 assert x['Name']=='/'+row['name'] and x['Image']==row['image'],'Container identity drift'
 assert mounts(x)==row['mounts'],'Mount drift: '+row['name']
 if 'limits' in row:assert {k:x['HostConfig'][k] for k in row['limits']}==row['limits'],'Resource limits drift: '+row['name']
 return x
def restart(row,policy):
 exact(row);name=policy['Name'];value=name+(':'+str(policy['MaximumRetryCount']) if name=='on-failure' and policy['MaximumRetryCount'] else '')
 call(['docker','update','--restart='+value,row['id']])
def stop(row):
 if exact(row)['State']['Running']:call(['docker','stop','-t','90',row['id']],timeout=110)
def start(row):
 if not exact(row)['State']['Running']:call(['docker','start',row['id']])
def wait(check,label,seconds=240):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  try:
   if check():return
  except Exception:pass
  time.sleep(3)
 raise RuntimeError('Not ready: '+label)
def healthy(row):
 x=exact(row);return x['State']['Running'] and x['State'].get('Health',{}).get('Status','healthy')=='healthy'
def http(path,host=None,port=80):
 req=urllib.request.Request('http://127.0.0.1:'+str(port)+path,headers={'Host':host} if host else {})
 with urllib.request.urlopen(req,timeout=12) as res:return res.status,res.read()
def cron(active):
 p=subprocess.run(['crontab','-l'],capture_output=True,text=True);assert p.returncode in [0,1]
 before=p.stdout;lines=[]
 for line in before.splitlines():
  related=any('/home/ai-agent_big-market/'+name in line for name in ['restart-services.sh','memory-monitor.sh'])
  if related and line.startswith(CRON_MARK) and active:line=line[len(CRON_MARK):]
  elif related and not line.lstrip().startswith('#') and not active:line=CRON_MARK+line
  lines.append(line)
 after='\n'.join(lines)+'\n'
 if after!=before:call(['crontab','-'],input=after)
 return {'shaBefore':hashlib.sha256(before.encode()).hexdigest(),'shaAfter':hashlib.sha256(after.encode()).hexdigest(),'legacyEnabled':active}
def new_sql(new,sql):
 pg=next(x for x in new if x['project']=='agentplatform-env' and x['service']=='postgres')
 return call(['docker','exec','-i',pg['id'],'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','ap_admin','-d','agentplatform'],input=sql)
def business_idle(new):return new_sql(new,"SELECT (SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL)+(SELECT count(*) FROM pf.task WHERE status IN ('QUEUED','RUNNING'));")=='0'
def idle(new):
 if not business_idle(new):return False
 if release_profile()[0] not in ['agentplatform-r3-app','agentplatform-r4-app']:return True
 return new_sql(new,"SELECT (SELECT count(*) FROM pf.file_operation WHERE status NOT IN('COMMITTED','FAILED'))+(SELECT count(*) FROM pf.workspace_file_lease WHERE status<>'FREE')+(SELECT count(*) FROM pf.file_transfer_slot WHERE owner_id IS NOT NULL)+(SELECT count(*) FROM pf.workspace_environment_operation WHERE status NOT IN('COMMITTED','FAILED'))+(SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL);")=='0'
def ordered_new(new):
 app,services,_=release_profile()
 return [next(x for x in new if x['project']==project and x['service']==service) for project,items in [('agentplatform-env',NEW_ENV),(app,services)] for service in items]
def stop_new(new):
 for row in new:restart(row,{'Name':'no','MaximumRetryCount':0})
 ordered=ordered_new(new)
 stop(next(x for x in ordered if x['service']=='gateway'));stop(next(x for x in ordered if x['service']=='api'))
 wait(lambda:idle(new),'new tasks/files/environment owner drained',seconds=180)
 worker=next(x for x in ordered if x['service']=='worker')
 for attempt in range(3):
  stop(worker)
  if idle(new):break
  start(worker);wait(lambda:idle(new),'original file MQ work drained',seconds=180)
 else:raise RuntimeError('Original work not quiescent; retain resources and refuse legacy startup')
 for row in reversed(ordered):stop(row)
 assert all(not exact(x)['State']['Running'] for x in new)
def stop_legacy(old):
 cron(False)
 for row in old:restart(row,{'Name':'no','MaximumRetryCount':0})
 for name in reversed(LEGACY):stop(next(x for x in old if x['name']==name))
def start_new(new):
 mount=json.loads(call(['findmnt','-J','--target',str(ROOT/'workspaces')]))['filesystems'][0]
 assert mount['target']==str(ROOT/'workspaces') and mount['fstype']=='xfs' and 'prjquota' in mount['options'],'Workspace mount guard'
 selection=OPS/'release-selection.json'
 if selection.exists():
  profile=json.loads(selection.read_text())
  assert call(['findmnt','-n','-o','UUID','--target',str(ROOT/'workspaces')])==profile['filesystemUuid']
  assert mount['source']==profile['loopDevice'],'Quota device drift; revalidate policy before startup'
 for row in new:restart(row,row['restart'])
 for row in ordered_new(new):
  if not row['running']:continue
  restart(row,row['restart']);start(row);wait(lambda row=row:healthy(row),row['name'])
  if row['service']=='runner':
   def registered():
    state=exact(row)['State']
    if not state['Running']:return False
    query="SELECT started_at FROM pf.workspace_runner_instance WHERE container_id='"+row['id']+"' AND image_id='"+row['image']+"';"
    return sum(runner_start_identity(value)==runner_start_identity(state['StartedAt']) for value in new_sql(new,query).splitlines())==1
   wait(registered,'exact trusted runner registration')
 wait(lambda:http('/api/v1/readiness',host='124.222.55.140')[0]==200,'selected new release public readiness')
def start_legacy(old,policies):
 for name in LEGACY:
  row=next(x for x in old if x['name']==name);restart(row,policies[name]);start(row)
  if name in ['mysql','ai-agent-pgvector','redis','ai-agent_big-market-front-app']:wait(lambda row=row:healthy(row),name)
  elif name=='rabbitmq':wait(lambda:call(['docker','exec',row['id'],'rabbitmq-diagnostics','-q','ping'],timeout=20) is not None,name)
  elif name=='big-market-app':wait(lambda:http('/actuator/health',port=8090)[0]==200,name)
  elif name=='ai-agent-app':wait(lambda:json.loads(http('/api/v1/platform/readiness',port=8091)[1])['data']['ready'] is True,name,seconds=300)
 call(['docker','exec','nginx','nginx','-t'])
 wait(lambda:http('/',host='agent.lailihao.cloud')[0]==200,'legacy front Host')
 wait(lambda:json.loads(http('/api/v1/platform/readiness',port=8091)[1])['data']['ready'] is True,'legacy direct API origin8091')
 cron(True)
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('target',choices=['status','to-legacy','to-new']);p.add_argument('--apply',action='store_true');p.add_argument('--operation-id');args=p.parse_args()
 new,old=inventory()
 if args.target=='status':
  print(json.dumps({'newRunning':[x['name'] for x in new if x['running']],'legacyRunning':[x['name'] for x in old if x['running']],'activeSwitch':json.loads((OPS/'active-switch.json').read_text()) if (OPS/'active-switch.json').exists() else None}));return
 source=None
 if args.target=='to-legacy':
  assert all(not x['running'] for x in old) and sum(x['running'] for x in new)==len(expected_services()),'Expected exclusive running selected new stack'
  assert {(x['project'],x['service']) for x in new if x['running']}==expected_services(),'Unexpected active new profiles'
  assert business_idle(new),'Active model execution; drain before switching'
  selection=OPS/'release-selection.json'
  if selection.exists():
   for row in json.loads(selection.read_text())['containers']:exact(row)
  policies={}
  for line in (ROOT/'operations/restore-policies.txt').read_text().splitlines():
   name,identity,image,policy,running=line.split('|');row=next(x for x in old if x['name']==name.lstrip('/'))
   assert row['id']==identity,'Legacy container ID drift: '+row['name']
   assert image in [row['image'],exact(row)['Config']['Image']],'Historical tag/config identity mismatch: '+row['name']
   assert running=='true' and policy in ['always','unless-stopped','no'];policies[row['name']]={'Name':policy,'MaximumRetryCount':0}
  assert set(policies)==set(LEGACY)
 else:
  active=json.loads((OPS/'active-switch.json').read_text());assert active['activePlatform']=='legacy'
  path=OPS/'sessions'/active['operationId']/'snapshot.json';source=json.loads(path.read_text())
  assert source.get('releaseSelectionSha256')==release_profile()[2],'Selected release changed since switch snapshot'
  assert sum(x['running'] for x in new)==0 and all(x['running'] for x in old),'Expected exclusive running legacy stack'
  for row in source['new']+source['legacy']:exact(row)
  new=source['new'];old=source['legacy'];policies=source['legacyActivationPolicies']
 if not args.apply:
  print(json.dumps({'status':'DRY_RUN','target':args.target,'newRetained':len(new),'legacyRetained':len(old),'actions':'serial graceful stop/start; exact identities/mounts; no pull/build/recreate/delete/data transfer'}));return
 assert os.geteuid()==0 and shutil.disk_usage(ROOT).free>=5*1024**3
 OPS.mkdir(mode=0o700,exist_ok=True);OPS.chmod(0o700)
 with (OPS/'switch.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  sid=args.operation_id or datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
  assert re.fullmatch('[0-9]{8}T[0-9]{6}Z',sid)
  folder=OPS/'sessions'/sid;folder.mkdir(parents=True,mode=0o700,exist_ok=False);folder.chmod(0o700)
  files={}
  for base in [ROOT/'deploy',pathlib.Path('/home/ai-agent_big-market')]:
   for f in base.glob('*compose*.y*ml'):files[str(f)]=hashlib.sha256(f.read_bytes()).hexdigest()
  selection=OPS/'release-selection.json'
  if selection.exists():
   for name in json.loads(selection.read_text()).get('configurationFiles',[]):files[name]=hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest()
  snapshot={'new':new,'legacy':old,'legacyActivationPolicies':policies,'composeHashes':files,'releaseSelectionSha256':release_profile()[2],'originalXfsUuid':call(['blkid','-p','-s','UUID','-o','value',str(ROOT/'quota/workspaces.xfs')]),'operationId':sid,'target':args.target}
  write(folder/'snapshot.json',snapshot)
  cp=subprocess.run(['crontab','-l'],capture_output=True,text=True);(folder/'crontab.before').write_text(cp.stdout);(folder/'crontab.before').chmod(0o600)
  write(folder/'progress.json',{'status':'RUNNING','target':args.target,'operationId':sid})
  try:
   if args.target=='to-legacy':stop_new(new);start_legacy(old,policies)
   else:stop_legacy(old);start_new(new)
   for name,digest in files.items():assert hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest()==digest
   assert call(['blkid','-p','-s','UUID','-o','value',str(ROOT/'quota/workspaces.xfs')])==snapshot['originalXfsUuid']
   active='legacy' if args.target=='to-legacy' else 'new';all_rows=inspect([x['id'] for x in new+old])
   expected={x['id'] for x in (old if active=='legacy' else new) if active=='legacy' or x['running']}
   assert {x['Id'] for x in all_rows if x['State']['Running']}==expected
   result={'status':'PASS','activePlatform':active,'operationId':sid,'runningContainers':len(expected),'composeUnchanged':True,'volumesAndImagesRetained':True,'noModelRequestsByVerification':True,'snapshot':str(folder/'snapshot.json')}
   write(folder/'result.json',result);write(OPS/'active-switch.json',result);print(json.dumps(result))
  except Exception as exc:
   result={'status':'FAIL','target':args.target,'errorType':type(exc).__name__,'error':str(exc),'operationId':sid}
   try:
    if args.target=='to-legacy':stop_legacy(old);start_new(new)
    else:
     for row in reversed(ordered_new(new)):stop(row)
     for row in new:restart(row,{'Name':'no','MaximumRetryCount':0})
     start_legacy(old,policies)
    result['rollback']='PASS'
   except Exception as rollback:result['rollback']='FAIL';result['rollbackError']=str(rollback)
   write(folder/'result.json',result);print(json.dumps(result));raise SystemExit(1)
if __name__=='__main__':main()
