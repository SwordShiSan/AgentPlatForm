"""Rebalance the idle R54 test services only, without restarting API/worker or production."""
from pathlib import Path
import argparse, importlib.util, json
ROOT=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--stage',choices=['r54'],required=True);p.add_argument('--apply',action='store_true');a=p.parse_args()
    spec=importlib.util.spec_from_file_location('fixture',ROOT/'deploy/scripts/files-native-fixture.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);m.configure(a.stage)
    plan=json.loads((m.DATA/'native-plan.json').read_text());root=plan['root'];assert root.startswith('/srv/agentplatform/acceptance/r44-r54-')
    code='''from pathlib import Path
import hashlib,json,subprocess,time
root=Path(ROOT);apply=APPLY;project='agentplatform-r54-local'
def run(args):return subprocess.check_output(args,text=True).strip()
base=['docker','compose','-p',project,'-f',str(root/'compose.json')]
ids=run(base+['ps','-a','-q']).split();rows=json.loads(run(['docker','inspect',*ids]));by={r['Config']['Labels']['com.docker.compose.service']:r for r in rows}
assert len(by)==12 and all(by[n]['State']['Running'] for n in by if n!='runner')
pg=by['postgres']['Id']
def sql(query):return run(['docker','exec',pg,'psql','-XqAt','-U','postgres','-d','file_runtime','-c',query])
assert sql("SELECT (SELECT count(*) FROM pf.task WHERE status IN('QUEUED','RUNNING','WAITING_INPUT'))+(SELECT count(*) FROM pf.heavy_runtime_slot WHERE kind IS NOT NULL)+(SELECT count(*) FROM pf.file_transfer_slot WHERE owner_id IS NOT NULL)+(SELECT count(*) FROM pf.preview_job WHERE state IN('QUEUED','RUNNING','BLOCKED'))+(SELECT count(*) FROM pf.document_extraction WHERE status IN('QUEUED','RUNNING','BLOCKED'));")=='0','Active or unresolved work prevents a resource change'
c=json.loads((root/'compose.json').read_text());caps={'api':512,'worker':320,'runner':320,'rabbitmq':256,'s3':160}
for name,limit in caps.items():c['services'][name]['mem_limit']=c['services'][name]['memswap_limit']=limit*1024**2
resident=sum(s['mem_limit'] for n,s in c['services'].items() if n!='operator');assert resident+704*1024**2==2880*1024**2
if not apply:print(json.dumps({'status':'DRY_RUN','residentMiB':resident//1024**2,'fullMiB':2880,'caps':caps}));raise SystemExit(0)
before={n:{'id':by[n]['Id'],'startedAt':by[n]['State']['StartedAt'],'image':by[n]['Image']} for n in ['api','worker','gateway']}
run(['docker','exec',by['rabbitmq']['Id'],'rabbitmqctl','set_vm_memory_high_watermark','absolute','201326592'])
run(['docker','update','--memory','256m','--memory-swap','256m',by['rabbitmq']['Id']])
run(['docker','update','--memory','160m','--memory-swap','160m',by['s3']['Id']])
run(['docker','update','--memory','320m','--memory-swap','320m',by['runner']['Id']])
if not by['runner']['State']['Running']:run(['docker','start',by['runner']['Id']])
for _ in range(60):
 runner=json.loads(run(['docker','inspect',by['runner']['Id']]))[0]
 assert runner['State']['Running'] and not runner['State']['OOMKilled']
 if 'Started PlatformApplication' in run(['docker','logs','--since',runner['State']['StartedAt'],'--tail','60',runner['Id']]):break
 time.sleep(1)
else:raise RuntimeError('Original runner did not become ready')
for name,expected in before.items():
 current=json.loads(run(['docker','inspect',expected['id']]))[0];assert current['State']['Running'] and current['Image']==expected['image'] and current['State']['StartedAt']==expected['startedAt']
(root/'compose.json').write_text(json.dumps(c,indent=2))
(root/'rabbitmq.conf').write_text('management.load_definitions = /etc/rabbitmq/definitions.json\\nvm_memory_high_watermark.absolute = 192MiB\\n')
record={'status':'PASS','scope':'idle test resource rebalance only','fullMiB':2880,'residentMiB':resident//1024**2,'unchangedApiWorkerGateway':before,'runnerId':runner['Id'],'caps':caps,'composeSha256':hashlib.sha256((root/'compose.json').read_bytes()).hexdigest(),'time':time.time()}
(root/'reports/budget-rebalanced.json').write_text(json.dumps(record,indent=2));print(json.dumps(record))
'''.replace('ROOT',repr(root)).replace('APPLY',repr(a.apply))
    compile(code,'r54-idle-budget','exec');result=json.loads(m.remote(code,timeout=120));(m.DATA/'budget-rebalanced.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
    if a.apply:
        c=json.loads((m.DATA/'native/compose.json').read_text())
        for name,limit in result['caps'].items():c['services'][name]['mem_limit']=c['services'][name]['memswap_limit']=limit*1024**2
        (m.DATA/'native/compose.json').write_text(json.dumps(c,indent=2))

if __name__=='__main__':main()
