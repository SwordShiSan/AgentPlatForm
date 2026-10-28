"""Retained-platform switch client. Default is read-only; resume an uncertain request instead of resubmitting."""
import argparse,json,os,pathlib,subprocess,time,uuid

REMOTE=r'''
from pathlib import Path
import datetime,fcntl,hashlib,json,os,subprocess,time,urllib.request
OPS=Path('/srv/agentplatform/operations/platform-switch');HELPER=OPS/'platform-switch.py'
assert hashlib.sha256(HELPER.read_bytes()).hexdigest()=='2d3021778267cd364568398fc44ba8f8d51b8eebc5b09ad3392e7ed2402a1e8b','Host helper changed; review identity before use'
def call(args):
 p=subprocess.run(args,text=True,capture_output=True,timeout=60)
 assert p.returncode==0,p.stderr[-1500:]
 return json.loads(p.stdout)
if ACTION=='status' or not APPLY:
 print(json.dumps(call(['python3',str(HELPER),ACTION])));raise SystemExit(0)
requests=OPS/'requests';requests.mkdir(mode=0o700,exist_ok=True)
record=requests/(REQUEST+'.json')
with (OPS/'request-launch.lock').open('a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 if record.exists():
  job=json.loads(record.read_text());assert ACTION in ['resume',job['target']],'Request target mismatch'
 else:
  assert ACTION!='resume','Unknown request; nothing was launched'
  for proc in Path('/proc').iterdir():
   if not proc.name.isdigit():continue
   try:cmd=(proc/'cmdline').read_bytes();state=(proc/'stat').read_text().split()[2]
   except (FileNotFoundError,PermissionError,ProcessLookupError):continue
   native=b'/srv/agentplatform/acceptance/' in cmd and (b'/window.py' in cmd or b'/watchdog.py' in cmd)
   release=b'/srv/agentplatform/operations/r4-release-' in cmd and any(name in cmd for name in [b'r4-release-control.py',b'r4-isolated-restore.py',b'production-observe.py',b'production-watchdog.py'])
   assert state=='Z' or not (native or release),'Acceptance/release controller still active; finish its restoration first'
  # Existing host preflight verifies exact retained identities, topology and active work.
  preview=call(['python3',str(HELPER),ACTION]);assert preview['status']=='DRY_RUN'
  sid=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
  assert not (OPS/'sessions'/sid).exists(),'Operation timestamp collision; no switch launched'
  job={'requestId':REQUEST,'operationId':sid,'target':ACTION,'status':'LAUNCH_INTENT','time':time.time()}
  record.write_text(json.dumps(job,indent=2));record.chmod(0o600)
  logpath=OPS/('switch-'+sid+'.log')
  with logpath.open('xb',buffering=0) as output:
   logpath.chmod(0o600)
   process=subprocess.Popen(['python3','-u',str(HELPER),ACTION,'--apply','--operation-id',sid],stdin=subprocess.DEVNULL,stdout=output,stderr=output,start_new_session=True)
  job.update(status='RUNNING',pid=process.pid);record.write_text(json.dumps(job,indent=2))
result=OPS/'sessions'/job['operationId']/'result.json'
if result.exists():
 job['result']=json.loads(result.read_text());job['status']=job['result']['status']
 if job['status']=='PASS':
  current=call(['python3',str(HELPER),'status']);expected='new' if job['target']=='to-new' else 'legacy'
  assert current['activeSwitch']['operationId']==job['operationId'] and current['activeSwitch']['activePlatform']==expected
  expectedNew=len(json.loads((OPS/'release-selection.json').read_text())['containers'])
  assert expectedNew>0 and len(current['newRunning'])==(expectedNew if expected=='new' else 0) and len(current['legacyRunning'])==(10 if expected=='legacy' else 0)
  url='http://127.0.0.1/api/v1/readiness' if expected=='new' else 'http://127.0.0.1:8091/api/v1/platform/readiness'
  request=urllib.request.Request(url,headers={'Host':'124.222.55.140'} if expected=='new' else {})
  with urllib.request.urlopen(request,timeout=15) as response:
   body=json.loads(response.read());assert response.status==200
   if expected=='legacy':assert body['data']['ready'] is True
  job['health']='PASS';job['activePlatform']=expected
else:
 proc=Path('/proc/'+str(job.get('pid',0)))
 if not proc.exists() or (proc/'stat').read_text().split()[2]=='Z':job['status']='UNKNOWN_PROCESS_EXITED'
print(json.dumps(job))
'''

def parser():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['status','to-new','to-legacy','resume'])
    p.add_argument('--identity-file',default=os.environ.get('AGENTPLATFORM_SSH_KEY'))
    p.add_argument('--host',default='124.222.55.140')
    p.add_argument('--apply',action='store_true',help='Only with the user-authorized direction; otherwise dry-run')
    p.add_argument('--request-id',help='Required for resume; reuse after a lost SSH response')
    p.add_argument('--wait-seconds',type=int,default=600)
    p.add_argument('--receipt-dir',type=pathlib.Path,default=pathlib.Path('.cache/platform-switch'))
    return p

def main():
    p=parser();args=p.parse_args()
    if not args.identity_file:p.error('Use --identity-file or AGENTPLATFORM_SSH_KEY; keys are never copied')
    key=pathlib.Path(args.identity_file)
    if not key.is_file() or key.is_symlink():p.error('Identity must be an existing regular key file')
    if args.action=='resume' and not args.request_id:p.error('resume requires the original --request-id')
    if args.action=='resume':args.apply=True
    if not 0<=args.wait_seconds<=900:p.error('wait-seconds must be0..900')
    request=str(uuid.UUID(args.request_id)) if args.request_id else str(uuid.uuid4())
    args.receipt_dir.mkdir(parents=True,exist_ok=True)
    receipt=args.receipt_dir/(request+'.json')
    if not receipt.exists():receipt.write_text(json.dumps({'requestId':request,'target':args.action,'status':'NOT_SENT','createdAt':time.time()}),encoding='utf-8')
    print(json.dumps({'requestId':request,'receipt':str(receipt),'mode':'APPLY' if args.apply else 'READ_ONLY'}),flush=True)
    start=time.monotonic();last=None;action=args.action
    while True:
        script='ACTION='+repr(action)+'\nAPPLY='+repr(args.apply)+'\nREQUEST='+repr(request)+'\n'+REMOTE
        ssh=['ssh','-F','NUL' if os.name=='nt' else '/dev/null','-i',str(key),'-o','BatchMode=yes','-o','ConnectTimeout=10','root@'+args.host,'python3 -']
        response=subprocess.run(ssh,input=script,text=True,encoding='utf-8',capture_output=True,timeout=90)
        if response.returncode:
            print('SSH/preflight failed. Read saved request and use resume; do not create a second apply request.',flush=True)
            print(response.stderr[-1800:]);return 2
        row=json.loads(response.stdout);receipt.write_text(json.dumps(row,indent=2),encoding='utf-8')
        status=row.get('status','STATUS')
        if status!=last:print(json.dumps(row),flush=True);last=status
        if not args.apply or status in ['PASS','FAIL','UNKNOWN_PROCESS_EXITED']:return 0 if not args.apply or status=='PASS' else 1
        if time.monotonic()-start>=args.wait_seconds:
            print('Still running; resume with request '+request,flush=True);return 3
        action='resume';time.sleep(10)

if __name__=='__main__':raise SystemExit(main())
