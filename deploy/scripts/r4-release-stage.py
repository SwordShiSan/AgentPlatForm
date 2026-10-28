"""Prepare R4 from the actual retained R3 graph. No stop, migration or activation."""
from pathlib import Path
import argparse,copy,hashlib,json,os,re,subprocess,time
ROOT=Path('/srv/agentplatform')
def read(args):return subprocess.check_output(args,text=True).strip()
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('folder',type=Path);p.add_argument('--candidate',type=Path,required=True);p.add_argument('--native-result',type=Path,required=True);args=p.parse_args();os.umask(0o077)
 folder=args.folder;candidate=json.loads(args.candidate.read_text());acceptance=json.loads(args.native_result.read_text())
 assert re.fullmatch('/srv/agentplatform/operations/r4-release-[a-f0-9]{12}-[0-9]{8}',str(folder)) and not folder.exists()
 assert acceptance['status']=='PASS' and acceptance['source']==candidate['source']
 ids=read(['docker','ps','-q','--no-trunc']).split();rows=json.loads(read(['docker','inspect',*ids]))
 rows=[r for r in rows if r['Config'].get('Labels',{}).get('com.docker.compose.project') in ['agentplatform-env','agentplatform-r3-app']]
 by={r['Config']['Labels']['com.docker.compose.service']:r for r in rows};assert len(by)==11 and all(r['State']['Running'] and not r['State']['OOMKilled'] for r in rows)
 original=Path(by['api']['Config']['Labels']['com.docker.compose.project.config_files']);assert original.is_file()
 config=copy.deepcopy(json.loads(original.read_text()));assert config['name']=='agentplatform-r3-app'
 mount=json.loads(read(['findmnt','-J','-o','TARGET,SOURCE,FSTYPE,OPTIONS,UUID','--target',str(ROOT/'workspaces')]))['filesystems'][0]
 assert mount['target']==str(ROOT/'workspaces') and mount['fstype']=='xfs' and 'prjquota' in mount['options'] and mount['uuid']=='fe8f72e3-4958-4048-a091-b1933d4195da'
 loop=mount['source'];assert re.fullmatch('/dev/loop[0-9]+',loop)
 folder.mkdir(mode=0o700);config['name']='agentplatform-r4-app'
 runtime=json.loads(Path('/etc/docker/daemon.json').read_text())['runtimes']['runsc'];digest=sha(Path(runtime['path']))
 assert digest=='210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a' and runtime['runtimeArgs']==['--platform=systrap']
 attestation=folder/'runtime-attestation.json';attestation.write_text(json.dumps({**runtime,'sha256':digest,'verifiedAt':int(time.time())}));attestation.chmod(0o444)
 nativePlan=json.loads((args.native_result.parent.parent/'operation.json').read_text());sandbox=nativePlan['sandboxImage'];quota=nativePlan['quotaImage'];file=candidate['images']['file-helper']['configId']
 policy='environment-v3|'+sandbox+'|'+quota+'|'+str(ROOT/'workspaces')+'|'+mount['uuid']+'|'+loop+'|'+digest+'|systrap|uid10001|ro|net-none|caps-none|devices-none|memory512m-swap512m|cpu1|pids128|nproc32|tmpfs64m|guest-no-file-bridge|trusted-file-helper:runc|uid10001|ro|net-none|caps-none|devices-none|memory64m-swap64m|cpu1|pids32|tmpfs8m|bridge-v3-jsonl|startup-canary-v1'
 filePolicy='file-stream-policy-v1|'+file+'|'+quota+'|'+str(ROOT/'workspaces')+'|'+mount['uuid']+'|'+loop+'|uid10001|runc-trusted-file-only|ro|net-none|caps-none|no-devices|no-socket|memory64m-swap64m|cpu1|pids32|tmpfs8m|raw64k|header64k|nfc|nofollow|sha256|single-work-bind|scan-v1-100entries-28k|create-noreplace|require-loop-device|file-stream-v2-sha256-trailer32'
 caps={'api':480,'worker':336,'runner':336,'gateway':32};heaps={'api':144,'worker':128,'runner':96}
 for name,service in config['services'].items():
  image=candidate['images']['gateway' if name=='gateway' else 'api'];service['image']=image['configId']
  actual=json.loads(read(['docker','image','inspect',service['image']]))[0];assert actual['Id']==image['configId'] and actual['Config']['Labels']['org.opencontainers.image.revision']==image['source']
  if name in caps:service['mem_limit']=caps[name]*1024**2;service['memswap_limit']=caps[name]*1024**2
  if name in heaps:service['entrypoint']=['java','-XX:+UseSerialGC','-Xms32m','-Xmx'+str(heaps[name])+'m','-Xss512k','-XX:MaxDirectMemorySize=24m','-XX:ReservedCodeCacheSize=32m','-jar','/app/platform.jar']
  check=service.get('healthcheck',{});test=check.get('test',[])
  if test[:2]==['CMD','java']:
   if '-XX:+UseSerialGC' not in test:test[2:2]=['-XX:+UseSerialGC','-Xms8m','-Xmx16m','-Xss256k','-XX:ReservedCodeCacheSize=8m','-XX:MaxMetaspaceSize=32m']
   check.update(interval='20s',timeout='6s',start_period='40s',retries=6)
  env=service.get('environment',{})
  if name in ['api','worker','runner']:
   env.update(PLATFORM_WORKSPACE_TOOLS_ENABLED='true',PLATFORM_WORKSPACE_ENVIRONMENT_IMAGE_ID=sandbox,PLATFORM_WORKSPACE_FILES_HELPER_IMAGE=file,PLATFORM_WORKSPACE_ENVIRONMENT_POLICY_HASH=hashlib.sha256(policy.encode()).hexdigest(),PLATFORM_WORKSPACE_FILES_POLICY_HASH=hashlib.sha256(filePolicy.encode()).hexdigest(),SERVER_TOMCAT_THREADS_MAX='12',SERVER_TOMCAT_THREADS_MIN_SPARE='2')
   if name in ['api','worker']:env.update(PLATFORM_CHAT_PLAN_ENABLED='true',PLATFORM_CHAT_PERMISSIONS_ENABLED='true')
  if name=='runner':
   assert service['labels']['ap.role']=='runner';env['PLATFORM_WORKSPACE_RUNNER_QUOTA_DEVICE']=loop
   for v in service['volumes']:
    if v.get('target')=='/run/fixture/runtime.json':v['source']=str(attestation)
  if name=='gateway':assert not service.get('volumes'),'Production gateway uses the accepted immutable configuration'
  assert not env.get('PLATFORM_CHAT_FIXTURE_PROVIDER','false')=='true' and 'fixture:' not in env.get('PLATFORM_CHAT_PROVIDER_BASE','')
  service['restart']='no'
 runner=config['services']['runner'];source=next(s['source'] for s in runner['secrets'] if s.get('target','').lstrip('/') in ['run/secrets/file_s3_access','file_s3_access']);access=Path(config['secrets'][source]['file']).read_text().strip()
 s3=json.loads((ROOT/'config/s3.json').read_text());matches=[i for i in s3['identities'] if any(c.get('accessKey')==access for c in i.get('credentials',[]))];assert len(matches)==1
 actions=matches[0]['actions'];assert 'Read:ap-assets' in actions
 if 'Write:ap-assets' not in actions:actions.append('Write:ap-assets')
 (folder/'s3.candidate.json').write_text(json.dumps(s3,indent=2));(folder/'compose.json').write_text(json.dumps(config,indent=2));(folder/'source-containers.private.json').write_text(json.dumps(rows,indent=2));(folder/'candidate.json').write_text(json.dumps(candidate,indent=2))
 capsum=sum(caps.values())+sum(r['HostConfig']['Memory']//1024**2 for n,r in by.items() if n not in caps)+512+128+64;assert capsum==2880
 record={'status':'STAGED_NOT_RUNNING','source':candidate['source'],'originalCompose':str(original),'originalComposeSha256':sha(original),'composeSha256':sha(folder/'compose.json'),'productionUuid':mount['uuid'],'loop':loop,'resourceBudgetMiB':capsum,'nativeEvidenceSha256':sha(args.native_result),'supplierCalls':0,'images':{k:{n:v[n] for n in ['source','configId','platform']} for k,v in candidate['images'].items()}}
 (folder/'stage.json').write_text(json.dumps(record,indent=2));print(json.dumps(record))
if __name__=='__main__':main()
