"""Observe the isolated R6.1 stack for 180 seconds without supplier calls."""
from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'.cache/r61-local'
spec=importlib.util.spec_from_file_location('r61_local',ROOT/'deploy/scripts/r61-local.py')
local=importlib.util.module_from_spec(spec);spec.loader.exec_module(local)
local.environment()
images=json.loads((DATA/'images.json').read_text(encoding='utf-8'))
fixture=json.loads((DATA/'fixture/manifest.json').read_text(encoding='utf-8'))
out=DATA/('observation-'+str(time.time_ns()));out.mkdir(parents=True,exist_ok=False)
names=['api','worker','gateway','fixture','rabbitmq','postgres']
started=time.monotonic();samples=[];status='FAIL'
try:
    while True:
        ids=local.call(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-r61-local']).split()
        rows=json.loads(local.call(['docker','inspect',*ids])) if ids else []
        indexed={row['Config']['Labels'].get('com.docker.compose.service'):row for row in rows}
        if set(indexed)!=set(names):raise RuntimeError('Required fixture services are not all running')
        if any(indexed[name]['State'].get('Health',{}).get('Status')!='healthy' for name in ['api','worker','rabbitmq','postgres']):
            raise RuntimeError('A required fixture service is not healthy')
        limits=sum(indexed[name]['HostConfig']['Memory'] for name in names)
        if limits>2887*1024*1024:raise RuntimeError('Container memory limits exceed the approved budget')
        if any(indexed[name]['HostConfig']['MemorySwap']!=indexed[name]['HostConfig']['Memory'] for name in names):
            raise RuntimeError('A fixture container permits swap expansion')
        memory=local.call(['docker','exec','agentplatform-r61-local-postgres-1','cat','/proc/meminfo'])
        values={line.split(':',1)[0]:int(line.split(':',1)[1].strip().split()[0]) for line in memory.splitlines() if ':' in line and line.split(':',1)[1].strip().split() and line.split(':',1)[1].strip().split()[0].isdigit()}
        if values.get('MemAvailable',0)<768*1024:raise RuntimeError('Available memory below the approved reserve')
        disk=local.call(['docker','exec','agentplatform-r61-local-postgres-1','df','-Pk','/var/lib/postgresql/data']).splitlines()[-1].split()
        availableDiskKiB=int(disk[3]);
        if availableDiskKiB<5*1024*1024:raise RuntimeError('Docker filesystem reserve below 5 GiB')
        stats=local.call(['docker','stats','--no-stream','--format','{{.Name}}|{{.MemUsage}}|{{.PIDs}}',*ids]).splitlines()
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open('http://127.0.0.1:'+str(fixture['gatewayPort'])+'/api/v1/readiness',timeout=5) as ready:
            if ready.status!=200:raise RuntimeError('Readiness failed')
        samples.append({'elapsedSeconds':round(time.monotonic()-started,2),'availableKiB':values['MemAvailable'],
                        'swapUsedKiB':values.get('SwapTotal',0)-values.get('SwapFree',0),'diskAvailableKiB':availableDiskKiB,'limitsBytes':limits,'containers':stats})
        if time.monotonic()-started>=180:break
        time.sleep(min(15,max(0,180-(time.monotonic()-started))))
    status='PASS'
finally:
    record={'status':status,'source':images['source'],'inputHash':images['inputHash'],'supplierCalls':0,
            'scope':'Samples only; not an absolute peak or higher concurrency claim','samples':samples}
    target=out/'observation.json';target.write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':status,'seconds':round(time.monotonic()-started,2),'samples':len(samples),'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'evidence':str(out.relative_to(ROOT))}))
