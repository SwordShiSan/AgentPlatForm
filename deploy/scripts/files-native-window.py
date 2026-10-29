"""Independent R51 native acceptance controller. Restores exact R4 on finish/failure."""
from pathlib import Path
import argparse,fcntl,hashlib,importlib.util,json,os,re,shutil,signal,subprocess,time,traceback,urllib.request,uuid
ROOT=Path(__file__).resolve().parent
assert re.fullmatch('/srv/agentplatform/acceptance/r44-r5[123]-[0-9]{14}',str(ROOT))
PLAN=json.loads((ROOT/'operation.json').read_text());STAGE=PLAN.get('acceptanceStage','r51');assert STAGE in ['r51','r52','r53'] and PLAN['root']==str(ROOT) and PLAN['project']=='agentplatform-'+STAGE+'-local'
REPORT=ROOT/'reports';os.umask(0o077)
helper=Path('/srv/agentplatform/operations/platform-switch/platform-switch.py')
assert hashlib.sha256(helper.read_bytes()).hexdigest()=='2d3021778267cd364568398fc44ba8f8d51b8eebc5b09ad3392e7ed2402a1e8b'
spec=importlib.util.spec_from_file_location('switch',helper);switch=importlib.util.module_from_spec(spec);spec.loader.exec_module(switch)
BASE=['docker','compose','-p',PLAN['project'],'-f',str(ROOT/'compose.json')]
def call(args,input=None,timeout=180,required=True):
 p=subprocess.run([str(x) for x in args],input=input,capture_output=True,timeout=timeout)
 with (REPORT/'commands.private.log').open('ab') as out:out.write(p.stdout+p.stderr+b'\n')
 if required and p.returncode:raise RuntimeError('Command failed; read protected native command log')
 return p.stdout.decode('utf-8',errors='replace').strip()
def save(name,value):
 p=REPORT/(name+'.json');tmp=REPORT/(name+'.tmp');tmp.write_text(json.dumps(value,indent=2));tmp.chmod(0o600);os.replace(tmp,p)
def cid(name):return call(BASE+['ps','-a','-q',name])
def sql(text):return call(['docker','exec','-i',cid('postgres'),'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','postgres','-d','file_runtime'],text.encode())
def status():
 rows=[]
 for name in ['api','worker','runner','gateway','postgres','rabbitmq','master','volume','filer','s3','redis']:
  identity=cid(name)
  if identity:
   r=json.loads(call(['docker','inspect',identity]))[0];rows.append({'service':name,'id':r['Id'],'image':r['Image'],'running':r['State']['Running'],'oom':r['State']['OOMKilled'],'memory':r['HostConfig']['Memory']})
 return rows
