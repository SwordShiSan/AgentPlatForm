"""Stage a corrected immutable R52 candidate only after the exact R4 has been restored."""
from pathlib import Path
import argparse,importlib.util,json
ROOT=Path(__file__).resolve().parents[2]
def main():
 p=argparse.ArgumentParser();p.add_argument('--stage',choices=['r52','r53'],default='r52');args=p.parse_args()
 s=importlib.util.spec_from_file_location('fixture',ROOT/'deploy/scripts/files-native-fixture.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.configure(args.stage);data=m.DATA
 pointer=json.loads((data/'candidate-pointer.json').read_text());candidate=json.loads((Path(pointer['folder'])/'result.json').read_text());assert m.sha(Path(candidate['archive']))==candidate['archiveSha256']
 plan=json.loads((data/'native-plan.json').read_text());cloud=plan['root'];assert cloud.startswith('/srv/agentplatform/acceptance/r44-'+args.stage+'-')
 m.remote('from pathlib import Path\nimport json\nr=Path('+repr(cloud)+');assert json.loads((r/"reports/restored.json").read_text())["status"]=="PASS"\nprint("EXACT_R4_RESTORED_BEFORE_UPDATE")\n')
 image=candidate['images']['gateway'];gateway={'source':candidate['source'],'configId':image['configId'],'archive':candidate['archive'],'archiveSha256':candidate['archiveSha256'],'candidate':candidate}
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
 assert actual['Id']==row['configId'] and actual['Config']['Labels']['org.opencontainers.image.revision']==candidate['source']
for name,row in c['services'].items():
 if name in ['api','worker','runner','operator']:row['image']=candidate['images']['api']['configId']
 if name=='gateway':row['image']=candidate['images']['gateway']['configId']
 env=row.get('environment',{})
 if 'PLATFORM_WORKSPACE_FILES_HELPER_IMAGE' in env:env['PLATFORM_WORKSPACE_FILES_HELPER_IMAGE']=candidate['images']['file-helper']['configId']
 if 'preview-renderer' in candidate['images'] and 'PLATFORM_PREVIEW_IMAGE_ID' in env:env.update(PLATFORM_PREVIEW_IMAGE_ID=candidate['images']['preview-renderer']['configId'],PLATFORM_PREVIEW_POLICY_HASH=__PREVIEW_HASH_VALUE__)
assert shutil.disk_usage('/').free>=5*1024**3
(r/'compose.json').write_text(json.dumps(c,indent=2));(r/'operation.json').write_text(json.dumps(plan,indent=2));print('UPDATED_SAME_FIXTURE_NOT_STARTED')
'''.replace('ROOT',repr(cloud)).replace('CANDIDATE',repr(candidate)).replace('PLAN',repr(plan)).replace('PROJECT',repr(plan['project'])).replace('__PREVIEW_HASH_VALUE__',repr(m.preview_policy_hash(candidate['images']['preview-renderer']['configId']) if 'preview-renderer' in candidate['images'] else ''))
 compile(code,'scoped-candidate-update','exec')
 print(m.remote(code,timeout=180));(data/'native-plan.json').write_text(json.dumps(plan,indent=2));(data/'gateway.json').write_text(json.dumps(gateway,indent=2))
 local=json.loads((data/'native/compose.json').read_text())
 for name,row in local['services'].items():
  if name in ['api','worker','runner','operator']:row['image']=candidate['images']['api']['configId']
  if name=='gateway':row['image']=candidate['images']['gateway']['configId']
  if 'PLATFORM_WORKSPACE_FILES_HELPER_IMAGE' in row.get('environment',{}):row['environment']['PLATFORM_WORKSPACE_FILES_HELPER_IMAGE']=candidate['images']['file-helper']['configId']
  if 'preview-renderer' in candidate['images'] and 'PLATFORM_PREVIEW_IMAGE_ID' in row.get('environment',{}):row['environment'].update(PLATFORM_PREVIEW_IMAGE_ID=candidate['images']['preview-renderer']['configId'],PLATFORM_PREVIEW_POLICY_HASH=m.preview_policy_hash(candidate['images']['preview-renderer']['configId']))
 (data/'native/compose.json').write_text(json.dumps(local,indent=2))
if __name__=='__main__':main()
