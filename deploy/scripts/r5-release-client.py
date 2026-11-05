"""Stage or query the versioned R5 release; mutations launch one receipted host action.

Only the already imported native-accepted artifacts are used. No credential,
database dump or private container inspection is returned to stdout.
"""
from pathlib import Path
import argparse, hashlib, importlib.util, json, subprocess, time

ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'.cache/r54-local'
spec=importlib.util.spec_from_file_location('native',ROOT/'deploy/scripts/files-native-fixture.py')
native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native);native.configure('r54')


def refresh(pointer):
    folder=pointer['folder'];files={p.name:p for p in (ROOT/'deploy/scripts').glob('r5-*.py')}
    files['sample.docx']=DATA/'preview-fixtures/sample.docx'
    files['platform-switch.py']=ROOT/'deploy/scripts/platform-switch.py'
    updates={name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in files.items()}
    preflight='''from pathlib import Path
import json,subprocess
r=Path(FOLDER);assert not (r/'job-start.json').exists(),'Tooling refresh only before production maintenance'
stage=json.loads((r/'stage.json').read_text());assert stage['maintenanceOnly']
rows=json.loads((r/'source-containers.private.json').read_text())
for old in rows:
 current=json.loads(subprocess.check_output(['docker','inspect',old['Id']]))[0]
 assert current['State']['Running'] and current['Image']==old['Image'],'Exact original R4 must remain running'
print('PRE_MAINTENANCE_TOOLING_REFRESH')
'''.replace('FOLDER',repr(folder))
    native.remote(preflight)
    for name,path in files.items():native.local(['scp',*native.SSH,path,'root@124.222.55.140:'+folder+'/'+name+'.pending'])
    code='''from pathlib import Path
import hashlib,json,shutil,time
r=Path(FOLDER);updates=UPDATES
assert not (r/'job-start.json').exists()
archive=r/'tooling-history'/str(time.time_ns());archive.mkdir(parents=True,mode=0o700)
hashes=json.loads((r/'input-hashes.json').read_text());shutil.copy2(r/'input-hashes.json',archive/'input-hashes.json')
for name,digest in updates.items():
 pending=r/(name+'.pending');assert hashlib.sha256(pending.read_bytes()).hexdigest()==digest
 if name.endswith('.py'):compile(pending.read_text(),name,'exec')
 if (r/name).exists():shutil.copy2(r/name,archive/name)
 pending.replace(r/name);hashes[name]=digest
shutil.copy2(r/'compose.json',archive/'compose.json');shutil.copy2(r/'stage.json',archive/'stage.json')
config=json.loads((r/'compose.json').read_text());operator=config['services']['operator']
operator['mem_limit']=operator['memswap_limit']=320*1024**2
operator['entrypoint']=['java','-XX:+UseSerialGC','-Xms16m','-Xmx128m','-Xss512k','-XX:MaxDirectMemorySize=24m','-XX:ReservedCodeCacheSize=32m','-jar','/app/platform.jar']
(r/'compose.json').write_text(json.dumps(config,indent=2));stage=json.loads((r/'stage.json').read_text());stage['composeSha256']=hashlib.sha256((r/'compose.json').read_bytes()).hexdigest();(r/'stage.json').write_text(json.dumps(stage,indent=2))
(r/'input-hashes.json').write_text(json.dumps(hashes,indent=2));print('R5_TOOLING_REFRESHED_APPLICATION_UNCHANGED')
'''.replace('FOLDER',repr(folder)).replace('UPDATES',repr(updates))
    compile(code,'r5-pre-maintenance-refresh','exec');print(native.remote(code))
    pointer['inputHashes'].update(updates);pointer['toolingSource']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    (DATA/'release-pointer.json').write_text(json.dumps(pointer,indent=2))


