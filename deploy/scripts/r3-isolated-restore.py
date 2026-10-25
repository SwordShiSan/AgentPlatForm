"""Serial cold restore of R3. No worker, runner, provider or source data writes."""
from pathlib import Path
import argparse,copy,fcntl,gzip,hashlib,importlib.util,ipaddress,json,os,re,shutil,socket,subprocess,tarfile,time,traceback
ROOT=Path('/srv/agentplatform');MIB=1024**2
def main():
 p=argparse.ArgumentParser();p.add_argument('folder',type=Path);a=p.parse_args();folder=a.folder.resolve(strict=True)
 assert re.fullmatch(r'/srv/agentplatform/operations/r3-release-[a-f0-9]{7,40}-\d{8}',str(folder))
 spec=importlib.util.spec_from_file_location('release',folder/'r3-release-control-v5.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);window=mod.Window(folder)
 os.umask(0o077)
 with (ROOT/'operations/platform-switch/switch.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  try:restore(folder,window)
  except BaseException:
   (folder/'restore-failure.private.log').write_text(traceback.format_exc())
   (folder/'restore-failure-r2-recovery.json').write_text(json.dumps(window.rollback(),indent=2));raise
def restore(folder,w):
 assert (folder/'backup-r3.json').exists() and not (folder/'restore.json').exists() and not (folder/'restore-intent.json').exists()
 backup=folder/'backup-r3'
 for name,digest in json.loads((backup/'checksums.json').read_text()).items():assert w.hash(backup/name)==digest
 for project in ['agentplatform-env','agentplatform-app','agentplatform-r3-app','agentplatform-r3-restore-env','agentplatform-r3-restore-app']:
  assert not w.run(['docker','ps','-q','--filter','label=com.docker.compose.project='+project]),'Serial restore only'
 w.capacity(256*MIB)
 root=ROOT/('operations/r3-restore-'+time.strftime('%Y%m%dT%H%M%SZ',time.gmtime()));assert not root.exists()
 env_base=app_base=None;mounted=False;loop=None;success=False
 root.mkdir(mode=0o700);(folder/'restore-intent.json').write_text(json.dumps({'root':str(root),'time':time.time(),'workerStarted':False}))
 try:
  with tarfile.open(backup/'cold.tgz') as archive:
   for member in archive.getmembers():
    assert (root/member.name).resolve().is_relative_to(root) and (member.isfile() or member.isdir()),'Unexpected archive path/type'
  w.run(['tar','-xzf',str(backup/'cold.tgz'),'-C',str(root)],timeout=180)
  shutil.copytree(backup/'r3-secrets',root/'r3-secrets')
  for name,digest in json.loads((backup/'r3-key-hashes.json').read_text()).items():
   key=root/'r3-secrets'/name;assert key.is_file() and w.hash(key)==digest;os.chown(key,10001,10001);key.chmod(0o400)
  for name in ['quota','workspaces','reports']:(root/name).mkdir(mode=0o700)
  image=root/'quota/workspaces.xfs';raw=hashlib.sha256();size=0
  # Same sparse-copy semantics as the verified R2 restore: logical4GiB, no new
  # production capacity promise. Every decompressed byte is still hashed.
  with gzip.open(backup/'workspaces.xfs.gz','rb') as source,image.open('xb') as target:
   while chunk:=source.read(MIB):
    raw.update(chunk);size+=len(chunk)
    if not any(chunk):target.seek(len(chunk),1)
    else:target.write(chunk)
   target.truncate(size);target.flush();os.fsync(target.fileno())
  proof=json.loads((folder/'backup-r3.json').read_text());assert size==4*1024**3 and raw.hexdigest()==proof['rawXfsSha256'] and w.hash(image)==raw.hexdigest()
  loop=w.run(['losetup','--find','--show',str(image)]);assert re.fullmatch('/dev/loop[0-9]+',loop)
  w.run(['mount','-o','nouuid,prjquota',loop,str(root/'workspaces')]);mounted=True
  w.run(['umount',str(root/'workspaces')]);mounted=False;w.run(['losetup','-d',loop]);loop=None
  w.run(['xfs_admin','-U','generate',str(image)])
  uuid=w.run(['blkid','-p','-s','UUID','-o','value',str(image)]);assert uuid!=proof['uuid']
  (root/'quota/filesystem.uuid').write_text(uuid+'\n')
  loop=w.run(['losetup','--find','--show',str(image)]);w.run(['mount','-o','prjquota',loop,str(root/'workspaces')]);mounted=True
  guard=folder/'r3-workspace-restore.py';manifest=ROOT/'deploy/scripts/workspace-manifest.py'
  w.run(['python3',str(guard),'verify-mount',str(root)])
  w.run(['python3',str(manifest),'verify',str(root/'workspaces'),str(backup/'workspace-files.json')])
  w.run(['python3',str(guard),'quota',str(root)])
  w.run(['umount',str(root/'workspaces')]);mounted=False
  rejected=subprocess.run(['python3',str(guard),'verify-mount',str(root)],capture_output=True);assert rejected.returncode!=0 and not list((root/'workspaces').iterdir())
  w.run(['mount','-o','prjquota',loop,str(root/'workspaces')]);mounted=True
  w.run(['python3',str(manifest),'verify',str(root/'workspaces'),str(backup/'workspace-files.json')]);w.capacity()
  with socket.socket() as port:port.bind(('127.0.0.1',18094))
  process_env=dict(os.environ,PLATFORM_ROOT=str(root),PLATFORM_BACKPLANE='agentplatform-r3-restore-control')
  env_base=['docker','compose','--env-file',str(root/'deploy/images.env'),'-p','agentplatform-r3-restore-env']
  for name in ['compose.env.yaml','compose.production.env.yaml','compose.r1-http.env.yaml']:env_base+=['-f',str(root/'deploy'/name)]
  resolved=subprocess.run(env_base+['config','--format','json'],env=process_env,capture_output=True,text=True,timeout=30);assert resolved.returncode==0
  env_config=json.loads(resolved.stdout)
  used=[]
  for identity in w.run(['docker','network','ls','-q']).split():
   network=json.loads(w.run(['docker','network','inspect',identity]))[0]
   used.extend(ipaddress.ip_network(x['Subnet']) for x in (network.get('IPAM',{}).get('Config') or []) if x.get('Subnet') and ':' not in x['Subnet'])
  used.extend(ipaddress.ip_network(x['dst'],strict=False) for x in json.loads(w.run(['ip','-j','route'])) if x.get('dst') not in [None,'default'] and ':' not in x['dst'])
  subnets=['10.234.46.0/24','10.234.47.0/24']
  for subnet in subnets:assert all(not ipaddress.ip_network(subnet).overlaps(x) for x in used)
  env_config['name']='agentplatform-r3-restore-env';env_config['networks']={'backplane':{'name':'agentplatform-r3-restore-control','internal':True,'ipam':{'config':[{'subnet':subnets[0]}]}}}
  (root/'compose.env.json').write_text(json.dumps(env_config,indent=2));env_base=['docker','compose','-p','agentplatform-r3-restore-env','-f',str(root/'compose.env.json')]
  original=json.loads((backup/'compose.json').read_text());app_config={'name':'agentplatform-r3-restore-app','services':{},'secrets':{},'networks':{'control':{'name':'agentplatform-r3-restore-control','external':True},'local-access':{'name':'agentplatform-r3-restore-access','ipam':{'config':[{'subnet':subnets[1]}]}}}}
  def remap(value):
   encoded=json.dumps(value).replace(str(folder/'secrets'),str(root/'r3-secrets')).replace(str(ROOT/'secrets'),str(root/'secrets'))
   return json.loads(encoded)
  app_config['secrets']=remap(original['secrets'])
  for name in ['api','gateway','operator']:
   service=remap(copy.deepcopy(original['services'][name]));service.pop('depends_on',None)
   if name=='api':
    service['environment']['SPRING_PROFILES_ACTIVE']+=',workspace-maintenance';service['environment']['PLATFORM_WORKSPACE_FILES_MAINTENANCE_SCAN_ADMISSION_ENABLED']='false'
   if name=='gateway':service['ports']=[{'target':8080,'published':'18094','host_ip':'127.0.0.1','protocol':'tcp'}]
   if name=='operator':
    service['environment'].update(SPRING_PROFILES_ACTIVE='chat-ops',PLATFORM_CHAT_MASTER_KEY_FILE='/run/private/restored_chat_master')
    service['volumes']=[{'type':'bind','source':str(root/'secrets/chat_master'),'target':'/run/private/restored_chat_master','read_only':True}]
   app_config['services'][name]=service
  (root/'compose.app.json').write_text(json.dumps(app_config,indent=2));app_base=['docker','compose','-p','agentplatform-r3-restore-app','-f',str(root/'compose.app.json')]
  for config in [env_config,app_config]:
   for service in config['services'].values():
    for mount in service.get('volumes',[]):
     if isinstance(mount,dict) and mount.get('type')=='bind':assert Path(mount['source']).resolve().is_relative_to(root),'No production bind mount in restore'
   for secret in config.get('secrets',{}).values():assert Path(secret['file']).resolve().is_relative_to(root)
  w.run(env_base+['up','-d','--wait','--wait-timeout','240','postgres','rabbitmq','redis','master','volume','filer','s3'],timeout=300)
  pg=w.run(env_base+['ps','-q','postgres']);pg=json.loads(w.run(['docker','inspect',pg]))[0]['Id']
  evidence=folder/'r2-database-evidence.py'
  w.run(['python3',str(evidence),pg,'--expected-migration','14','--output',str(root/'reports/database.json'),'--compare',str(backup/'database.json')])
  def sql(query):return w.run(['docker','exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','ap_admin','-d','agentplatform'],query.encode())
  assert sql("SELECT count(*)=1 AND bool_and((value <-> '[1,2,3]'::vector)=0) FROM pf.r04_restore_vector WHERE id='r04-functional-restore';")=='t'
  w.run(app_base+['--profile','operator','run','--rm','-T','operator','--chat-action=verify-restored-credentials'],timeout=180)
  objects=json.loads(sql("SELECT coalesce(jsonb_agg(jsonb_build_object('key',object_key,'bytes',size_bytes,'sha256',sha256)),'[]'::jsonb)::text FROM pf.asset_object WHERE status='AVAILABLE';"));assert objects
  manifest_objects=root/'reports/objects.json';manifest_objects.write_text(json.dumps(objects));os.chown(manifest_objects,10001,10001);manifest_objects.chmod(0o400)
  probe='sha256:3255f74d916da5faae7762e66d5d7d3fba657383b1ca590c16494ee18e5cc14b'
  w.run(['docker','run','--rm','--network','agentplatform-r3-restore-control','--user','10001:10001','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','64m','--memory-swap','64m','--pids-limit','32',
   '--mount','type=bind,source='+str(manifest_objects)+',target=/run/recovery/objects.json,readonly','--mount','type=bind,source='+str(root/'r3-secrets/file-runner-access')+',target=/run/recovery/access,readonly','--mount','type=bind,source='+str(root/'r3-secrets/file-runner-secret')+',target=/run/recovery/secret,readonly','--mount','type=bind,source='+str(folder/'r3-restore-objects.py')+',target=/probe/restore.py,readonly','--entrypoint','python',probe,'/probe/restore.py'],timeout=180)
  w.run(app_base+['--profile','application','up','-d','--wait','--wait-timeout','240','api','gateway'],timeout=300)
  ready=w.run(['curl','--noproxy','*','-fsS','--max-time','15','-H','Host: agentplatform.lailihao.cloud','http://127.0.0.1:18094/api/v1/readiness']);assert json.loads(ready)=={'status':'READY'}
  assert not w.run(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-r3-restore-app','--filter','label=com.docker.compose.service=worker'])
  assert w.run(['findmnt','-n','-o','UUID','--target',str(ROOT/'workspaces')])==proof['uuid'];w.capacity()
  record={'status':'PASS','root':str(root),'sourceUuidUnchanged':True,'restoredUuid':uuid,'migration':14,'runtimeBaseline':1,'databaseFingerprints':'MATCH','credentialDecryption':'PASS','immutableObjects':len(objects),'workspaceHashes':'MATCH','byteAndInodeQuotaDenial':'PASS','missingMountRejected':True,'readiness':'READY','workerStarted':False,'supplierCalls':0,'restoredXfsLogicalBytes':size,'sparseRestoreCopy':True}
  success=True
 finally:
  failures=[]
  for base in [app_base,env_base]:
   if base:
    try:w.run(base+['--profile','*','stop'],timeout=240)
    except BaseException:failures.append('restore-container-stop')
  if mounted:
   try:w.run(['umount',str(root/'workspaces')]);mounted=False
   except BaseException:failures.append('restore-unmount')
  if loop and not mounted:
   try:
    backing=Path('/sys/block/'+Path(loop).name+'/loop/backing_file');assert Path('/'+backing.read_text().strip().lstrip('/')).resolve()==(root/'quota/workspaces.xfs').resolve()
    w.run(['losetup','-d',loop])
   except BaseException:failures.append('restore-loop-detach')
  if failures:(root/'reports/cleanup-failure.json').write_text(json.dumps(failures));raise RuntimeError('Restore cleanup requires attention')
 if success:
  record['temporaryResourcesStopped']=True;w.receipt('restore',record);print(json.dumps(record))
if __name__=='__main__':main()
