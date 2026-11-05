"""Read-only R54 host readiness. Does not stop, modify or start any resource."""
from pathlib import Path
import hashlib, json, shutil, subprocess

def read(args):return subprocess.check_output(args,text=True).strip()

def main():
    ids=read(['docker','ps','-q','--no-trunc']).split()
    rows=json.loads(read(['docker','inspect',*ids])) if ids else []
    selected=[r for r in rows if r['Config'].get('Labels',{}).get('com.docker.compose.project') in ['agentplatform-env','agentplatform-r4-app']]
    safe=[{'id':r['Id'],'image':r['Image'],'service':r['Config']['Labels']['com.docker.compose.service'],
           'running':r['State']['Running'],'oom':r['State']['OOMKilled'],'memory':r['HostConfig']['Memory'],
           'restart':r['HostConfig']['RestartPolicy']} for r in selected]
    memory={line.split(':')[0]:int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemAvailable:','SwapFree:','SwapTotal:'))}
    mount=json.loads(read(['findmnt','-J','-o','TARGET,SOURCE,FSTYPE,OPTIONS,UUID','--target','/srv/agentplatform/workspaces']))['filesystems'][0]
    runtime=json.loads(Path('/etc/docker/daemon.json').read_text())['runtimes']['runsc']
    result={'status':'READ_ONLY','production':'R4','services':safe,'rootFreeBytes':shutil.disk_usage('/').free,
            'memory':memory,'mount':mount,'runscSha256':hashlib.sha256(Path(runtime['path']).read_bytes()).hexdigest(),
            'runtimeArgs':runtime.get('runtimeArgs'),'supplierCalls':0}
    assert len(safe)==11 and all(r['running'] and not r['oom'] for r in safe),'Inspect actual production graph before maintenance'
    print(json.dumps(result))

if __name__=='__main__':main()
