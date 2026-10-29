"""Prepare an independent R5.1 Linux fixture; production pause is a separate action."""
from pathlib import Path
import argparse,base64,copy,gzip,hashlib,json,os,re,secrets,subprocess,tarfile,time,uuid
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
ROOT=Path(__file__).resolve().parents[2]; DATA=ROOT/'.cache/r51-local'; PROJECT='agentplatform-r51-local'
STAGE='r51'
def configure(stage):
 global DATA,PROJECT,STAGE
 assert stage in ['r51','r52','r53'];STAGE=stage;DATA=ROOT/('.cache/'+stage+'-local');PROJECT='agentplatform-'+stage+'-local'
SSH=['-F','NUL','-i','D:/java/Dev/腾讯云服务器/lailihao_shanghai.pem','-o','BatchMode=yes','-o','ConnectTimeout=10']
def local(command,timeout=180):
 p=subprocess.run([str(x) for x in command],cwd=ROOT,capture_output=True,timeout=timeout)
 if p.returncode:
  DATA.mkdir(parents=True,exist_ok=True);path=DATA/('command-failure-'+str(time.time_ns())+'.private.log');path.write_bytes(p.stdout+p.stderr);(DATA/'last-command-failure.json').write_text(json.dumps({'path':str(path),'exitCode':p.returncode}));raise RuntimeError('Inspect protected command failure receipt before retrying')
 return p.stdout.decode('utf-8',errors='replace').strip()
def remote(code,timeout=90):
 p=subprocess.run(['ssh',*SSH,'root@124.222.55.140','python3 -'],input=code,text=True,encoding='utf-8',capture_output=True,timeout=timeout)
 if p.returncode:
  path=DATA/('remote-failure-'+str(time.time_ns())+'.private.log');path.write_text(p.stdout+p.stderr,encoding='utf-8');(DATA/'last-remote-failure.json').write_text(json.dumps({'path':str(path),'exitCode':p.returncode}));raise RuntimeError('Inspect protected remote failure receipt; do not resubmit unknown writes')
 return p.stdout.strip()
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def preview_policy_hash(image,bundle='preview-bundle-v1'):
 runsc='210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a'
 material='preview-runtime-v1|'+image+'|'+bundle+'|'+runsc+'|systrap|uid10001|net-none|ro|caps-none|no-devices|one-readonly-input|memory512m-swap512m|cpu1|pids128|tmpfs256m|timeout120s|output100m|pages200|image40mp|zip500m|source-or-fixed-output|office26.2.6|jod5.1.1|pdfbox3.0.8|jsoup1.23.2|twelvemonkeys3.13.1'
 return hashlib.sha256(material.encode()).hexdigest()
def build():
 if STAGE in ['r52','r53']:
  candidate=json.loads((DATA/'candidate-pointer.json').read_text());record=json.loads((Path(candidate['folder'])/'result.json').read_text())
  assert record['status']=='BUILT_EXPORTED_NOT_ACCEPTED'
  gateway=record['images']['gateway'];image={'source':record['source'],'configId':gateway['configId'],'archive':record['archive'],'archiveSha256':record['archiveSha256'],'candidate':record}
  (DATA/'gateway.json').write_text(json.dumps(image,indent=2));print(STAGE.upper()+'_CANDIDATE_REUSED_SAME_EXPORT');return
 source=local(['git','rev-parse','HEAD']);env=dict(os.environ,**{k:'true' for k in ['VITE_CHAT_ENABLED','VITE_PLAN_ENABLED','VITE_PERMISSIONS_ENABLED','VITE_WORKSPACE_ENABLED','VITE_WORKSPACE_FILES_ENABLED','VITE_WORKSPACE_TOOLS_ENABLED']})
 p=subprocess.run([str(ROOT/'.tools/node-v22.23.3-win-x64/node.exe'),str(ROOT/'frontend/node_modules/vite/bin/vite.js'),'build'],cwd=ROOT/'frontend',env=env,capture_output=True)
 (DATA/'frontend-build.log').write_bytes(p.stdout+p.stderr);assert p.returncode==0
 tag='agentplatform-gateway:r51-'+source[:12]
 local(['docker','--context','desktop-linux','build','--platform','linux/amd64','--pull=false','--network=none','--build-arg','SOURCE_REVISION='+source,'--build-arg','NGINX_CONFIG=deploy/nginx.files-local.conf','-f','deploy/gateway.Dockerfile','-t',tag,'.'])
 image=json.loads(local(['docker','--context','desktop-linux','image','inspect',tag]))[0]
 archive=DATA/'gateway.tar';local(['docker','--context','desktop-linux','save','-o',archive,tag])
 with tarfile.open(archive) as t:
  manifest=json.load(t.extractfile('manifest.json'));assert len(manifest)==1;config=manifest[0]['Config'];config_sha=hashlib.sha256(t.extractfile(config).read()).hexdigest()
  assert 'org.opencontainers.image.revision' in image['Config']['Labels']
 inputs=local(['git','ls-files','--','frontend/src','frontend/package.json','frontend/pnpm-lock.yaml','deploy/gateway.Dockerfile','deploy/nginx.files-local.conf']).splitlines()
 record={'source':source,'localId':image['Id'],'configId':'sha256:'+config_sha,'archive':str(archive),'archiveSha256':sha(archive),'platform':'linux/amd64','manifest':manifest,'inputHashes':{name:sha(ROOT/name) for name in inputs}}
 (DATA/'gateway.json').write_text(json.dumps(record,indent=2));print('R51_GATEWAY_BUILT_AND_EXPORT_VERIFIED')