def repair_smoke(pointer):
    """Patch only the known pre-mutation account-list assumption, not a running job."""
    folder=pointer['folder'];source=ROOT/'deploy/scripts/r5-production-smoke.py';digest=hashlib.sha256(source.read_bytes()).hexdigest()
    promotion=json.loads((DATA/'promotion.json').read_text());assert promotion['status']=='PASS'
    assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()==promotion['main']
    prefix='from pathlib import Path\nimport json,hashlib,shutil,time\nr=Path('+repr(folder)+')\n'
    guard='''assert not (r/'writes-open-intent.json').exists() and not (r/'smoke.json').exists()
old=json.loads((r/'job-smoke.json').read_text());p=Path('/proc/'+str(old['pid'])+'/stat')
assert not p.exists() or p.read_text().split()[21]!=old['processStart'] or p.read_text().split()[2]=='Z'
assert 'IndexError: list index out of range' in (r/'job-smoke.private.log').read_text()
assert not (r/'smoke-journal.private.json').exists(),'Do not reset a sent mutation or physical-call meter'
'''
    native.remote(prefix+guard+'print("PRE_MUTATION_DRIVER_FAILURE_CONFIRMED")\n')
    native.local(['scp',*native.SSH,source,'root@124.222.55.140:'+folder+'/r5-production-smoke.py.pending'])
    code=prefix+guard+'digest='+repr(digest)+'''
pending=r/'r5-production-smoke.py.pending';assert hashlib.sha256(pending.read_bytes()).hexdigest()==digest;compile(pending.read_text(),pending.name,'exec')
archive=r/'tooling-history'/str(time.time_ns());archive.mkdir(parents=True,mode=0o700)
for name in ['r5-production-smoke.py','job-smoke.json','job-smoke.private.log','input-hashes.json']:shutil.copy2(r/name,archive/name)
pending.replace(r/'r5-production-smoke.py')
for name in ['job-smoke.json','job-smoke.private.log']:(r/name).rename(archive/(name+'.original'))
hashes=json.loads((r/'input-hashes.json').read_text());hashes['r5-production-smoke.py']=digest;(r/'input-hashes.json').write_text(json.dumps(hashes,indent=2))
print('SMOKE_DRIVER_REPAIRED_NO_APPLICATION_RESTART_OR_REQUEST_REPLAY')
'''
    compile(code,'r5-pre-mutation-smoke-repair','exec');print(native.remote(code));pointer['inputHashes']['r5-production-smoke.py']=digest
    (DATA/'release-pointer.json').write_text(json.dumps(pointer,indent=2))


