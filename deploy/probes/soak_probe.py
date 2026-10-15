"""30-minute single-sandbox baseline observation, samples are evidence not throughput promises."""
import fcntl,json,pathlib,re,subprocess,time,urllib.request,urllib.error
root=pathlib.Path('/srv/agentplatform');report=root/'reports/soak.jsonl'
images=dict(line.split('=',1) for line in (root/'deploy/images.env').read_text().splitlines() if '=' in line)
assert re.fullmatch(r'sha256:[0-9a-f]{64}',images.get('SANDBOX_IMAGE','')),'Verified immutable sandbox identity required'
def cmd(args):return subprocess.run(args,check=True,text=True,capture_output=True,timeout=90).stdout
def health():
 for endpoint in ['health','readiness']:
  with urllib.request.urlopen('http://127.0.0.1:18080/api/v1/'+endpoint,timeout=10) as r:assert r.status==200
def dependency_restart(name):
 cmd(['docker','stop','-t','30',name])
 try:
  urllib.request.urlopen('http://127.0.0.1:18080/api/v1/readiness',timeout=10)
  raise AssertionError('Readiness stayed green while required dependency stopped')
 except urllib.error.HTTPError as e:assert e.code==503
 cmd(['docker','start',name])
 for _ in range(120):
  try:health();return
  except urllib.error.HTTPError:time.sleep(1)
 raise AssertionError('Dependency restart did not recover readiness')
def sample():
 mem={k.rstrip(':'):int(v.split()[0]) for k,v in (line.split(None,1) for line in pathlib.Path('/proc/meminfo').read_text().splitlines())}
 return {'at':time.time(),'memAvailableKiB':mem['MemAvailable'],'swapUsedKiB':mem['SwapTotal']-mem['SwapFree'],'rootAvailableBytes':__import__('shutil').disk_usage('/').free,'stats':[json.loads(x) for x in cmd(['docker','stats','--no-stream','--format','{{json .}}']).splitlines()]}
begin=time.monotonic();initial=sample();iterations=0
report.write_text(json.dumps({'initial':initial})+'\n')
while time.monotonic()-begin<1800:
 health()
 mem=sample();assert mem['memAvailableKiB']>512*1024
 # Work is admitted only with room for512MiB+128MiB runtime and512MiB safety floor.
 if mem['memAvailableKiB']>=1152*1024:
  with (root/'operations/sandbox-slot.lock').open('a') as slot:
   fcntl.flock(slot,fcntl.LOCK_EX|fcntl.LOCK_NB)
   cmd(['docker','run','--rm','--name','agentplatform-r03-soak-sandbox','--runtime=runsc','--user','10001:10001','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','512m','--memory-swap','512m','--cpus','1','--pids-limit','128','--ulimit','nproc=128:128','--tmpfs','/tmp:rw,noexec,nosuid,size=64m','--label','project=agentplatform','--label','sandbox=true',images['SANDBOX_IMAGE'],'python','-c','import hashlib;assert len(hashlib.sha256(b"baseline").digest())==32;print(666)'])
  iterations+=1
 else:mem['admission']='QUEUED'
 elapsed=time.monotonic()-begin
 # Controlled real restarts, once each; no legacy services touched.
 if 600<=elapsed<660 and not (root/'reports/soak-redis-restarted').exists():
  dependency_restart('agentplatform-env-redis-1');(root/'reports/soak-redis-restarted').touch()
 if 1200<=elapsed<1260 and not (root/'reports/soak-s3-restarted').exists():
  dependency_restart('agentplatform-env-s3-1');(root/'reports/soak-s3-restarted').touch()
 with report.open('a') as f:f.write(json.dumps(mem)+'\n')
 time.sleep(30)
health();final=sample()
assert iterations>0
assert final['swapUsedKiB']<=initial['swapUsedKiB']+64*1024,'Swap growth is not extra capacity'
(root/'reports/soak-summary.json').write_text(json.dumps({'status':'PASS','seconds':time.monotonic()-begin,'iterations':iterations,'initial':initial,'final':final},indent=2))
print('30-minute minimum-host soak:PASS',flush=True)