def prepare():
 assert not (DATA/'native-plan.json').exists(),'Read original native plan/status before preparing another fixture'
 image=json.loads((DATA/'gateway.json').read_text());assert sha(Path(image['archive']))==image['archiveSha256']
 old_pointer=json.loads((ROOT/'.cache/r44-local/linux-fixture-pointer.json').read_text());old=Path(old_pointer['local']);config=json.loads((old/'compose.json').read_text())
 stamp=time.strftime('%Y%m%d%H%M%S',time.gmtime());cloud='/srv/agentplatform/acceptance/r44-'+STAGE+'-'+stamp;private=DATA/'native';private.mkdir();os.umask(0o077)
 actor=local(['whoami']);local(['icacls',private,'/inheritance:r','/grant:r',actor+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F'])
 plan={'root':cloud,'project':PROJECT,'source':image['source'],'gateway':image,'uuid':str(uuid.uuid4()),'local':str(private),'supplierCalls':0,'stage':'PREPARING_NOT_PAUSED','acceptanceStage':STAGE,'schema':{'r51':18,'r52':19,'r53':20}[STAGE],'port':18100+int(STAGE[1:])}
 (DATA/'native-plan.json').write_text(json.dumps(plan,indent=2));(private/'operation.json').write_text(json.dumps(plan,indent=2))
 # Only shape is reused; no historical password/key/database/workspace content is copied.
 oldroot=old_pointer['cloud'];config=json.loads(json.dumps(config).replace(oldroot,cloud).replace(config['name'],PROJECT));config['name']=PROJECT
 config['services'].pop('fixture');config['networks'].pop('provider-egress',None)
 for name,row in config['networks'].items():row['name']=PROJECT+'-'+name;row['ipam']={'config':[{'subnet':'10.235.'+str(2*int(STAGE[1:])-51+(0 if name=='control' else 1))+'.0/24'}]}
 for name,row in config.get('volumes',{}).items():row['name']=PROJECT+'-'+name
 for name,row in config['secrets'].items():
  file=Path(row['file']).name;row['file']=cloud+'/'+file
  if 'private' not in name and 'public' not in name:(private/file).write_bytes(secrets.token_bytes(32) if name.startswith('chat_') else secrets.token_urlsafe(32).encode())
 for name in ['control','runner']:
  key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
  (private/(name+'-private.pem')).write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
  (private/(name+'-public.pem')).write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
 # Resolve current production app/middleware identities without copying production environment/secrets.
 found=json.loads(remote("import json,subprocess\nnames={'api':'agentplatform-r4-app-api-1','postgres':'agentplatform-env-postgres-1','rabbitmq':'agentplatform-env-rabbitmq-1','s3':'agentplatform-env-s3-1','redis':'agentplatform-env-redis-1'}\nprint(json.dumps({k:json.loads(subprocess.check_output(['docker','inspect',n]))[0]['Image'] for k,n in names.items()}))\n"))
 if STAGE in ['r52','r53']:found['api']=image['candidate']['images']['api']['configId']
 caps={'api':480,'worker':336,'runner':336,'operator':256,'gateway':32,'postgres':192,'rabbitmq':256,'master':112,'volume':96,'filer':96,'s3':224,'redis':16}
 for name,row in config['services'].items():
  if name in ['api','worker','runner','operator']:row['image']=found['api']
  elif name in ['master','volume','filer','s3']:row['image']=found['s3']
  elif name in found:row['image']=found[name]
  if name=='gateway':row['image']=image['configId'];row['ports']=[{'target':8080,'published':str(plan['port']),'host_ip':'127.0.0.1','protocol':'tcp'}];row.pop('volumes',None)
  elif name in ['postgres','rabbitmq','s3']:row.pop('ports',None)
  row['mem_limit']=row['memswap_limit']=caps[name]*1024**2
  row['restart']='no';row['networks']={k:v for k,v in row.get('networks',{}).items() if k!='provider-egress'}
  if name in ['api','worker','runner','operator']:
   entry=row['entrypoint'];entry.insert(1,'-XX:+UseSerialGC')
   for i,value in enumerate(entry):
    if value.startswith('-Xmx'):entry[i]='-Xmx'+str({'api':144,'worker':128,'runner':96,'operator':112}[name])+'m'
  env=row.get('environment',{})
  for k in ['PLATFORM_WORKSPACE_MESSAGING_USER','PLATFORM_CHAT_MQ_USER']:
   if k in env:env[k]=STAGE
  if name=='api':env['PLATFORM_IDENTITY_ORIGINS']='http://127.0.0.1:'+str(plan['port'])+',http://localhost:'+str(plan['port'])
  if STAGE in ['r52','r53'] and 'PLATFORM_WORKSPACE_FILES_HELPER_IMAGE' in env:env['PLATFORM_WORKSPACE_FILES_HELPER_IMAGE']=image['candidate']['images']['file-helper']['configId']
  if name=='worker':env['PLATFORM_CHAT_PROVIDER_BASE']='http://127.0.0.1:1';env['PLATFORM_CHAT_FIXTURE_PROVIDER']='true'
 if STAGE=='r53':
  renderer=image['candidate']['images']['preview-renderer']['configId'];bundle='preview-bundle-v1'
  for name in ['api','worker','runner']:
   config['services'][name]['environment'].update(PLATFORM_PREVIEW_ENABLED='true',PLATFORM_PREVIEW_IMAGE_ID=renderer,PLATFORM_PREVIEW_BUNDLE=bundle,PLATFORM_PREVIEW_POLICY_HASH=preview_policy_hash(renderer,bundle))
  runner=config['services']['runner'];runner['environment'].update(PLATFORM_PREVIEW_RUNNER_STAGING_ROOT=cloud+'/previews',PLATFORM_PREVIEW_RUNNER_RUNTIME_ATTESTATION=cloud+'/runtime-attestation.json')
  runner['volumes'] += [{'type':'bind','source':cloud+'/previews','target':cloud+'/previews'}, {'type':'bind','source':cloud+'/runtime-attestation.json','target':cloud+'/runtime-attestation.json','read_only':True}]
  (private/'previews').mkdir(mode=0o700)
 # Fresh MQ account, same tested topology and scoped names.
 definitions=json.loads((old/'rabbitmq-definitions.json').read_text());password=(private/Path(config['secrets']['mq_password']['file']).name).read_bytes();salt=secrets.token_bytes(4)
 for user in definitions['users']:user.update(name=STAGE,password_hash=base64.b64encode(salt+hashlib.sha256(salt+password).digest()).decode())
 for permission in definitions['permissions']:permission['user']=STAGE
 for exchange,queue,route in [('ap.commands.v1','ap.execution.v1','execution.ready'),('ap.workspace.commands.v1','ap.workspace.provision.v1','workspace.provision'),('ap.workspace.environments.v2','ap.workspace.environment.v2','workspace.environment'),('ap.workspace.files.v1','ap.workspace.file.v1','workspace.file'),*([('ap.commands.v1','ap.asset.v1','asset.process')] if STAGE=='r53' else [])]:
  definitions.setdefault('exchanges',[]).append({'name':exchange,'vhost':'/agentplatform','type':'direct','durable':True,'auto_delete':False,'internal':False,'arguments':{}})
  definitions.setdefault('queues',[]).append({'name':queue,'vhost':'/agentplatform','durable':True,'auto_delete':False,'arguments':{}})
  definitions.setdefault('bindings',[]).append({'source':exchange,'vhost':'/agentplatform','destination':queue,'destination_type':'queue','routing_key':route,'arguments':{}})
 for kind,keys in [('exchanges',('vhost','name')),('queues',('vhost','name')),('bindings',('vhost','source','destination','routing_key'))]:
  definitions[kind]=list({tuple(row.get(key) for key in keys):row for row in definitions[kind]}.values())
 (private/'rabbitmq-definitions.json').write_text(json.dumps(definitions));(private/'rabbitmq.conf').write_text('management.load_definitions = /etc/rabbitmq/definitions.json\nvm_memory_high_watermark.absolute = 192MiB\n')
 identities=[]
 for role in ['api','worker','runner']:
  access=(private/Path(config['secrets']['file_'+role+'_access']['file']).name).read_text();secret=(private/Path(config['secrets']['file_'+role+'_secret']['file']).name).read_text()
  identities.append({'name':'r51-'+role,'credentials':[{'accessKey':access,'secretKey':secret}],'actions':[a+':r33-assets' for a in ['Read','Write','List','Tagging']],'account':{'id':'r51-storage','displayName':'R51 isolated storage'}})
 bootstrap_access=secrets.token_hex(16);bootstrap_secret=secrets.token_hex(32)
 (private/'bootstrap-access').write_text(bootstrap_access);(private/'bootstrap-secret').write_text(bootstrap_secret)
 identities.append({'name':'r51-bootstrap-only','credentials':[{'accessKey':bootstrap_access,'secretKey':bootstrap_secret}],'actions':['Admin:r33-assets','Read:r33-assets'],'account':{'id':'r51-storage','displayName':'R51 isolated storage'}})
 (private/'s3.json').write_text(json.dumps({'identities':identities,'accounts':[{'id':'r51-storage','displayName':'R51 isolated storage'}]}));config['services']['s3']['environment']['WEED_S3_SSE_KEK_PASSPHRASE']=secrets.token_urlsafe(32)
 for name in ['master','volume','filer','quota','workspaces','reports','accounts']:(private/name).mkdir()
 for name in [STAGE+'_admin',STAGE+'_user']:(private/'accounts'/(name+'.password')).write_text(secrets.token_urlsafe(28))
 (private/'accounts/requests.json').write_text(json.dumps({n:str(uuid.uuid4()) for n in [STAGE+'_admin',STAGE+'_user']}))
 for p in (ROOT/'deploy/config').glob('*grants.sql'):(private/p.name).write_bytes(p.read_bytes())
 (private/'compose.json').write_text(json.dumps(config,indent=2))
 for name in ['files-native-window.py','files-native-watchdog.py']:(private/name).write_bytes((ROOT/'deploy/scripts'/name).read_bytes())
 bundle=DATA/'fixture-private.tar'
 with tarfile.open(bundle,'w') as t:
  for p in private.iterdir():t.add(p,arcname=p.name)
 remote('from pathlib import Path\nimport os,shutil\np=Path('+repr(cloud)+');assert not p.exists();assert shutil.disk_usage("/").free>=9*1024**3;os.umask(0o077);p.mkdir(mode=0o700)\n')
 for source,name in [(bundle,'fixture-private.tar'),(Path(image['archive']),'gateway.tar')]:local(['scp',*SSH,source,'root@124.222.55.140:'+cloud+'/'+name],timeout=180)
 result=remote('''from pathlib import Path
import tarfile,hashlib,subprocess,json,os,ipaddress,shutil
p=Path(CLOUD);digest=hashlib.sha256()
with (p/'gateway.tar').open('rb') as stream:
 for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
assert digest.hexdigest()==DIGEST
with tarfile.open(p/'fixture-private.tar') as archive:
 for member in archive.getmembers():assert not member.issym() and not member.islnk() and not Path(member.name).is_absolute() and '..' not in Path(member.name).parts
 archive.extractall(p,filter='data')
c=json.loads((p/'compose.json').read_text());ids=subprocess.check_output(['docker','network','ls','-q'],text=True).split();networks=json.loads(subprocess.check_output(['docker','network','inspect',*ids]))
for network in networks:
 for row in network.get('IPAM',{}).get('Config',[]) or []:
  if row.get('Subnet'):
   assert all(not ipaddress.ip_network(row['Subnet']).overlaps(ipaddress.ip_network(entry['subnet'])) for v in c['networks'].values() for entry in v['ipam']['config'])
subprocess.run(['docker','load','-i',str(p/'gateway.tar')],check=True,capture_output=True)
assert json.loads(subprocess.check_output(['docker','image','inspect',IMAGE]))[0]['Id']==IMAGE
for name in ['master','volume','filer']+(['previews'] if (p/'previews').is_dir() else []):os.chown(p/name,10001,10001);(p/name).chmod(0o700)
for row in c['secrets'].values():Path(row['file']).chmod(0o444)
(p/'s3.json').chmod(0o444);(p/'rabbitmq.conf').chmod(0o444);(p/'rabbitmq-definitions.json').chmod(0o444)
print(json.dumps({'status':'STAGED_NOT_PAUSED','root':CLOUD,'freeBytes':shutil.disk_usage('/').free}))
'''.replace('CLOUD',repr(cloud)).replace('DIGEST',repr(image['archiveSha256'])).replace('IMAGE',repr(image['configId'])),timeout=180)
 print(result)
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['build','prepare']);p.add_argument('--stage',choices=['r51','r52','r53'],default='r51');a=p.parse_args();configure(a.stage);DATA.mkdir(parents=True,exist_ok=True)
 if a.action=='build':build()
 else:prepare()
if __name__=='__main__':main()
