"""Receipted host operations for R3; failed mutations restore retained R2.

No provider calls. All command output and backups stay in the private window.
"""
from pathlib import Path
import argparse,fcntl,gzip,hashlib,json,os,re,shutil,subprocess,time,urllib.request
ROOT=Path('/srv/agentplatform');MIB=1024**2
class Window:
 def __init__(self,folder):
  self.folder=folder.resolve(strict=True);assert re.fullmatch(r'/srv/agentplatform/operations/r3-release-[a-f0-9]{7,40}-\d{8}',str(self.folder))
  self.stage=json.loads((self.folder/'stage.json').read_text());self.rows=json.loads((self.folder/'source-containers.private.json').read_text())
  assert self.hash(self.folder/'compose.json')==self.stage['composeSha256'],'Staged input changed'
  self.by={r['Config']['Labels']['com.docker.compose.service']:r for r in self.rows}
  self.base=['docker','compose','-p','agentplatform-r3-app','-f',str(self.folder/'compose.json')]
  self.log=(self.folder/'commands.private.log').open('a',buffering=1)
 def run(self,args,input=None,timeout=240):
  p=subprocess.run(args,input=input,capture_output=True,timeout=timeout)
  self.log.write(json.dumps({'program':args[0],'exitCode':p.returncode,'outputSha256':hashlib.sha256(p.stdout+p.stderr).hexdigest()})+'\n')
  if p.returncode:
   (self.folder/'last-command.private.log').write_bytes(p.stdout+p.stderr)
   raise RuntimeError('Command failed; protected diagnostic retained')
  return p.stdout.decode().strip()
 def sql(self,text):return self.run(['docker','exec','-i',self.by['postgres']['Id'],'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','ap_admin','-d','agentplatform'],text.encode())
 def receipt(self,name,row):
  path=self.folder/(name+'.json');assert not path.exists(),'Inspect completed operation; no blind replay'
  path.write_text(json.dumps({**row,'recordedAt':time.time()},indent=2))
 def capacity(self,extra=0):
  assert shutil.disk_usage('/').free>=5*1024**3+extra,'Root safety reserve'
  available=int(next(x.split()[1] for x in Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')))*1024
  assert available>=768*MIB,'Host available memory reserve'
 def cid(self,name):return self.run(self.base+['ps','-a','-q',name])
 def stop_r2_apps(self):
  for name in ['gateway','api','worker']:self.run(['docker','stop','-t','60',self.by[name]['Id']])
  assert self.sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL;')=='0'
  assert self.sql("SELECT count(*) FROM pf.task WHERE status IN('QUEUED','RUNNING');")=='0'
 def backup(self,generation):
  dest=self.folder/('backup-'+generation);assert not dest.exists();self.capacity(256*MIB)
  if generation=='r2':self.stop_r2_apps()
  else:self.stop_r3_apps()
  dest.mkdir(mode=0o700)
  with (dest/'postgres.sql').open('wb') as stream:
   subprocess.run(['docker','exec',self.by['postgres']['Id'],'pg_dumpall','-U','ap_admin'],stdout=stream,stderr=self.log,check=True,timeout=120)
  self.run(['docker','exec',self.by['rabbitmq']['Id'],'rabbitmqctl','export_definitions','/tmp/r3-release-definitions.json'])
  self.run(['docker','cp',self.by['rabbitmq']['Id']+':/tmp/r3-release-definitions.json',str(dest/'rabbitmq-definitions.json')])
  for name in ['s3','filer','volume','master','redis','rabbitmq','postgres']:self.run(['docker','stop','-t','60',self.by[name]['Id']])
  self.run(['tar','-C',str(ROOT),'-czf',str(dest/'cold.tgz'),'data','deploy','config','secrets'],timeout=180)
  shutil.copytree(self.folder/'secrets',dest/'r3-secrets')
  for name in ['compose.json','stage.json','s3.candidate.json']:shutil.copy2(self.folder/name,dest/name)
  mount=self.run(['findmnt','-n','-o','UUID','--target',str(ROOT/'workspaces')]);assert mount==self.stage['productionUuid']
  self.run(['sync']);frozen=False;h=hashlib.sha256();size=0
  try:
   self.run(['xfs_freeze','-f',str(ROOT/'workspaces')]);frozen=True
   with (ROOT/'quota/workspaces.xfs').open('rb') as source,(dest/'workspaces.xfs.gz').open('xb') as target,gzip.GzipFile(fileobj=target,mode='wb',compresslevel=1,mtime=0) as zipped:
    while chunk:=source.read(MIB):h.update(chunk);size+=len(chunk);zipped.write(chunk)
  finally:
   if frozen:self.run(['xfs_freeze','-u',str(ROOT/'workspaces')])
  check=hashlib.sha256();read=0
  with gzip.open(dest/'workspaces.xfs.gz','rb') as stream:
   while chunk:=stream.read(MIB):check.update(chunk);read+=len(chunk)
  assert size==read==4*1024**3 and h.hexdigest()==check.hexdigest()
  self.run(['python3',str(ROOT/'deploy/scripts/workspace-manifest.py'),'write',str(ROOT/'workspaces'),str(dest/'workspace-files.json')])
  (dest/'quota-report.txt').write_text(self.run(['xfs_quota','-x','-c','report -p -b -i',str(ROOT/'workspaces')]))
  hashes={p.name:self.hash(p) for p in dest.iterdir() if p.is_file()}
  (dest/'checksums.json').write_text(json.dumps(hashes,indent=2));self.capacity()
  self.receipt('backup-'+generation,{'status':'PASS','hashes':hashes,'rawXfsSha256':h.hexdigest(),'rawXfsBytes':size,'uuid':mount,'compressed':True})
 @staticmethod
 def hash(path):
  h=hashlib.sha256()
  with path.open('rb') as stream:
   while chunk:=stream.read(MIB):h.update(chunk)
  return h.hexdigest()
 def start_dependencies(self):
  for name in ['postgres','rabbitmq','redis','master','volume','filer','s3']:
   self.run(['docker','start',self.by[name]['Id']])
   if name in ['postgres','rabbitmq','filer','s3']:
    for _ in range(120):
     r=json.loads(self.run(['docker','inspect',self.by[name]['Id']]))[0]
     assert r['State']['Running'] and not r['State']['OOMKilled']
     if r['State'].get('Health',{}).get('Status','healthy')=='healthy':break
     time.sleep(2)
    else:raise RuntimeError('Dependency health did not converge: '+name)
 def configure(self):
  private=self.folder/'secrets'
  for role,name in [('ap_runner','runner-password'),('ap_workspace_ops','ops-password')]:
   password=(private/name).read_text();assert re.fullmatch('[A-Za-z0-9_-]+',password)
   self.sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" LOGIN;END IF;END $$;ALTER ROLE "+role+" PASSWORD '"+password+"';")
  self.run(self.base+['run','--rm','-T','-e','SPRING_PROFILES_ACTIVE=cloud-migrate','operator'],timeout=180)
  for name in ['r1-runtime-grants.sql','r2-runtime-grants.sql','r3-workspace-grants.sql','r3-environment-grants.sql','r3-file-grants.sql']:self.sql((self.folder/'deploy/config'/name).read_text())
  assert self.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;')=='14'
  assert self.sql('SELECT schema_version FROM pf.baseline_version WHERE id=1;')=='1'
  # Shared Spring AMQP connection uses existing chat identities. Grant only the
  # six fixed workspace resources, preserving their existing chat permissions.
  topology=['ap.workspace.commands.v1','ap.workspace.provision.v1','ap.workspace.environments.v2','ap.workspace.environment.v2','ap.workspace.files.v1','ap.workspace.file.v1']
  pattern='^('+('|'.join(re.escape(x) for x in topology))+')$'
  definitions=json.loads((self.folder/'backup-r2/rabbitmq-definitions.json').read_text())
  permissions=[]
  for item in definitions['permissions']:
   if item['user'] in ['mq_publisher','mq_execution'] and item['vhost']=='/agentplatform':
    permissions.append({**item,**{k:'('+item[k]+')|('+pattern+')' for k in ['configure','write','read']}})
  assert len(permissions)==2
  import_data={'permissions':permissions,'exchanges':[],'queues':[],'bindings':[]}
  for exchange,queue,route in [('ap.workspace.commands.v1','ap.workspace.provision.v1','workspace.provision'),('ap.workspace.environments.v2','ap.workspace.environment.v2','workspace.environment'),('ap.workspace.files.v1','ap.workspace.file.v1','workspace.file')]:
   import_data['exchanges'].append({'name':exchange,'vhost':'/agentplatform','type':'direct','durable':True,'auto_delete':False,'internal':False,'arguments':{}})
   import_data['queues'].append({'name':queue,'vhost':'/agentplatform','durable':True,'auto_delete':False,'arguments':{}})
   import_data['bindings'].append({'source':exchange,'vhost':'/agentplatform','destination':queue,'destination_type':'queue','routing_key':route,'arguments':{}})
  path=self.folder/'mq-r3.private.json';path.write_text(json.dumps(import_data))
  self.run(['docker','cp',str(path),self.by['rabbitmq']['Id']+':/tmp/r3-permissions.json'])
  self.run(['docker','exec','--user','0',self.by['rabbitmq']['Id'],'chown','999:999','/tmp/r3-permissions.json'])
  self.run(['docker','exec',self.by['rabbitmq']['Id'],'rabbitmqctl','import_definitions','/tmp/r3-permissions.json'])
 def activate(self,receipt='activate'):
  assert (self.folder/'backup-r2.json').exists();self.capacity()
  for row in self.rows:assert not json.loads(self.run(['docker','inspect',row['Id']]))[0]['State']['Running'],'Original R2 must be stopped serially'
  for name,digest in json.loads((self.folder/'deploy-input-hashes.json').read_text()).items():assert self.hash(self.folder/'deploy/config'/name)==digest
  for name,limit in self.stage['middlewareCapsMiB'].items():
   self.run(['docker','update','--memory',str(limit)+'m','--memory-swap',str(limit)+'m',self.by[name]['Id']])
  # Preserve the original file inode for the retained S3 container bind mount.
  with (ROOT/'config/s3.json').open('w') as stream:stream.write((self.folder/'s3.candidate.json').read_text())
  self.start_dependencies();self.configure()
  self.run(self.base+['--profile','application','up','-d','--wait','--wait-timeout','240','runner','api','worker','gateway'],timeout=300)
  self.health();self.receipt(receipt,{'status':'PASS','migration':14,'runtimeBaseline':1,'supplierCalls':0})
 def health(self):
  request=urllib.request.Request('http://127.0.0.1/api/v1/readiness',headers={'Host':'agentplatform.lailihao.cloud'})
  with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request,timeout=10) as response:assert response.status==200
 def stop_r3_apps(self):
  self.run(self.base+['stop','gateway','api'],timeout=150)
  query="SELECT (SELECT count(*) FROM pf.file_operation WHERE status NOT IN('COMMITTED','FAILED'))+(SELECT count(*) FROM pf.workspace_file_lease WHERE status<>'FREE')+(SELECT count(*) FROM pf.file_transfer_slot WHERE owner_id IS NOT NULL)+(SELECT count(*) FROM pf.workspace_environment_operation WHERE status NOT IN('COMMITTED','FAILED'));"
  for _ in range(90):
   if self.sql(query)=='0':break
   time.sleep(2)
  else:raise RuntimeError('Original worker/runner work did not drain; retain facts')
  self.run(self.base+['stop','worker'],timeout=90)
  # Disable only future scans; original MQ work continues while runner lives.
  config=json.loads((self.folder/'compose.json').read_text());worker=config['services']['worker']['environment']
  worker['SPRING_PROFILES_ACTIVE']+=',workspace-maintenance';worker['PLATFORM_WORKSPACE_FILES_MAINTENANCE_SCAN_ADMISSION_ENABLED']='false'
  path=self.folder/'compose.maintenance.json';path.write_text(json.dumps(config))
  drain=['docker','compose','-p','agentplatform-r3-app','-f',str(path)]
  self.run(drain+['--profile','application','up','-d','worker'],timeout=120)
  for _ in range(90):
   count=self.sql(query)
   if count=='0':break
   time.sleep(2)
  else:raise RuntimeError('Unresolved operation prevents stop/snapshot; no reset')
  assert self.sql('SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL;')=='0','Sleep/delete environment before release snapshot'
  self.run(drain+['stop','worker','runner'],timeout=150)
 def rollback(self):
  # No automatic data restore or destructive migration rollback.
  self.run(self.base+['--profile','*','stop'],timeout=240)
  backup=self.folder/'backup-r2'
  if backup.exists():
   # S3 configuration only; database remains at additive schema14.
   import tarfile
   with tarfile.open(backup/'cold.tgz') as archive:
    stream=archive.extractfile('config/s3.json');assert stream is not None
    with (ROOT/'config/s3.json').open('wb') as target:shutil.copyfileobj(stream,target)
  for name in ['postgres','rabbitmq','redis','master','volume','filer','s3']:
   row=self.by[name];host=row['HostConfig']
   self.run(['docker','update','--memory',str(host['Memory']),'--memory-swap',str(host['MemorySwap']),row['Id']])
  self.start_dependencies()
  if (backup/'rabbitmq-definitions.json').exists():
   self.run(['docker','cp',str(backup/'rabbitmq-definitions.json'),self.by['rabbitmq']['Id']+':/tmp/r3-rollback-definitions.json'])
   self.run(['docker','exec','--user','0',self.by['rabbitmq']['Id'],'chown','999:999','/tmp/r3-rollback-definitions.json'])
   self.run(['docker','exec',self.by['rabbitmq']['Id'],'rabbitmqctl','import_definitions','/tmp/r3-rollback-definitions.json'])
  for name in ['api','worker','gateway']:self.run(['docker','start',self.by[name]['Id']])
  for _ in range(90):
   try:self.health();break
   except Exception:time.sleep(2)
  else:raise RuntimeError('R2 readiness did not recover')
  return {'status':'PASS','exactR2ContainersRetained':True,'originalCapsRestored':True,'migration':int(self.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;')),'supplierCalls':0}
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['start','backup-r2','activate','backup-r3','rollback','redeploy','retry-activation']);p.add_argument('folder',type=Path);a=p.parse_args();os.umask(0o077);w=Window(a.folder)
 with (ROOT/'operations/platform-switch/switch.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  try:
   assert not (w.folder/(a.action+'.json')).exists(),'Inspect original action before retry'
   if a.action=='start':w.backup('r2');w.activate();w.receipt('start',{'status':'PASS','supplierCalls':0})
   elif a.action.startswith('backup-'):w.backup(a.action.removeprefix('backup-'))
   elif a.action in ['activate','redeploy','retry-activation']:
    if a.action in ['redeploy','retry-activation']:
     if a.action=='redeploy':assert (w.folder/'rollback.json').exists() and (w.folder/'restore.json').exists()
     else:assert (w.folder/'failure-restoration.json').exists() and not (w.folder/'activate.json').exists()
     w.stop_r2_apps()
     for name in ['s3','filer','volume','master','redis','rabbitmq','postgres']:w.run(['docker','stop','-t','60',w.by[name]['Id']])
    w.activate(a.action)
   else:w.receipt('rollback',w.rollback())
  except BaseException:
   if a.action!='rollback':
    try:w.receipt('failure-'+a.action+'-restoration',w.rollback())
    except BaseException:(w.folder/'restoration-required').write_text('Original failure and restoration require attention')
   raise
if __name__=='__main__':main()