def stage(new_attempt=False):
    pointer=DATA/'release-pointer.json'
    if new_attempt:
        previous=json.loads(pointer.read_text());folder=previous['folder']
        assert folder.startswith('/srv/agentplatform/operations/r5-release-')
        code='''from pathlib import Path
import json,subprocess
r=Path(FOLDER);assert not (r/'writes-open-intent.json').exists()
assert json.loads((r/'failure-start-restoration.json').read_text())['status']=='PASS'
for path in r.glob('job-*.json'):
 row=json.loads(path.read_text());p=Path('/proc/'+str(row['pid'])+'/stat')
 assert not p.exists() or p.read_text().split()[21]!=row['processStart'] or p.read_text().split()[2]=='Z'
for old in json.loads((r/'source-containers.private.json').read_text()):
 now=json.loads(subprocess.check_output(['docker','inspect',old['Id']]))[0]
 assert now['State']['Running'] and now['Image']==old['Image'] and now['HostConfig']['RestartPolicy']==old['HostConfig']['RestartPolicy']
print('FAILED_RELEASE_RECOVERED_EXACT_R4_RETAINED')
'''.replace('FOLDER',repr(folder))
        print(native.remote(code));archive=DATA/'release-attempts'/Path(folder).name;archive.mkdir(parents=True,exist_ok=False)
        (archive/'release-pointer.json').write_bytes(pointer.read_bytes());pointer.unlink()
    assert not pointer.exists(),'Query the retained release, do not prepare a second one'
    candidate=json.loads((Path(json.loads((DATA/'candidate-pointer.json').read_text())['folder'])/'result.json').read_text())
    acceptance=json.loads((DATA/'native-acceptance.json').read_text());assert acceptance['status']=='PASS' and acceptance['source']==candidate['source']
    changed=subprocess.check_output(['git','diff','--name-only',candidate['source'],'HEAD'],cwd=ROOT,text=True).splitlines()
    assert not any('/src/main/' in name or name.startswith('frontend/src/') or name.endswith('/pom.xml') or name in ['deploy/gateway.Dockerfile','deploy/nginx.production.conf','deploy/preview-renderer.Dockerfile','deploy/preview-renderer.lock.json'] for name in changed),'Application artifact changed after native acceptance'
    source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    stamp=time.strftime('%Y%m%d',time.gmtime());folder='/srv/agentplatform/operations/r5-release-'+source[:12]+'-'+stamp
    incoming=folder.replace('/r5-release-','/r5-inputs-')
    code='from pathlib import Path\nimport json\nr=Path('+repr(incoming)+');assert not r.exists();r.mkdir(mode=0o700)\nprint("PRIVATE_RELEASE_INPUTS_CREATED")\n'
    print(native.remote(code))
    files={name:ROOT/'deploy/scripts'/name for name in ['r5-release-stage.py','r5-release-control.py','r5-isolated-restore.py','r5-database-evidence.py','r5-workspace-restore.py','r5-restore-objects.py','workspace-manifest.py']}
    files['r5-release-profile.json']=ROOT/'deploy/r5-release-profile.json'
    files['candidate.json']=Path(json.loads((DATA/'candidate-pointer.json').read_text())['folder'])/'result.json'
    files['native-result.json']=DATA/'native-acceptance.json';files['native-operation.json']=DATA/'native-plan.json'
    files.update({p.name:p for p in (ROOT/'deploy/config').glob('*grants.sql')})
    hashes={name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in files.items()}
    for name,path in files.items():native.local(['scp',*native.SSH,path,'root@124.222.55.140:'+incoming+'/'+name])
    code='''from pathlib import Path
import hashlib,json,shutil,subprocess
incoming=Path(INCOMING);folder=Path(FOLDER);hashes=HASHES
for name,digest in hashes.items():assert hashlib.sha256((incoming/name).read_bytes()).hexdigest()==digest
for path in incoming.glob('*.py'):compile(path.read_text(),path.name,'exec')
result=subprocess.run(['python3',str(incoming/'r5-release-stage.py'),str(folder),'--candidate',str(incoming/'candidate.json'),'--native-result',str(incoming/'native-result.json')],capture_output=True,text=True,timeout=90)
if result.returncode:
 (incoming/'stage-failure.private.log').write_text(result.stdout+result.stderr)
 raise RuntimeError('Release staging rejected before any production stop; inspect private failure')
for name in hashes:
 target=folder/'deploy/config'/name if name.endswith('grants.sql') else folder/name
 target.parent.mkdir(parents=True,mode=0o700,exist_ok=True);shutil.copy2(incoming/name,target)
(folder/'input-hashes.json').write_text(json.dumps(hashes,indent=2))
print((folder/'stage.json').read_text())
'''.replace('INCOMING',repr(incoming)).replace('FOLDER',repr(folder)).replace('HASHES',repr(hashes))
    compile(code,'r5-private-stage','exec');record=json.loads(native.remote(code,timeout=120))
    pointer.write_text(json.dumps({'folder':folder,'incoming':incoming,'configurationSource':source,'candidateSource':candidate['source'],'stage':record,'inputHashes':hashes},indent=2),encoding='utf-8')
    print(json.dumps({'status':record['status'],'folder':folder,'resourceBudgetMiB':record['resourceBudgetMiB'],'productionStill':'R4'}))


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['stage','refresh-scripts','repair-smoke','status','start','smoke','observe','backup-r5','restore','rollback-drill','redeploy','final-smoke','final-observe','open-writes']);parser.add_argument('--new-attempt',action='store_true',help='Archive only a failed fully restored pre-write release pointer');args=parser.parse_args()
    if args.action=='stage':stage(args.new_attempt);return
    assert not args.new_attempt
    pointer=json.loads((DATA/'release-pointer.json').read_text());folder=pointer['folder']
    assert folder.startswith('/srv/agentplatform/operations/r5-release-')
    if args.action=='refresh-scripts':refresh(pointer);return
    if args.action=='repair-smoke':repair_smoke(pointer);return
    prefix='from pathlib import Path\nimport hashlib,json,subprocess,time\nr=Path('+repr(folder)+')\n'
    if args.action=='status':
        code='''reports={}
for path in r.glob('*.json'):
 if path.name in ['stage.json','start.json','activate.json','backup-r4.json','backup-r5.json','restore.json','rollback-drill.json','redeploy.json','observation.json','smoke.json','writes-open.json','final-smoke.json','final-observation.json','watchdog-r4-restored.json']:
  row=json.loads(path.read_text());reports[path.stem]={k:row[k] for k in ['status','migration','resourceBudgetMiB','recordedAt','elapsedSeconds','supplierCalls'] if k in row}
jobs=[]
for path in r.glob('job-*.json'):
 row=json.loads(path.read_text());proc=Path('/proc/'+str(row['pid'])+'/stat');row['alive']=proc.exists() and proc.read_text().split()[21]==row['processStart'] and proc.read_text().split()[2]!='Z';jobs.append(row)
print(json.dumps({'folder':str(r),'reports':reports,'jobs':jobs}))
'''
    else:
        action=args.action
        # Production actions are launched only after exact-source protected promotion evidence exists.
        promotion=json.loads((DATA/'promotion.json').read_text());assert promotion['status']=='PASS'
        if action=='restore':command=['python3',folder+'/r5-isolated-restore.py',folder]
        elif action in ['smoke','final-smoke']:command=['python3',folder+'/r5-production-smoke.py',folder,'--real-model' if action=='smoke' else '--final']
        elif action in ['observe','final-observe']:command=['python3',folder+'/r5-production-observe.py',folder]+(['--final'] if action=='final-observe' else [])
        elif action=='open-writes':command=['python3',folder+'/r5-open-writes.py',folder,'--main',promotion['main']]
        else:command=['python3',folder+'/r5-release-control.py',action,folder]
        code='command='+repr(command)+'\naction='+repr(action)+'''
assert not (r/('job-'+action+'.json')).exists(),'Query the original action identity'
for path in r.glob('job-*.json'):
 old=json.loads(path.read_text());p=Path('/proc/'+str(old['pid'])+'/stat')
 assert not p.exists() or p.read_text().split()[21]!=old['processStart'] or p.read_text().split()[2]=='Z','Another release action is active'
for name,digest in json.loads((r/'input-hashes.json').read_text()).items():
 path=r/'deploy/config'/name if name.endswith('grants.sql') else r/name
 assert hashlib.sha256(path.read_bytes()).hexdigest()==digest,'Staged release inputs drifted'
log=(r/('job-'+action+'.private.log')).open('xb')
process=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
identity=Path('/proc/'+str(process.pid)+'/stat').read_text().split()[21]
row={'status':'LAUNCHED_QUERY_ORIGINAL','action':action,'pid':process.pid,'processStart':identity,'time':time.time()}
(r/('job-'+action+'.json')).write_text(json.dumps(row,indent=2));print(json.dumps(row))
if action=='start':
 watchdog=subprocess.Popen(['python3',str(r/'r5-maintenance-watchdog.py'),str(r)],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
 (r/'watchdog.json').write_text(json.dumps({'pid':watchdog.pid,'deadlineSeconds':2700,'stopsAfterWriteIntent':True}))
'''
    compile(prefix+code,'r5-release-client','exec');print(native.remote(prefix+code))


if __name__=='__main__':main()
