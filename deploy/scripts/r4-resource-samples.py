"""Bounded read-only release sampling, including short-lived workspace resources."""
from pathlib import Path
import argparse,json,shutil,subprocess,time

def main():
 p=argparse.ArgumentParser();p.add_argument('folder',type=Path);a=p.parse_args();root=a.folder.resolve(strict=True)
 assert root.parent==Path('/srv/agentplatform/operations') and root.name.startswith('r4-release-')
 output=root/'continuous-resources.jsonl';assert not output.exists();started=time.monotonic();samples=0;violations=0
 with output.open('x') as log:
  while time.monotonic()-started<2400:
   ids=subprocess.check_output(['docker','ps','-q'],text=True,timeout=15).split()
   rows=json.loads(subprocess.check_output(['docker','inspect',*ids],text=True,timeout=20)) if ids else []
   rows=[r for r in rows if r['State']['Running'] and (r['Config'].get('Labels',{}).get('com.docker.compose.project') in ['agentplatform-env','agentplatform-r4-app'] or any(m.get('Source','').startswith('/srv/agentplatform/workspaces') for m in r.get('Mounts',[])))]
   memory={l.split(':')[0]:int(l.split()[1])*1024 for l in Path('/proc/meminfo').read_text().splitlines() if l.startswith(('MemAvailable:','SwapTotal:','SwapFree:'))}
   values=[]
   for r in rows:
    v={'id':r['Id'],'image':r['Image'],'runtime':r['HostConfig']['Runtime'],'capBytes':r['HostConfig']['Memory'],'swapCapBytes':r['HostConfig']['MemorySwap'],'restartCount':r['RestartCount'],'oomKilled':r['State']['OOMKilled']}
    cg=Path('/sys/fs/cgroup/system.slice/docker-'+r['Id']+'.scope')
    for name in ['memory.current','memory.peak','memory.swap.current','pids.current']:
     path=cg/name
     if path.exists():
      try:v[name]=int(path.read_text())
      except FileNotFoundError:pass
    values.append(v)
   total=sum(v['capBytes'] for v in values)+(128*1024**2 if any(v['runtime']=='runsc' for v in values) else 0);free=shutil.disk_usage('/').free
   passed=total<=2887*1024**2 and memory['MemAvailable']>=768*1024**2 and free>=5*1024**3 and all(v['capBytes']==v['swapCapBytes'] and not v['oomKilled'] for v in values)
   violations+=not passed;samples+=1;log.write(json.dumps({'time':time.time(),**memory,'rootFreeBytes':free,'limitBytesIncludingRuntime':total,'resourceAdmissionPass':passed,'containers':values})+'\n');log.flush()
   done=root/'observation.json'
   if done.exists():break
   time.sleep(5)
 (root/'continuous-resources.json').write_text(json.dumps({'status':'PASS' if violations==0 else 'FAIL','samples':samples,'violations':violations,'seconds':time.monotonic()-started,'sampledNotAbsolutePeaks':True,'supplierCalls':0},indent=2))
if __name__=='__main__':main()
