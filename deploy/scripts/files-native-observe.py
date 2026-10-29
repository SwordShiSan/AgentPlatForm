"""One180second functional/resource observation of the final R51 candidate; no model calls."""
from pathlib import Path
import importlib.util,json,subprocess,time,hashlib,sys
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'.cache/r51-local'
def load(name,file):
 s=importlib.util.spec_from_file_location(name,ROOT/'deploy/scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
api=load('file_api_acceptance','files-native-api.py');native=load('native_fixture','files-native-fixture.py')
plan=json.loads((DATA/'native-plan.json').read_text());ids=json.loads((DATA/'native-file-identities.json').read_text())
def sample():
 code='''from pathlib import Path
import importlib.util,json
r=Path(ROOT);s=importlib.util.spec_from_file_location('window',r/'files-native-window.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);row=m.sample();print(json.dumps({'time':row['time'],'MemAvailable':row['MemAvailable'],'SwapFree':row['SwapFree'],'SwapTotal':row['SwapTotal'],'rootFreeBytes':row['rootFreeBytes'],'requiredCount':len(row['required']),'capSum':sum(v['memory'] for v in row['required'])}))
'''.replace('ROOT',repr(plan['root']))
 return json.loads(native.remote(code))
def main():
 assert json.loads((DATA/'native-api-result.json').read_text())['status']=='PASS'
 assert json.loads((DATA/'native-browser-result.json').read_text())['status']=='PASS'
 admission='from pathlib import Path\nimport json,time\nr=Path('+repr(plan['root'])+')\nready=json.loads((r/"reports/ready.json").read_text());remaining=900-(time.time()-ready["time"]);assert remaining>=240,"Observation requires full180seconds plus60seconds closure margin before any write"\nprint(int(remaining))\n'
 remaining=int(native.remote(admission));assert remaining>=240
 client=api.Client();started=time.monotonic();samples=[sample()];count=0;base=None
 name='observe-r51-'+str(time.time_ns())+'.txt'
 while True:
  content=('R51 observation '+str(count)+' 中文😀\r\n').encode('utf-8')
  upload,current,_=api.upload(client,ids['workspaceId'],name,content,base)
  # Actual immutable readback is already checked by upload(); retain only hashes/IDs, never account material.
  base=current;count+=1;samples.append(sample())
  if time.monotonic()-started>=180:break
  time.sleep(3)
 final='''from pathlib import Path
import subprocess,json,time
r=Path(ROOT);pg=subprocess.check_output(['docker','compose','-p','agentplatform-r51-local','-f',str(r/'compose.json'),'ps','-q','postgres'],text=True).strip()
def sql(q):return subprocess.check_output(['docker','exec',pg,'psql','-XqAt','-U','postgres','-d','file_runtime','-c',q],text=True).strip()
for _ in range(20):
 pending=int(sql("SELECT (SELECT count(*) FROM pf.file_operation WHERE status NOT IN('COMMITTED','FAILED'))+(SELECT count(*) FROM pf.file_transfer_slot WHERE owner_id IS NOT NULL)+(SELECT count(*) FROM pf.workspace_file_lease WHERE status<>'FREE');"))
 if pending==0:break
 time.sleep(1)
assert pending==0
assert sql('SELECT count(*) FROM pf.model_call;')=='0'
print(json.dumps({'supplierCalls':0,'pendingFileFacts':pending,'fileVersions':int(sql('SELECT count(*) FROM pf.file_version;'))}))
'''.replace('ROOT',repr(plan['root']))
 result={'status':'PASS','root':plan['root'],'seconds':round(time.monotonic()-started,3),'serialRounds':count,'samples':samples,'lastFileId':base['id'],'lastVersionId':base['versionId'],'lastSha256':base['sha256'],'final':json.loads(native.remote(final)),'scope':'same frozen gateway/backend; serial real create/replace/download and sampled resources; no new gVisor isolation matrix or capacity claim'}
 (DATA/'native-observation.json').write_text(json.dumps(result,indent=2));acceptance={'status':'PASS','root':plan['root'],'api':str(DATA/'native-api-result.json'),'browser':str(DATA/'native-browser-result.json'),'observation':str(DATA/'native-observation.json'),'supplierCalls':0};(DATA/'native-acceptance.json').write_text(json.dumps(acceptance,indent=2))
 subprocess.run([sys.executable,str(ROOT/'deploy/scripts/files-local.py'),'stop','--native','--passed'],check=True)
 print(json.dumps({'status':result['status'],'seconds':result['seconds'],'rounds':count,'minMemAvailableMiB':min(x['MemAvailable'] for x in samples)//1024**2,'supplierCalls':0}))
if __name__=='__main__':main()
