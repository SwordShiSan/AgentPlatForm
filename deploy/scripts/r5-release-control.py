"""Receipted R4 -> R5 host release. All raw backup/config material stays private."""
from pathlib import Path
import argparse,fcntl,gzip,hashlib,importlib.util,json,os,re,shutil,signal,subprocess,tarfile,time,urllib.request
ROOT=Path('/srv/agentplatform');MIB=1024**2
def interrupted(signum,frame):
 raise InterruptedError('Release termination requested; recover exact retained production graph')
class Window:
 def __init__(self,folder):
  self.folder=folder.resolve(strict=True);assert re.fullmatch('/srv/agentplatform/operations/r5-release-[a-f0-9]{12}-[0-9]{8}',str(self.folder))
  self.stage=json.loads((self.folder/'stage.json').read_text());assert self.hash(self.folder/'compose.json')==self.stage['composeSha256']
  self.profile=json.loads((self.folder/'r5-release-profile.json').read_text());assert self.hash(self.folder/'r5-release-profile.json')==self.stage['profileSha256']
  assert self.profile['migration']==21 and self.profile['previousMigration']==18
  self.rows=json.loads((self.folder/'source-containers.private.json').read_text());self.by={r['Config']['Labels']['com.docker.compose.service']:r for r in self.rows};assert len(self.by)==11
  self.base=['docker','compose','-p','agentplatform-r5-app','-f',str(self.folder/'compose.json')];self.log=(self.folder/'commands.private.log').open('a',buffering=1)
 def run(self,args,input=None,timeout=240):
  p=subprocess.run(args,input=input,capture_output=True,timeout=timeout);self.log.write(json.dumps({'program':args[0],'exitCode':p.returncode,'outputSha256':hashlib.sha256(p.stdout+p.stderr).hexdigest()})+'\n')
  if p.returncode:(self.folder/'last-command.private.log').write_bytes(p.stdout+p.stderr);raise RuntimeError('Release command failed; private evidence retained')
  return p.stdout.decode().strip()
 def sql(self,text):return self.run(['docker','exec','-i',self.by['postgres']['Id'],'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','ap_admin','-d','agentplatform'],text.encode())
 @staticmethod
 def hash(path):
  h=hashlib.sha256()
  with path.open('rb') as f:
   while chunk:=f.read(MIB):h.update(chunk)
  return h.hexdigest()
 def receipt(self,name,value):
  path=self.folder/(name+'.json');assert not path.exists(),'Inspect existing action before retry';path.write_text(json.dumps({**value,'recordedAt':time.time()},indent=2))
 def capacity(self,extra=0):
  assert shutil.disk_usage('/').free>=5*1024**3+extra
  assert int(next(l.split()[1] for l in Path('/proc/meminfo').read_text().splitlines() if l.startswith('MemAvailable:')))*1024>=768*MIB
 def cid(self,name):return self.run(self.base+['ps','-a','-q',name])
 def idle(self):
  base="SELECT (SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL)+(SELECT count(*) FROM pf.task WHERE status IN('QUEUED','RUNNING','WAITING_INPUT'))+(SELECT count(*) FROM pf.file_operation WHERE status NOT IN('COMMITTED','FAILED'))+(SELECT count(*) FROM pf.workspace_file_lease WHERE status<>'FREE')+(SELECT count(*) FROM pf.file_transfer_slot WHERE owner_id IS NOT NULL)+(SELECT count(*) FROM pf.workspace_environment_operation WHERE status NOT IN('COMMITTED','FAILED'))+(SELECT count(*) FROM pf.workspace_sandbox_slot WHERE environment_id IS NOT NULL)"
  schema=int(self.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;'))
  if schema==21:base+="+(SELECT count(*) FROM pf.heavy_runtime_slot WHERE kind IS NOT NULL)+(SELECT count(*) FROM pf.preview_job WHERE state IN('QUEUED','RUNNING','BLOCKED'))+(SELECT count(*) FROM pf.document_extraction WHERE status IN('QUEUED','RUNNING','BLOCKED'))"
  return self.sql(base+';')=='0'
 def stop_apps(self,generation):
  ids={n:self.by[n]['Id'] if generation=='r4' else self.cid(n) for n in ['gateway','api','worker','runner']}
  for n in ['gateway','api']:self.run(['docker','stop','-t','60',ids[n]])
  for _ in range(90):
   if self.idle():break
   time.sleep(2)
  else:raise RuntimeError('Active or UNKNOWN ownership prevents release snapshot')
  for n in ['worker','runner']:self.run(['docker','stop','-t','60',ids[n]])
  assert self.idle()
  if generation=='r5':assert self.sql("SELECT count(*) FROM pf.task WHERE status IN('QUEUED','RUNNING','WAITING_INPUT');")=='0','Drain new bundle before old program rollback'
 def stop_dependencies(self):
  for n in ['s3','filer','volume','master','redis','rabbitmq','postgres']:self.run(['docker','stop','-t','60',self.by[n]['Id']])
 def start_dependencies(self):
  for n in ['postgres','rabbitmq','redis','master','volume','filer','s3']:
   self.run(['docker','start',self.by[n]['Id']])
   for _ in range(100):
    row=json.loads(self.run(['docker','inspect',self.by[n]['Id']]))[0];assert row['State']['Running'] and not row['State']['OOMKilled']
    if row['State'].get('Health',{}).get('Status','healthy')=='healthy':break
    time.sleep(2)
   else:raise RuntimeError('Dependency health failed: '+n)
 def backup(self,generation):
  dest=self.folder/('backup-'+generation);assert not dest.exists();self.capacity(256*MIB);self.stop_apps(generation);dest.mkdir(mode=0o700)
  migration=18 if generation=='r4' else 21
  assert migration in (18,21)
  self.run(['python3',str(self.folder/'r5-database-evidence.py'),self.by['postgres']['Id'],'--expected-migration',str(migration),'--output',str(dest/'database.json')])
  with (dest/'postgres.sql').open('wb') as f:subprocess.run(['docker','exec',self.by['postgres']['Id'],'pg_dumpall','-U','ap_admin'],stdout=f,stderr=self.log,check=True,timeout=180)
  self.run(['docker','exec',self.by['rabbitmq']['Id'],'rabbitmqctl','export_definitions','/tmp/r5-release-definitions.json']);self.run(['docker','cp',self.by['rabbitmq']['Id']+':/tmp/r5-release-definitions.json',str(dest/'rabbitmq-definitions.json')])
  self.stop_dependencies();self.run(['tar','-C',str(ROOT),'-czf',str(dest/'cold.tgz'),'data','deploy','config','secrets'],timeout=300)
  original=json.loads(Path(self.stage['originalCompose']).read_text());secretRoot=Path(original['secrets']['runner_password']['file']).parent
  assert secretRoot.is_relative_to(ROOT/'operations') and secretRoot.name=='secrets'
  shutil.copytree(secretRoot,dest/'r5-secrets');(dest/'r5-key-hashes.json').write_text(json.dumps({p.name:self.hash(p) for p in (dest/'r5-secrets').iterdir() if p.is_file()},indent=2))
  for name in ['compose.json','stage.json','s3.candidate.json']:shutil.copy2(self.folder/name,dest/name)
  if generation=='r4':shutil.copy2(self.stage['originalCompose'],dest/'compose.json')
  (dest/'secret-source.json').write_text(json.dumps({'original':str(secretRoot)}))
  mount=self.run(['findmnt','-n','-o','UUID','--target',str(ROOT/'workspaces')]);assert mount==self.stage['productionUuid'];self.run(['sync']);frozen=False;h=hashlib.sha256();size=0
  try:
   self.run(['xfs_freeze','-f',str(ROOT/'workspaces')]);frozen=True
   with (ROOT/'quota/workspaces.xfs').open('rb') as source,(dest/'workspaces.xfs.gz').open('xb') as f,gzip.GzipFile(fileobj=f,mode='wb',compresslevel=1,mtime=0) as z:
    while chunk:=source.read(MIB):h.update(chunk);size+=len(chunk);z.write(chunk)
  finally:
   if frozen:self.run(['xfs_freeze','-u',str(ROOT/'workspaces')])
  check=hashlib.sha256();count=0
  with gzip.open(dest/'workspaces.xfs.gz','rb') as f:
   while chunk:=f.read(MIB):check.update(chunk);count+=len(chunk)
  assert size==count==4*1024**3 and h.hexdigest()==check.hexdigest()
  self.run(['python3',str(self.folder/'workspace-manifest.py'),'write',str(ROOT/'workspaces'),str(dest/'workspace-files.json')]);(dest/'quota-report.txt').write_text(self.run(['xfs_quota','-x','-c','report -p -b -i',str(ROOT/'workspaces')]))
  hashes={p.name:self.hash(p) for p in dest.iterdir() if p.is_file()};(dest/'checksums.json').write_text(json.dumps(hashes,indent=2));self.capacity();self.receipt('backup-'+generation,{'status':'PASS','hashes':hashes,'rawXfsSha256':h.hexdigest(),'rawXfsBytes':size,'uuid':mount,'migration':migration,'supplierCalls':0})
 def health(self):
  req=urllib.request.Request('http://127.0.0.1/api/v1/readiness',headers={'Host':'agentplatform.lailihao.cloud'})
  with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=10) as r:assert r.status==200
 def activate(self,name='activate'):
  assert (self.folder/'backup-r4.json').exists();self.capacity()
  assert all(not json.loads(self.run(['docker','inspect',r['Id']]))[0]['State']['Running'] for r in self.rows)
  with (ROOT/'config/s3.json').open('w') as f:f.write((self.folder/'s3.candidate.json').read_text())
  self.start_dependencies();self.run(self.base+['--profile','operator','run','--rm','-T','-e','SPRING_PROFILES_ACTIVE=cloud-migrate','operator'],timeout=240)
  for f in ['r1-runtime-grants.sql','r2-runtime-grants.sql','r3-workspace-grants.sql','r3-environment-grants.sql','r3-file-grants.sql','r4-runtime-grants.sql','r4-permission-grants.sql','r4-approval-grants.sql','r4-workspace-tools-grants.sql','r5-file-grants.sql','r5-preview-grants.sql','r5-workbench-grants.sql']:
   content=(self.folder/'deploy/config'/f).read_text()
   for role in sorted(set(re.findall(r'\bap_[a-z_]+\b',content))):
    self.sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" NOLOGIN; END IF; END $$;")
   self.sql(content)
  assert self.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;')=='21' and self.sql('SELECT schema_version FROM pf.baseline_version WHERE id=1;')=='1'
  self.run(self.base+['--profile','application','up','-d','runner'],timeout=150)
  for _ in range(100):
   r=json.loads(self.run(['docker','inspect',self.cid('runner')]))[0];assert r['State']['Running'] and not r['State']['OOMKilled']
   if 'Started PlatformApplication' in self.run(['docker','logs','--since',r['State']['StartedAt'],'--tail','50',r['Id']]):break
   time.sleep(2)
  else:raise RuntimeError('Runner startup failed')
  self.run(self.base+['--profile','application','up','-d','--wait','--wait-timeout','240','api','worker','gateway'],timeout=300)
  self.health();self.receipt(name,{'status':'PASS','migration':21,'runtimeBaseline':1,'supplierCalls':0})
 def restore_snapshot(self,generation):
  """Restore only a verified maintenance snapshot, with all source writers stopped.

  Never used after real writes open. Existing data directories are retained in
  this release folder, not deleted or mixed with restored PostgreSQL files.
  """
  assert generation in ['r4','r5'] and not (self.folder/'writes-open-intent.json').exists() and not (self.folder/'writes-open.json').exists()
  backup=self.folder/('backup-'+generation);proof=json.loads((self.folder/('backup-'+generation+'.json')).read_text())
  assert proof['status']=='PASS' and proof['migration']==(18 if generation=='r4' else 21)
  for name,digest in json.loads((backup/'checksums.json').read_text()).items():assert self.hash(backup/name)==digest
  ids=self.run(['docker','ps','-q']).split()
  for row in json.loads(self.run(['docker','inspect',*ids])) if ids else []:
   assert not any(m.get('Source','').startswith(str(ROOT)+'/'+part+'/') for m in row.get('Mounts',[]) for part in ['data','config','secrets']),'Snapshot replacement requires all bound services stopped'
  assert self.run(['findmnt','-n','-o','UUID','--target',str(ROOT/'workspaces')])==proof['uuid']
  self.run(['umount',str(ROOT/'workspaces')]);stamp=str(time.time_ns());detached=self.folder/('detached-before-'+generation+'-'+stamp);detached.mkdir(mode=0o700)
  self.receipt('restore-intent-'+generation+'-'+stamp,{'snapshot':str(backup),'detached':str(detached),'realWritesOpened':False})
  with tarfile.open(backup/'cold.tgz') as archive:
   for member in archive.getmembers():
    assert member.name.split('/')[0] in ['data','config','deploy','secrets'] and (member.isdir() or member.isfile()) and (ROOT/member.name).resolve().is_relative_to(ROOT),'Unexpected snapshot path or type'
  for part in ['data','config','deploy','secrets']:
   target=ROOT/part;assert target.resolve().parent==ROOT and not target.is_symlink()
   if target.exists():target.rename(detached/part)
  self.run(['tar','-xzf',str(backup/'cold.tgz'),'-C',str(ROOT)],timeout=300)
  image=ROOT/'quota/workspaces.xfs';assert image.is_file() and image.stat().st_size==4*1024**3
  h=hashlib.sha256();size=0
  with gzip.open(backup/'workspaces.xfs.gz','rb') as source,image.open('r+b') as target:
   while chunk:=source.read(MIB):h.update(chunk);size+=len(chunk);target.write(chunk)
   target.flush();os.fsync(target.fileno())
  assert size==4*1024**3 and h.hexdigest()==proof['rawXfsSha256'] and self.hash(image)==h.hexdigest()
  assert self.run(['blkid','-p','-s','UUID','-o','value',str(image)])==proof['uuid']
  self.run(['mount','-o','prjquota,noatime',self.stage['loop'],str(ROOT/'workspaces')])
  self.run(['python3',str(self.folder/'workspace-manifest.py'),'verify',str(ROOT/'workspaces'),str(backup/'workspace-files.json')])
  self.receipt('restored-snapshot-'+generation+'-'+stamp,{'status':'PASS','migration':proof['migration'],'sourceUuidUnchanged':True,'directoriesRetained':str(detached)})

 def rollback(self):
  assert not (self.folder/'writes-open-intent.json').exists() and not (self.folder/'writes-open.json').exists(),'After real writes reopen use data-preserving repair, never an old snapshot'
  running=self.run(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-r5-app'])
  if running:self.stop_apps('r5')
  self.stop_dependencies();self.restore_snapshot('r4')
  self.start_dependencies()
  for n in ['runner','api','worker','gateway']:self.run(['docker','start',self.by[n]['Id']])
  for _ in range(100):
   try:self.health();break
   except Exception:time.sleep(2)
  else:raise RuntimeError('Original R4 health failed')
  for r in self.rows:
   now=json.loads(self.run(['docker','inspect',r['Id']]))[0];assert now['State']['Running'] and now['Image']==r['Image'] and now['HostConfig']['RestartPolicy']==r['HostConfig']['RestartPolicy']
  assert self.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;')=='18'
  return {'status':'PASS','sameR4Containers':True,'matchedSnapshotSchema':18,'supplierCalls':0}

 def recover_preupgrade(self):
  assert not (self.folder/'backup-r4.json').exists() and not (self.folder/'writes-open.json').exists()
  self.start_dependencies()
  assert self.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;')=='18','Never attach the old application to upgraded data'
  for name in ['runner','api','worker','gateway']:self.run(['docker','start',self.by[name]['Id']])
  for _ in range(100):
   try:self.health();return {'status':'PASS','scope':'Original R4 retained, upgrade never started'}
   except Exception:time.sleep(2)
  raise RuntimeError('Pre-upgrade R4 recovery requires attention')
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['start','backup-r5','rollback-drill','redeploy']);p.add_argument('folder',type=Path);a=p.parse_args();os.umask(0o077);w=Window(a.folder)
 with (ROOT/'operations/platform-switch/switch.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  signal.signal(signal.SIGTERM,interrupted)
  try:
   assert not (w.folder/(a.action+'.json')).exists()
   if a.action=='start':w.backup('r4');w.activate();w.receipt('start',{'status':'PASS'})
   elif a.action=='backup-r5':w.backup('r5')
   elif a.action=='rollback-drill':w.receipt(a.action,w.rollback())
   else:
    assert (w.folder/'restore.json').exists() and (w.folder/'rollback-drill.json').exists();w.stop_apps('r4');w.stop_dependencies();w.restore_snapshot('r5');w.activate('redeploy')
  except BaseException:
   try:w.receipt('failure-'+a.action+'-restoration',w.rollback() if (w.folder/'backup-r4.json').exists() else w.recover_preupgrade())
   except BaseException:(w.folder/'restoration-required').write_text('Inspect original failure and exact retained R4 graph')
   raise
if __name__=='__main__':main()
