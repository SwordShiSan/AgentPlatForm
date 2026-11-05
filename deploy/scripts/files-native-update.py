"""Stage a corrected immutable R52 candidate only after the exact R4 has been restored."""
from pathlib import Path
import argparse,importlib.util,json,hashlib
ROOT=Path(__file__).resolve().parents[2]

def staged_scripts(m,data):
 plan=json.loads((data/'native-plan.json').read_text());cloud=plan['root']
 assert cloud.startswith('/srv/agentplatform/acceptance/r44-'+plan['acceptanceStage']+'-')
 prefix='from pathlib import Path\nimport json,subprocess,hashlib,shutil,time\nr=Path('+repr(cloud)+')\n'
 preflight="""if (r/'controller.json').exists():
 assert json.loads((r/'reports/restored.json').read_text())['status']=='PASS'
 assert json.loads((r/'reports/window-result.json').read_text())['status']=='FAIL'
 old=json.loads((r/'controller.json').read_text());process=Path('/proc/'+str(old['pid'])+'/stat')
 assert not process.exists() or process.read_text().split()[21]!=old['processStart'],'Original controller still alive'
"""+"assert not subprocess.check_output(['docker','ps','-q','--filter','label=com.docker.compose.project='+"+repr(plan['project'])+"],text=True).strip()\n"
 m.remote(prefix+preflight+'print("STAGED_NOT_STARTED_CONFIRMED")\n')
 updates={}
 for name in ['files-native-window.py','files-native-watchdog.py']:
  source=ROOT/'deploy/scripts'/name;updates[name]=hashlib.sha256(source.read_bytes()).hexdigest()
  m.local(['scp',*m.SSH,source,'root@124.222.55.140:'+cloud+'/'+name+'.pending'])
 plan['configurationSource']=m.local(['git','rev-parse','HEAD'])
 for name,digest in updates.items():plan['fixtureInputHashes']['deploy/scripts/'+name]=digest
 code=prefix+preflight+'updates='+repr(updates)+'\nplan='+repr(plan)+'''\narchive=r/'staged-history'/str(time.time_ns());archive.mkdir(parents=True,mode=0o700)
for name,digest in updates.items():
 pending=r/(name+'.pending');assert hashlib.sha256(pending.read_bytes()).hexdigest()==digest
 compile(pending.read_text(),name,'exec')
 shutil.copy2(r/name,archive/name);pending.replace(r/name)
shutil.copy2(r/'operation.json',archive/'operation.json')
if plan['acceptanceStage']=='r54':
 shutil.copy2(r/'compose.json',archive/'compose.json');config=json.loads((r/'compose.json').read_text())
 config['services']['api']['environment'].update(PLATFORM_IDENTITY_ORIGINS='http://agentplatform.lailihao.cloud,http://124.222.55.140',PLATFORM_IDENTITY_HTTP_DEPLOYMENT='true')
 for service,limit in [('api',512),('worker',320),('runner',320),('rabbitmq',256),('s3',160)]:
  config['services'][service]['mem_limit']=config['services'][service]['memswap_limit']=limit*1024**2
 config['services']['worker']['entrypoint']=['-Xmx160m' if value.startswith('-Xmx') else value for value in config['services']['worker']['entrypoint']]
 shutil.copy2(r/'rabbitmq.conf',archive/'rabbitmq.conf')
 (r/'rabbitmq.conf').write_text('management.load_definitions = /etc/rabbitmq/definitions.json\\nvm_memory_high_watermark.absolute = 192MiB\\n')
 (r/'compose.json').write_text(json.dumps(config,indent=2))
(r/'operation.json').write_text(json.dumps(plan,indent=2))
print('STAGED_CONTROLLERS_UPDATED_NOT_STARTED')
'''
 compile(code,'scoped-staged-controller-update','exec')
 print(m.remote(code))
 for name in updates:(data/'native'/name).write_bytes((ROOT/'deploy/scripts'/name).read_bytes())
 if plan['acceptanceStage']=='r54':
  config=json.loads((data/'native/compose.json').read_text());config['services']['api']['environment'].update(PLATFORM_IDENTITY_ORIGINS='http://agentplatform.lailihao.cloud,http://124.222.55.140',PLATFORM_IDENTITY_HTTP_DEPLOYMENT='true')
  for service,limit in [('api',512),('worker',320),('runner',320),('rabbitmq',256),('s3',160)]:config['services'][service]['mem_limit']=config['services'][service]['memswap_limit']=limit*1024**2
  config['services']['worker']['entrypoint']=['-Xmx160m' if value.startswith('-Xmx') else value for value in config['services']['worker']['entrypoint']]
  (data/'native/compose.json').write_text(json.dumps(config,indent=2))
  (data/'native/rabbitmq.conf').write_text('management.load_definitions = /etc/rabbitmq/definitions.json\nvm_memory_high_watermark.absolute = 192MiB\n')
 for path in [data/'native-plan.json',data/'native/operation.json']:path.write_text(json.dumps(plan,indent=2),encoding='utf-8')
