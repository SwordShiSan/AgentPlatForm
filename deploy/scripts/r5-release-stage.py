"""Prepare R5 from the actual retained R4 graph. No stop, migration or activation."""
from pathlib import Path
import argparse,copy,hashlib,json,os,re,subprocess,time
ROOT=Path('/srv/agentplatform')
def read(args):return subprocess.check_output(args,text=True).strip()
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('folder',type=Path);p.add_argument('--candidate',type=Path,required=True);p.add_argument('--native-result',type=Path,required=True);args=p.parse_args();os.umask(0o077)
 folder=args.folder;candidate=json.loads(args.candidate.read_text());acceptance=json.loads(args.native_result.read_text())
 profilePath=Path(__file__).with_name('r5-release-profile.json');profile=json.loads(profilePath.read_text())
 assert profile['migration']==21 and profile['previousMigration']==18 and profile['totalBudgetMiB']==2880
 assert re.fullmatch('/srv/agentplatform/operations/r5-release-[a-f0-9]{12}-[0-9]{8}',str(folder)) and not folder.exists()
 assert acceptance['status']=='PASS' and acceptance['source']==candidate['source']
 ids=read(['docker','ps','-q','--no-trunc']).split();rows=json.loads(read(['docker','inspect',*ids]))
 rows=[r for r in rows if r['Config'].get('Labels',{}).get('com.docker.compose.project') in ['agentplatform-env','agentplatform-r4-app']]
 by={r['Config']['Labels']['com.docker.compose.service']:r for r in rows};assert len(by)==11 and all(r['State']['Running'] and not r['State']['OOMKilled'] for r in rows)
 original=Path(by['api']['Config']['Labels']['com.docker.compose.project.config_files']);assert original.is_file()
 # Reuse only the existing protected production acceptance accounts; never import
 # local fixture accounts, plaintext model keys or local databases.
 accountSource=original.parent/'probe-accounts.private.json';assert accountSource.is_file()
 previousSmoke=original.parent/'smoke.json';assert previousSmoke.is_file() and json.loads(previousSmoke.read_text())['status']=='PASS'
 config=copy.deepcopy(json.loads(original.read_text()));assert config['name']=='agentplatform-r4-app'
 mount=json.loads(read(['findmnt','-J','-o','TARGET,SOURCE,FSTYPE,OPTIONS,UUID','--target',str(ROOT/'workspaces')]))['filesystems'][0]
 assert mount['target']==str(ROOT/'workspaces') and mount['fstype']=='xfs' and 'prjquota' in mount['options'] and mount['uuid']=='fe8f72e3-4958-4048-a091-b1933d4195da'
 loop=mount['source'];assert re.fullmatch('/dev/loop[0-9]+',loop)
 startingMigration=int(read(['docker','exec',by['postgres']['Id'],'psql','-XqAt','-U','ap_admin','-d','agentplatform','-c','SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;']))
 assert startingMigration==18,'R5 upgrades released R4 schema18; rollback requires the matching cold snapshot'
 folder.mkdir(mode=0o700);config['name']='agentplatform-r5-app'
 import shutil
 shutil.copy2(accountSource,folder/'probe-accounts.private.json');(folder/'probe-accounts.private.json').chmod(0o600)
 shutil.copy2(previousSmoke,folder/'previous-smoke.json')
 for part in ['api','gateway','file-helper','preview-renderer']:
  image=candidate['images'][part];actual=json.loads(read(['docker','image','inspect',image['configId']]))[0]
  assert actual['Id']==image['configId'] and actual['Config']['Labels']['org.opencontainers.image.revision']==image['source']
 staging=Path(profile['previewStagingRoot']);assert staging==ROOT/'previews' and not staging.is_symlink()
 staging.mkdir(mode=0o700,exist_ok=True);os.chown(staging,10001,10001);staging.chmod(0o700)
 runtime=json.loads(Path('/etc/docker/daemon.json').read_text())['runtimes']['runsc'];digest=sha(Path(runtime['path']))
 assert digest=='210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a' and runtime['runtimeArgs']==['--platform=systrap']
 attestation=folder/'runtime-attestation.json';attestation.write_text(json.dumps({**runtime,'sha256':digest,'verifiedAt':int(time.time())}));attestation.chmod(0o444)
 nativePlan=json.loads(args.native_result.with_name('native-operation.json').read_text());assert nativePlan['schema']==21 and nativePlan['source']==candidate['source']
 runtimeEnv=config['services']['runner']['environment'];sandbox=runtimeEnv['PLATFORM_WORKSPACE_ENVIRONMENT_IMAGE_ID'];quota=runtimeEnv['PLATFORM_WORKSPACE_RUNNER_HELPER_IMAGE'];file=candidate['images']['file-helper']['configId']
 assert re.fullmatch('sha256:[a-f0-9]{64}',sandbox) and re.fullmatch('sha256:[a-f0-9]{64}',quota)
 policy='environment-v3|'+sandbox+'|'+quota+'|'+str(ROOT/'workspaces')+'|'+mount['uuid']+'|'+loop+'|'+digest+'|systrap|uid10001|ro|net-none|caps-none|devices-none|memory512m-swap512m|cpu1|pids128|nproc32|tmpfs64m|guest-no-file-bridge|trusted-file-helper:runc|uid10001|ro|net-none|caps-none|devices-none|memory64m-swap64m|cpu1|pids32|tmpfs8m|bridge-v3-jsonl|startup-canary-v1'
 filePolicy='file-stream-policy-v1|'+file+'|'+quota+'|'+str(ROOT/'workspaces')+'|'+mount['uuid']+'|'+loop+'|uid10001|runc-trusted-file-only|ro|net-none|caps-none|no-devices|no-socket|memory64m-swap64m|cpu1|pids32|tmpfs8m|raw64k|header64k|nfc|nofollow|sha256|single-work-bind|scan-v1-100entries-28k|create-noreplace|require-loop-device|file-stream-v2-sha256-trailer32'
 caps=profile['applicationMemoryMiB'];heaps=profile['heapMiB']
 for name,service in config['services'].items():
  image=candidate['images']['gateway' if name=='gateway' else 'api'];service['image']=image['configId']
  actual=json.loads(read(['docker','image','inspect',service['image']]))[0];assert actual['Id']==image['configId'] and actual['Config']['Labels']['org.opencontainers.image.revision']==image['source']
  if name in caps:service['mem_limit']=caps[name]*1024**2;service['memswap_limit']=caps[name]*1024**2
  if name in heaps:service['entrypoint']=['java','-XX:+UseSerialGC','-Xms32m','-Xmx'+str(heaps[name])+'m','-Xss512k','-XX:MaxDirectMemorySize=24m','-XX:ReservedCodeCacheSize=32m','-jar','/app/platform.jar']
  if name=='operator':
   service['mem_limit']=service['memswap_limit']=320*1024**2
   service['entrypoint']=['java','-XX:+UseSerialGC','-Xms16m','-Xmx128m','-Xss512k','-XX:MaxDirectMemorySize=24m','-XX:ReservedCodeCacheSize=32m','-jar','/app/platform.jar']
  check=service.get('healthcheck',{});test=check.get('test',[])
  if test[:2]==['CMD','java']:
   if '-XX:+UseSerialGC' not in test:test[2:2]=['-XX:+UseSerialGC','-Xms8m','-Xmx16m','-Xss256k','-XX:ReservedCodeCacheSize=8m','-XX:MaxMetaspaceSize=32m']
   check.update(interval='20s',timeout='6s',start_period='40s',retries=6)
  env=service.get('environment',{})
  if name in ['api','worker','runner']:
   env.update(PLATFORM_WORKSPACE_TOOLS_ENABLED='true',PLATFORM_WORKSPACE_ENVIRONMENT_IMAGE_ID=sandbox,PLATFORM_WORKSPACE_FILES_HELPER_IMAGE=file,PLATFORM_WORKSPACE_ENVIRONMENT_POLICY_HASH=hashlib.sha256(policy.encode()).hexdigest(),PLATFORM_WORKSPACE_FILES_POLICY_HASH=hashlib.sha256(filePolicy.encode()).hexdigest(),SERVER_TOMCAT_THREADS_MAX='12',SERVER_TOMCAT_THREADS_MIN_SPARE='2')
   if name in ['api','worker']:env.update(PLATFORM_CHAT_PLAN_ENABLED='true',PLATFORM_CHAT_PERMISSIONS_ENABLED='true')
   renderer=candidate['images']['preview-renderer']['configId'];bundle='asset-parser-tika3.3.3-v2'
   material='preview-runtime-v1|'+renderer+'|'+bundle+'|'+digest+'|systrap|uid10001|net-none|ro|caps-none|no-devices|one-readonly-input|memory512m-swap512m|cpu1|pids128|tmpfs256m|timeout120s|output100m|pages200|image40mp|zip500m|source-or-fixed-output|office26.2.6|jod5.1.1|pdfbox3.0.8|jsoup1.23.2|twelvemonkeys3.13.1'
   env.update(PLATFORM_ATTACHMENTS_ENABLED='true',PLATFORM_CHAT_CONTEXT_V2_ENABLED='true',PLATFORM_CHAT_CONTEXT_WINDOW_TOKENS='262144',PLATFORM_CHAT_CONTEXT_MAX_OUTPUT_TOKENS='4096',PLATFORM_PREVIEW_ENABLED='true',PLATFORM_PREVIEW_IMAGE_ID=renderer,PLATFORM_PREVIEW_BUNDLE=bundle,PLATFORM_PREVIEW_POLICY_HASH=hashlib.sha256(material.encode()).hexdigest())
  if name=='runner':
   assert service['labels']['ap.role']=='runner';env['PLATFORM_WORKSPACE_RUNNER_QUOTA_DEVICE']=loop
   for v in service['volumes']:
    if v.get('target')=='/run/fixture/runtime.json':v['source']=str(attestation)
   env.update(PLATFORM_PREVIEW_RUNNER_STAGING_ROOT=str(staging),PLATFORM_PREVIEW_RUNNER_RUNTIME_ATTESTATION=str(attestation))
   service['volumes'].extend([{'type':'bind','source':str(staging),'target':str(staging)}, {'type':'bind','source':str(attestation),'target':str(attestation),'read_only':True}])
  if name=='gateway':assert not service.get('volumes'),'Production gateway uses the accepted immutable configuration'
  if name=='gateway':
   # Maintenance is reachable only through the already-authorized SSH tunnel.
   for port in service['ports']:port['host_ip']='127.0.0.1'
  assert not env.get('PLATFORM_CHAT_FIXTURE_PROVIDER','false')=='true' and 'fixture:' not in env.get('PLATFORM_CHAT_PROVIDER_BASE','')
  service['restart']='no'
 runner=config['services']['runner'];source=next(s['source'] for s in runner['secrets'] if s.get('target','').lstrip('/') in ['run/secrets/file_s3_access','file_s3_access']);access=Path(config['secrets'][source]['file']).read_text().strip()
 s3=json.loads((ROOT/'config/s3.json').read_text());matches=[i for i in s3['identities'] if any(c.get('accessKey')==access for c in i.get('credentials',[]))];assert len(matches)==1
 actions=matches[0]['actions'];assert 'Read:ap-assets' in actions
 if 'Write:ap-assets' not in actions:actions.append('Write:ap-assets')
 (folder/'s3.candidate.json').write_text(json.dumps(s3,indent=2));(folder/'compose.json').write_text(json.dumps(config,indent=2));(folder/'source-containers.private.json').write_text(json.dumps(rows,indent=2));(folder/'candidate.json').write_text(json.dumps(candidate,indent=2))
 capsum=sum(caps.values())+sum(r['HostConfig']['Memory']//1024**2 for n,r in by.items() if n not in caps)+512+128+64;assert capsum==2880
 record={'status':'STAGED_NOT_RUNNING','source':candidate['source'],'originalCompose':str(original),'originalComposeSha256':sha(original),'composeSha256':sha(folder/'compose.json'),'productionUuid':mount['uuid'],'loop':loop,'resourceBudgetMiB':capsum,'nativeEvidenceSha256':sha(args.native_result),'supplierCalls':0,'images':{k:{n:v[n] for n in ['source','configId','platform']} for k,v in candidate['images'].items()}}
 record['startingMigration']=startingMigration
 record['profileSha256']=sha(profilePath)
 record.update(targetMigration=21,runtimeBaseline=1,appProject='agentplatform-r5-app',maintenanceOnly=True,realWritesOpened=False,rollbackStrategy='MATCHED_PRE_UPGRADE_COLD_SNAPSHOT',requiredServices=['postgres','rabbitmq','redis','master','volume','filer','s3','runner','api','worker','gateway'])
 (folder/'stage.json').write_text(json.dumps(record,indent=2));print(json.dumps(record))
if __name__=='__main__':main()
