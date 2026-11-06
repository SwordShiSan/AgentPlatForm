"""Run real-browser R6.1 checks against the isolated local fixture only."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'.cache/r61-local'
spec=importlib.util.spec_from_file_location('r61_local',ROOT/'deploy/scripts/r61-local.py')
local=importlib.util.module_from_spec(spec);spec.loader.exec_module(local)
local.environment()  # Verify source and image identity before recording evidence.
fixture=json.loads((DATA/'fixture/manifest.json').read_text(encoding='utf-8'))
images=json.loads((DATA/'images.json').read_text(encoding='utf-8'))
out=DATA/('browser-'+str(time.time_ns()));out.mkdir(parents=True,exist_ok=False)
if os.name=='nt':
    user=subprocess.check_output(['whoami'],text=True,errors='replace').strip()
    subprocess.run(['icacls',str(out),'/inheritance:r','/grant:r',user+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],check=True,capture_output=True)
node=ROOT/'.tools/node-v22.23.3-win-x64/node.exe'
env=dict(os.environ,R61_BROWSER_ACCEPTANCE='1',BASE_URL='http://127.0.0.1:'+str(fixture['gatewayPort']),
         TEMP=str(DATA/'tmp'),TMP=str(DATA/'tmp'),PATH=str(node.parent)+os.pathsep+os.environ['PATH'])
command=[str(node),str(ROOT/'frontend/node_modules/@playwright/test/cli.js'),'test','personal-configuration.spec.ts','--reporter=json','--output',str(out/'artifacts')]
report=out/'playwright.json';errors=out/'playwright.stderr.log'
with report.open('xb') as stdout,errors.open('xb') as stderr:
    result=subprocess.run(command,cwd=ROOT/'frontend',env=env,stdout=stdout,stderr=stderr,timeout=300)
try:
    stats=json.loads(report.read_text(encoding='utf-8'))['stats']
    passed=result.returncode==0 and stats['unexpected']==0 and stats['skipped']==0 and stats['expected']==3
except (ValueError,KeyError):
    stats={};passed=False
record={'status':'PASS' if passed else 'FAIL','exitCode':result.returncode,'source':images['source'],'inputHash':images['inputHash'],
        'imageConfigIds':{name:value['configId'] for name,value in images['images'].items()},'stats':stats,'supplierCalls':0,
        'reportSha256':hashlib.sha256(report.read_bytes()).hexdigest()}
(out/'result.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'status':record['status'],'evidence':str(out.relative_to(ROOT))}))
raise SystemExit(0 if passed else 1)