def main():
 p=argparse.ArgumentParser();p.add_argument('--stage',choices=['r52','r53','r54'],default='r52');p.add_argument('--staged-scripts',action='store_true');args=p.parse_args()
 s=importlib.util.spec_from_file_location('fixture',ROOT/'deploy/scripts/files-native-fixture.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.configure(args.stage);data=m.DATA
 if args.staged_scripts:staged_scripts(m,data);return
 pointer=json.loads((data/'candidate-pointer.json').read_text());candidate=json.loads((Path(pointer['folder'])/'result.json').read_text());assert m.sha(Path(candidate['archive']))==candidate['archiveSha256']
 plan=json.loads((data/'native-plan.json').read_text());cloud=plan['root'];assert cloud.startswith('/srv/agentplatform/acceptance/r44-'+args.stage+'-')
 m.remote('from pathlib import Path\nimport json\nr=Path('+repr(cloud)+');assert json.loads((r/"reports/restored.json").read_text())["status"]=="PASS"\nprint("EXACT_R4_RESTORED_BEFORE_UPDATE")\n')
 image=candidate['images']['gateway'];gateway={'source':image['source'],'applicationSource':candidate['source'],'configId':image['configId'],'archive':candidate['archive'],'archiveSha256':candidate['archiveSha256'],'candidate':candidate}
 m.local(['scp',*m.SSH,candidate['archive'],'root@124.222.55.140:'+cloud+'/candidate-update.tar.gz'],timeout=600)
 plan['previousCandidates']=plan.get('previousCandidates',[])+[{'source':plan['source'],'gatewayConfigId':plan['gateway']['configId']}]
 plan.update(source=candidate['source'],gateway=gateway,configurationSource=m.local(['git','rev-parse','HEAD']))
 code='''from pathlib import Path
import hashlib,json,subprocess,shutil
r=Path(ROOT);candidate=CANDIDATE;plan=PLAN;archive=r/'candidate-update.tar.gz'
assert hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()==candidate['archiveSha256']
c=json.loads((r/'compose.json').read_text());assert c['name']==PROJECT
ids=subprocess.check_output(['docker','compose','-p',c['name'],'-f',str(r/'compose.json'),'ps','-a','-q'],text=True).split()
assert all(not v['State']['Running'] for v in json.loads(subprocess.check_output(['docker','inspect',*ids])))
subprocess.run(['docker','load','-i',str(archive)],check=True,capture_output=True)
for part,row in candidate['images'].items():
 actual=json.loads(subprocess.check_output(['docker','image','inspect',row['configId']]))[0]
 assert actual['Id']==row['configId'] and actual['Config']['Labels']['org.opencontainers.image.revision']==row['source']
for name,row in c['services'].items():
 if name in ['api','worker','runner','operator']:row['image']=candidate['images']['api']['configId']
 if name=='gateway':row['image']=candidate['images']['gateway']['configId']
 env=row.get('environment',{})
 if 'PLATFORM_WORKSPACE_FILES_HELPER_IMAGE' in env:env['PLATFORM_WORKSPACE_FILES_HELPER_IMAGE']=candidate['images']['file-helper']['configId']
 if 'preview-renderer' in candidate['images'] and 'PLATFORM_PREVIEW_IMAGE_ID' in env:env.update(PLATFORM_PREVIEW_IMAGE_ID=candidate['images']['preview-renderer']['configId'],PLATFORM_PREVIEW_POLICY_HASH=__PREVIEW_HASH_VALUE__)
assert shutil.disk_usage('/').free>=5*1024**3
(r/'compose.json').write_text(json.dumps(c,indent=2));(r/'operation.json').write_text(json.dumps(plan,indent=2));print('UPDATED_SAME_FIXTURE_NOT_STARTED')
'''.replace('ROOT',repr(cloud)).replace('CANDIDATE',repr(candidate)).replace('PLAN',repr(plan)).replace('PROJECT',repr(plan['project'])).replace('__PREVIEW_HASH_VALUE__',repr(m.preview_policy_hash(candidate['images']['preview-renderer']['configId'],'asset-parser-tika3.3.3-v2' if args.stage=='r54' else 'preview-bundle-v1') if 'preview-renderer' in candidate['images'] else ''))
 compile(code,'scoped-candidate-update','exec')
 print(m.remote(code,timeout=180));(data/'native-plan.json').write_text(json.dumps(plan,indent=2));(data/'gateway.json').write_text(json.dumps(gateway,indent=2))
 local=json.loads((data/'native/compose.json').read_text())
 for name,row in local['services'].items():
  if name in ['api','worker','runner','operator']:row['image']=candidate['images']['api']['configId']
  if name=='gateway':row['image']=candidate['images']['gateway']['configId']
  if 'PLATFORM_WORKSPACE_FILES_HELPER_IMAGE' in row.get('environment',{}):row['environment']['PLATFORM_WORKSPACE_FILES_HELPER_IMAGE']=candidate['images']['file-helper']['configId']
  if 'preview-renderer' in candidate['images'] and 'PLATFORM_PREVIEW_IMAGE_ID' in row.get('environment',{}):row['environment'].update(PLATFORM_PREVIEW_IMAGE_ID=candidate['images']['preview-renderer']['configId'],PLATFORM_PREVIEW_POLICY_HASH=m.preview_policy_hash(candidate['images']['preview-renderer']['configId'],'asset-parser-tika3.3.3-v2' if args.stage=='r54' else 'preview-bundle-v1'))
 (data/'native/compose.json').write_text(json.dumps(local,indent=2))
if __name__=='__main__':main()
