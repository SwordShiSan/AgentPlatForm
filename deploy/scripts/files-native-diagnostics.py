"""Read only the current file acceptance graph and bounded startup causes; no restart."""
from pathlib import Path
import argparse, importlib.util, json
ROOT=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser();p.add_argument('--stage',choices=['r51','r52','r53','r54'],required=True);a=p.parse_args()
    spec=importlib.util.spec_from_file_location('native',ROOT/'deploy/scripts/files-native-fixture.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);m.configure(a.stage)
    plan=json.loads((m.DATA/'native-plan.json').read_text());root=plan['root']
    assert root.startswith('/srv/agentplatform/acceptance/r44-'+a.stage+'-')
    code='''from pathlib import Path
import json,subprocess,time
root=Path(ROOT);c=json.loads((root/'compose.json').read_text())
ids=subprocess.check_output(['docker','compose','-p',c['name'],'-f',str(root/'compose.json'),'ps','-a','-q'],text=True).split()
result=[]
for row in json.loads(subprocess.check_output(['docker','inspect',*ids])) if ids else []:
 service=row['Config']['Labels']['com.docker.compose.service'];state=row['State']
 item={'service':service,'id':row['Id'],'image':row['Image'],'state':state['Status'],'exitCode':state['ExitCode'],'oom':state['OOMKilled'],'health':state.get('Health',{}).get('Status')}
 if service in ['api','worker','runner']:
  logs=subprocess.run(['docker','logs','--since',state['StartedAt'],'--tail','160',row['Id']],capture_output=True,text=True)
  lines=(logs.stdout+logs.stderr).splitlines();item['started']=any('Started PlatformApplication' in line for line in lines)
  item['causes']=[line.strip()[:220] for line in lines if line.startswith('Caused by:')][-3:]
 result.append(item)
facts=None
postgres=next((item for item in result if item['service']=='postgres' and item['state']=='running'),None)
if postgres:
 query="SELECT json_build_object('previews',(SELECT coalesce(json_agg(json_build_object('id',id,'state',state,'errorCode',error_code)),'[]'::json) FROM pf.preview_job),'extractions',(SELECT coalesce(json_agg(json_build_object('id',id,'status',status,'errorCode',error_code)),'[]'::json) FROM pf.document_extraction),'heavy',(SELECT kind FROM pf.heavy_runtime_slot WHERE id=1),'transferBusy',(SELECT owner_id IS NOT NULL FROM pf.file_transfer_slot WHERE id=1));"
 response=subprocess.run(['docker','exec',postgres['id'],'psql','-XqAt','-U','postgres','-d','file_runtime','-c',query],capture_output=True,text=True)
 if response.returncode==0:facts=json.loads(response.stdout)
print(json.dumps({'time':time.time(),'services':result,'assetFacts':facts}))
'''.replace('ROOT',repr(root))
    data=json.loads(m.remote(code));(m.DATA/'native-diagnostics.json').write_text(json.dumps(data,indent=2),encoding='utf-8');print(json.dumps(data))

if __name__=='__main__':main()