def sample():
 rows=status();assert len(rows)==11 and all(r['running'] and not r['oom'] for r in rows)
 memory={line.split(':')[0]:int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemAvailable:','SwapFree:','SwapTotal:'))}
 assert memory['MemAvailable']>=768*1024**2 and shutil.disk_usage('/').free>=5*1024**3
 assert sum(r['memory'] for r in rows)+704*1024**2<=2887*1024**2
 record={'time':time.time(),**memory,'rootFreeBytes':shutil.disk_usage('/').free,'required':rows}
 with (REPORT/'resources.jsonl').open('a') as f:f.write(json.dumps(record)+'\n')
 return record
def restore(snapshot):
 # Stop only this project's exact containers and helpers mounted under its root.
 ids=call(['docker','ps','-q']).split()
 rows=json.loads(call(['docker','inspect',*ids])) if ids else []
 for row in rows:
  if row['Config'].get('Labels',{}).get('com.docker.compose.project')==PLAN['project'] or any(m.get('Source','').startswith(str(ROOT)+'/') for m in row.get('Mounts',[])):
   call(['docker','stop','-t','10',row['Id']]);assert not json.loads(call(['docker','inspect',row['Id']]))[0]['State']['Running']
 mounted=call(['findmnt','-n','-o','TARGET','--mountpoint',str(ROOT/'workspaces')],required=False)
 if mounted:assert mounted==str(ROOT/'workspaces');call(['umount',str(ROOT/'workspaces')])
 device=ROOT/'quota/loop-device'
 if device.exists():
  loop=device.read_text().strip();assert re.fullmatch('/dev/loop[0-9]{1,5}',loop)
  backing=Path('/sys/block/'+Path(loop).name+'/loop/backing_file')
  if backing.exists():assert Path('/'+backing.read_text().strip().lstrip('/')).resolve()==(ROOT/'quota/workspaces.xfs').resolve();call(['losetup','-d',loop])
 switch.start_new(snapshot['new'])
 assert all(switch.exact(row)['State']['Running'] and switch.exact(row)['HostConfig']['RestartPolicy']==row['restart'] for row in snapshot['new'])
 assert all(not switch.exact(row)['State']['Running'] for row in snapshot['legacy'])
 assert call(['findmnt','-n','-o','UUID','--target','/srv/agentplatform/workspaces'])==snapshot['filesystemUuid']
 assert switch.http('/api/v1/readiness',host='124.222.55.140')[0]==200
 save('restored',{'status':'PASS','sameR4ContainersImagesAndPolicies':True,'productionUuidUnchanged':True,'legacyStopped':True,'supplierCalls':0,'time':time.time()})
def operator(profile,args=[],content=None):
 c=BASE+['--profile','operator','run','--rm','-T','--no-deps','-e','SPRING_PROFILES_ACTIVE='+profile]
 if content is None:return call(c+['operator']+args)
 shell='umask 077; cat > /tmp/operator-input; java -Xms16m -Xmx112m -jar /app/platform.jar "$@"; result=$?; rm -f /tmp/operator-input; exit "$result"'
 return call(c+['--entrypoint','sh','operator','-c',shell,'operator']+args,content)
def start():
 lock=(switch.OPS/'switch.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 # Validate all non-live prerequisites before any production pause.
 c=json.loads((ROOT/'compose.json').read_text());assert c['name']==PLAN['project']
 runtime=json.loads(Path('/etc/docker/daemon.json').read_text())['runtimes']['runsc'];digest=hashlib.sha256(Path(runtime['path']).read_bytes()).hexdigest();assert digest=='210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a'
 (ROOT/'runtime-attestation.json').write_text(json.dumps({**runtime,'sha256':digest,'verifiedAt':int(time.time())}));(ROOT/'runtime-attestation.json').chmod(0o444)
 for name,row in c['services'].items():
  identity=json.loads(call(['docker','image','inspect',row['image']]))[0];assert identity['Architecture']=='amd64'
  for binding in row.get('volumes',[]):
   if isinstance(binding,dict) and binding.get('type')=='bind':
    assert binding['source']=='/var/run/docker.sock' or binding['source'].startswith(str(ROOT)+'/')
    assert Path(binding['source']).exists(),'Missing fixture bind input before maintenance'
 for secret in c['secrets'].values():assert Path(secret['file']).is_file() and Path(secret['file']).parent==ROOT
 assert (ROOT/'bootstrap-access').is_file() and (ROOT/'bootstrap-secret').is_file(),'Explicit isolated bucket bootstrap approval and inputs required'
 grants='\n'.join(p.read_text() for p in ROOT.glob('*grants.sql'));assert 'ap_workspace_ops' in grants
 assert all((ROOT/'accounts'/(name+'.password')).is_file() for name in [STAGE+'_admin',STAGE+'_user'])
 new,old=switch.inventory()
 save('precheck',{'requiredServices':len(new),'newAllRunning':all(r['running'] for r in new),'legacyStopped':all(not r['running'] for r in old),'idleAtRead':switch.idle(new),'time':time.time()})
 assert len(new)==11,'Selected R4 service count changed'
 assert all(r['running'] for r in new),'Selected R4 service stopped before maintenance'
 assert all(not r['running'] for r in old),'Legacy platform unexpectedly running'
 switch.wait(lambda:switch.idle(new),'R4 original tasks/files drained before maintenance',seconds=30)
 snapshot={'new':new,'legacy':old,'filesystemUuid':call(['findmnt','-n','-o','UUID','--target','/srv/agentplatform/workspaces'])};save('production-before',snapshot)
 # Resuming an already allocated independent disk does not require allocating another4GiB.
 required_free=5*1024**3+(0 if (ROOT/'quota/workspaces.xfs').exists() else 4*1024**3)
 assert shutil.disk_usage('/').free>=required_free,'Insufficient safe free space for the remaining allocation'
 paused=False;result={'status':'FAIL','supplierCalls':0}
 try:
  paused=True;switch.stop_new(new);save('progress',{'stage':'R4_PAUSED','time':time.time()})
  image=ROOT/'quota/workspaces.xfs'
  if not image.exists():call(['fallocate','-l','4G',image]);call(['mkfs.xfs','-f','-m','uuid='+PLAN['uuid'],image])
  else:assert image.stat().st_size==4294967296 and call(['blkid','-p','-s','UUID','-o','value',image])==PLAN['uuid']
  assert image.stat().st_blocks*512>=4294967296 and shutil.disk_usage('/').free>=5*1024**3
  assert not call(['losetup','-j',image]),'Inspect original attached test device before resuming'
  loop=call(['losetup','--find','--show',image]);assert re.fullmatch('/dev/loop[0-9]{1,5}',loop);(ROOT/'quota/loop-device').write_text(loop)
  call(['mount','-o','prjquota,noatime',loop,ROOT/'workspaces']);assert call(['findmnt','-n','-o','UUID','--target',ROOT/'workspaces'])==PLAN['uuid']
  c=json.loads((ROOT/'compose.json').read_text());runner=c['services']['runner']['environment']
  runtime=json.loads(Path('/etc/docker/daemon.json').read_text())['runtimes']['runsc'];digest=hashlib.sha256(Path(runtime['path']).read_bytes()).hexdigest();assert digest=='210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a'
  (ROOT/'runtime-attestation.json').write_text(json.dumps({**runtime,'sha256':digest,'verifiedAt':int(time.time())}));(ROOT/'runtime-attestation.json').chmod(0o444)
  helper_image=runner['PLATFORM_WORKSPACE_RUNNER_HELPER_IMAGE'];sandbox=runner['PLATFORM_WORKSPACE_ENVIRONMENT_IMAGE_ID'];file_image=runner['PLATFORM_WORKSPACE_FILES_HELPER_IMAGE']
  file_policy='file-stream-policy-v1|'+file_image+'|'+helper_image+'|'+str(ROOT/'workspaces')+'|'+PLAN['uuid']+'|'+loop+'|uid10001|runc-trusted-file-only|ro|net-none|caps-none|no-devices|no-socket|memory64m-swap64m|cpu1|pids32|tmpfs8m|raw64k|header64k|nfc|nofollow|sha256|single-work-bind|scan-v1-100entries-28k|create-noreplace|require-loop-device|file-stream-v2-sha256-trailer32'
  policy='environment-v3|'+sandbox+'|'+helper_image+'|'+str(ROOT/'workspaces')+'|'+PLAN['uuid']+'|'+loop+'|'+digest+'|systrap|uid10001|ro|net-none|caps-none|devices-none|memory512m-swap512m|cpu1|pids128|nproc32|tmpfs64m|guest-no-file-bridge|trusted-file-helper:runc|uid10001|ro|net-none|caps-none|devices-none|memory64m-swap64m|cpu1|pids32|tmpfs8m|bridge-v3-jsonl|startup-canary-v1'
  for row in c['services'].values():
   env=row.get('environment',{})
   if 'PLATFORM_WORKSPACE_ENVIRONMENT_POLICY_HASH' in env:env['PLATFORM_WORKSPACE_ENVIRONMENT_POLICY_HASH']=hashlib.sha256(policy.encode()).hexdigest()
   if 'PLATFORM_WORKSPACE_FILES_POLICY_HASH' in env:env['PLATFORM_WORKSPACE_FILES_POLICY_HASH']=hashlib.sha256(file_policy.encode()).hexdigest()
   if 'PLATFORM_WORKSPACE_RUNNER_WORKSPACE_ROOT' in env:env.update(PLATFORM_WORKSPACE_RUNNER_WORKSPACE_ROOT=str(ROOT/'workspaces'),PLATFORM_WORKSPACE_RUNNER_MOUNT_UUID=PLAN['uuid'],PLATFORM_WORKSPACE_RUNNER_QUOTA_DEVICE=loop)
   if row.get('labels',{}).get('ap.role')=='runner':row['group_add']=[str(Path('/var/run/docker.sock').stat().st_gid)]
  (ROOT/'compose.json').write_text(json.dumps(c,indent=2));call(BASE+['config','--quiet'])
  call(BASE+['up','-d','--wait','--wait-timeout','150','postgres','rabbitmq'],timeout=180)
  operator('cloud-migrate');assert sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;')==str(PLAN.get('schema',18))
  for role,secret in [('ap_api','api_password'),('ap_worker','worker_password'),('ap_runner','runner_password'),('ap_migration','postgres_password')]:
   password=Path(c['secrets'][secret]['file']).read_text().strip();assert re.fullmatch('[A-Za-z0-9_-]+',password)
   sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" LOGIN; END IF; END $$;ALTER ROLE "+role+" PASSWORD '"+password+"';")
  grant_files=['r1-runtime-grants.sql','r2-runtime-grants.sql','r3-workspace-grants.sql','r3-environment-grants.sql','r3-file-grants.sql','r4-runtime-grants.sql','r4-permission-grants.sql','r4-approval-grants.sql','r4-workspace-tools-grants.sql']
  if STAGE in ['r52','r53']:grant_files.append('r5-file-grants.sql')
  if STAGE=='r53':grant_files.append('r5-preview-grants.sql')
  # Named service/ops roles must exist before any grant script; do not discover them one failure at a time.
  names=set(re.findall(r'\bap_[a-z_]+\b','\n'.join((ROOT/name).read_text() for name in grant_files)))
  for role in sorted(names):
   assert re.fullmatch('ap_[a-z_]+',role)
   sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" NOLOGIN; END IF; END $$;")
  for name in grant_files:sql((ROOT/name).read_text())
  requests=json.loads((ROOT/'accounts/requests.json').read_text())
  for name in [STAGE+'_admin',STAGE+'_user']:
   operator('identity-ops',['--identity-action='+('bootstrap' if name==STAGE+'_admin' else 'create'),'--username='+name,'--request-id='+requests[name],'--password-file=/tmp/operator-input'],(ROOT/'accounts'/(name+'.password')).read_bytes())
  call(BASE+['up','-d','master','volume','filer','s3','redis'])
  # Known internal SDK readiness and explicit once-only bucket bootstrap.
  s3_script="import boto3,time\nfrom botocore.config import Config\nfrom botocore.exceptions import ClientError\naccess=open('/run/access').read().strip();secret=open('/run/secret').read().strip()\nclient=boto3.client('s3',endpoint_url='http://s3:8333',region_name='us-east-1',aws_access_key_id=access,aws_secret_access_key=secret,config=Config(retries={'max_attempts':0},connect_timeout=2,read_timeout=5))\nexists=False\nfor index in range(30):\n try:\n  client.head_bucket(Bucket='r33-assets');exists=True;break\n except ClientError as error:\n  status=error.response['ResponseMetadata']['HTTPStatusCode']\n  if status==404:break\n  if status<500 or index==29:raise\n  time.sleep(2)\n except Exception:\n  if index==29:raise\n  time.sleep(2)\nif not exists:client.create_bucket(Bucket='r33-assets')\nclient.head_bucket(Bucket='r33-assets')\nclient.close()\nprint('R51_PRIVATE_BUCKET_READY')\n"
  access=str(ROOT/'bootstrap-access');secret=str(ROOT/'bootstrap-secret')
  call(['docker','run','--rm','-i','--network',PLAN['project']+'-control','--memory','64m','--memory-swap','64m','--user','10001:10001','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--mount','type=bind,src='+access+',dst=/run/access,readonly','--mount','type=bind,src='+secret+',dst=/run/secret,readonly','--entrypoint','python','sha256:e32ffc7386c0bdd9e8adcb497b5ad73f01b3da58fb1fbf09b9c4216c4b1670bf','-'],s3_script.encode(),timeout=120)
  call(BASE+['up','-d','runner'])
  for _ in range(60):
   identity=cid('runner');row=json.loads(call(['docker','inspect',identity]))[0];assert row['State']['Running'] and not row['State']['OOMKilled']
   if 'Started PlatformApplication' in call(['docker','logs','--since',row['State']['StartedAt'],'--tail','35',identity]):break
   time.sleep(2)
  else:raise RuntimeError('Runner did not become ready')
  call(BASE+['up','-d','--wait','--wait-timeout','150','api','worker','gateway'],timeout=180)
  client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
  with client.open('http://127.0.0.1:'+str(PLAN.get('port',18151))+'/api/v1/readiness',timeout=10) as response:assert json.load(response)=={'status':'READY'}
  gateway=json.loads(call(['docker','inspect',cid('gateway')]))[0];image=json.loads(call(['docker','image','inspect',gateway['Image']]))[0]
  save('ready',{'status':'READY','root':str(ROOT),'project':PLAN['project'],'time':time.time(),'initialStagingSource':PLAN['source'],'gatewaySource':image['Config']['Labels']['org.opencontainers.image.revision'],'gatewayConfigId':gateway['Image'],'supplierCalls':0});sample()
  deadline=time.monotonic()+900
  while time.monotonic()<deadline and not (ROOT/'finish-request.json').exists():time.sleep(3)
  assert (ROOT/'finish-request.json').exists(),'Native client window timed out'
  result['status']='PASS' if json.loads((ROOT/'finish-request.json').read_text())['status']=='PASS' else 'FAIL'
 except BaseException:(REPORT/'failure.private.txt').write_text(traceback.format_exc())
 finally:
  try:
   if paused:restore(snapshot)
   result['r4Restored']=True
  except BaseException:result.update(status='FAIL',r4Restored=False);(REPORT/'restore-failure.private.txt').write_text(traceback.format_exc())
  save('window-result',result)
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['start','status','sample','restore']);a=p.parse_args()
 if a.action=='start':start()
 elif a.action=='sample':print(json.dumps(sample()))
 elif a.action=='restore':
  with (switch.OPS/'switch.lock').open('a') as lock:fcntl.flock(lock,fcntl.LOCK_EX);restore(json.loads((REPORT/'production-before.json').read_text()))
 else:print(json.dumps({'services':status(),'ready':(REPORT/'ready.json').exists(),'finished':json.loads((REPORT/'window-result.json').read_text()) if (REPORT/'window-result.json').exists() else None}))
if __name__=='__main__':main()
