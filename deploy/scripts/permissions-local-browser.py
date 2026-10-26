"""Real-browser R4.2 acceptance, fixed to this stage's loopback fixture and immutable runtime images."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, time, urllib.request

ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'.cache/r42-local'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--permissions-only',action='store_true',help='Target only the two new permission cases after a locator/fixture-only fix')
args=parser.parse_args()
out=DATA/('browser-'+str(time.time_ns()));out.mkdir(parents=True,exist_ok=False)
source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT),'Browser evidence requires committed test source'
images=json.loads((DATA/'images.json').read_text(encoding='utf-8'))
with urllib.request.urlopen('http://127.0.0.1:18102/api/v1/readiness',timeout=5) as ready:assert ready.status==200
node=ROOT/'.tools/node-v22.23.3-win-x64/node.exe'
env=dict(os.environ,R42_BROWSER_ACCEPTANCE='1',BASE_URL='http://127.0.0.1:18102',PATH=str(node.parent)+os.pathsep+os.environ['PATH'])
command=[str(node),str(ROOT/'frontend/node_modules/@playwright/test/cli.js'),'test','permissions.spec.ts','--reporter=json']
if args.permissions_only:command+=['--grep','task workspace binding|two tabs cannot']
report=out/'playwright.json';errors=out/'playwright.stderr.log'
with report.open('xb') as stdout,errors.open('xb') as stderr:
    result=subprocess.run(command,cwd=ROOT/'frontend',env=env,stdout=stdout,stderr=stderr)
parsed=json.loads(report.read_text(encoding='utf-8'))
expected=2 if args.permissions_only else 5
stats=parsed['stats'];passed=result.returncode==0 and stats['unexpected']==0 and stats['skipped']==0 and stats['expected']==expected
record={'status':'PASS' if passed else 'FAIL','exitCode':result.returncode,'testSource':source,
        'runtimeSource':images['source'],'supplierCalls':0,'stats':stats,'caseGroup':'permissions' if args.permissions_only else 'full',
        'reportSha256':hashlib.sha256(report.read_bytes()).hexdigest(),'command':command}
(out/'result.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({'status':record['status'],'exitCode':result.returncode,'evidence':str(out.relative_to(ROOT))}),flush=True)
raise SystemExit(0 if passed else 1)
