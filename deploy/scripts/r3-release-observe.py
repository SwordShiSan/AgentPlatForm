"""Bounded production resource sampling; no credentials or provider calls."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,time,traceback,urllib.request
def main():
 p=argparse.ArgumentParser();p.add_argument('folder',type=Path);p.add_argument('--seconds',type=int,default=1800);a=p.parse_args()
 assert 1800<=a.seconds<=2400 and a.folder.parent==Path('/srv/agentplatform/operations')
 os.umask(0o077);assert not (a.folder/'observation-start.json').exists()
 def out(args):return subprocess.check_output(args,text=True).strip()
 ids=[]
 for project in ['agentplatform-env','agentplatform-r3-app']:ids+=out(['docker','ps','--no-trunc','-q','--filter','label=com.docker.compose.project='+project]).split()
 assert len(ids)==11
 original={r['Id']:{'image':r['Image'],'startedAt':r['State']['StartedAt'],'service':r['Config']['Labels']['com.docker.compose.service']} for r in json.loads(out(['docker','inspect',*ids]))}
 samples=[];started=time.monotonic();(a.folder/'observation-start.json').write_text(json.dumps({'time':time.time(),'seconds':a.seconds,'required':original},indent=2))
 result={'status':'FAIL','scope':'Production resource/readiness samples; no absolute peak or concurrency claim','supplierCalls':0};fault=False
 try:
  while time.monotonic()-started<a.seconds:
   elapsed=time.monotonic()-started
   mem={line.split(':')[0]:int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemAvailable:','SwapFree:','SwapTotal:'))}
   assert mem['MemAvailable']>=768*1024**2 and shutil.disk_usage('/').free>=5*1024**3
   running=out(['docker','ps','--no-trunc','-q']).split();assert set(ids)<=set(running),'Required service disappeared'
   rows=json.loads(out(['docker','inspect',*running]));total=0;containers=[]
   for row in rows:
    host=row['HostConfig'];state=row['State'];assert state['Running'] and not state['OOMKilled']
    assert host['Memory']>0 and host['Memory']==host['MemorySwap'];total+=host['Memory']
    if host.get('Runtime')=='runsc':total+=128*1024**2
    if row['Id'] in original:assert row['Image']==original[row['Id']]['image']
    peak=Path('/sys/fs/cgroup/system.slice/docker-'+row['Id']+'.scope/memory.peak')
    current=peak.with_name('memory.current')
    containers.append({'id':row['Id'],'memoryLimit':host['Memory'],'currentBytes':int(current.read_text()) if current.exists() else None,'sampledKernelPeakBytes':int(peak.read_text()) if peak.exists() else None})
   assert total<=2887*1024**2
   request=urllib.request.Request('http://127.0.0.1/api/v1/readiness',headers={'Host':'agentplatform.lailihao.cloud'})
   with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request,timeout=10) as r:assert r.status==200
   sample={'time':time.time(),'elapsedSeconds':elapsed,**mem,'rootFreeBytes':shutil.disk_usage('/').free,'limitsIncludingRuntime':total,'containers':containers}
   with (a.folder/'resource-samples.jsonl').open('a') as stream:stream.write(json.dumps(sample)+'\n')
   samples.append(sample);(a.folder/'observation-progress.json').write_text(json.dumps({'elapsedSeconds':elapsed,'samples':len(samples)}))
   if elapsed>=600 and not fault:
    broker=next(k for k,v in original.items() if v['service']=='rabbitmq')
    subprocess.run(['docker','restart','-t','30',broker],check=True,capture_output=True,timeout=120)
    for _ in range(60):
     state=json.loads(out(['docker','inspect',broker]))[0]['State']
     if state.get('Health',{}).get('Status')=='healthy':break
     time.sleep(2)
    else:raise RuntimeError('Broker did not recover')
    fault=True;(a.folder/'broker-restart.json').write_text(json.dumps({'status':'PASS','exactId':broker,'time':time.time(),'noModelRequest':True}))
   time.sleep(10)
  result.update(status='PASS',elapsedSeconds=time.monotonic()-started,samples=len(samples),brokerRestart=fault,minAvailableBytes=min(x['MemAvailable'] for x in samples),minRootFreeBytes=min(x['rootFreeBytes'] for x in samples),swapUsedFirstBytes=samples[0]['SwapTotal']-samples[0]['SwapFree'],swapUsedLastBytes=samples[-1]['SwapTotal']-samples[-1]['SwapFree'],maxInspectedLimits=max(x['limitsIncludingRuntime'] for x in samples))
 except BaseException:
  (a.folder/'observation-failure.private.log').write_text(traceback.format_exc());raise
 finally:(a.folder/'observation.json').write_text(json.dumps(result,indent=2))
if __name__=='__main__':main()
