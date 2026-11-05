"""One 180-second pinned R5 content/readiness/resource observation; no model calls."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,shutil,subprocess,time,urllib.request


def main():
    parser=argparse.ArgumentParser();parser.add_argument('folder',type=Path);parser.add_argument('--final',action='store_true');args=parser.parse_args()
    root=args.folder.resolve(strict=True);assert root.parent==Path('/srv/agentplatform/operations') and root.name.startswith('r5-release-')
    name='final-observation' if args.final else 'observation';assert not (root/(name+'.json')).exists()
    smoke=json.loads((root/'smoke.json').read_text());assert smoke['status']=='PASS'
    if args.final:assert json.loads((root/'final-smoke.json').read_text())['status']=='PASS'
    spec=importlib.util.spec_from_file_location('smoke',root/'r5-production-smoke.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    api=m.Api(json.loads((root/'probe-accounts.private.json').read_text())[0])
    def read(command):return subprocess.check_output(command,text=True,timeout=45).strip()
    ids=[]
    for project in ['agentplatform-env','agentplatform-r5-app']:ids+=read(['docker','ps','-q','--no-trunc','--filter','label=com.docker.compose.project='+project]).split()
    original={row['Id']:row for row in json.loads(read(['docker','inspect',*ids]))};assert len(original)==11
    assert sum(row['HostConfig']['Memory'] for row in original.values())+704*1024**2<=2887*1024**2
    source=api.request('GET','/attachments/'+smoke['attachmentId'])[1];samples=[];started=time.monotonic();fault=False
    (root/(name+'-intent.json')).write_text(json.dumps({'time':time.time(),'requiredIds':list(original),'seconds':180}))
    try:
        while True:
            rows=json.loads(read(['docker','inspect',*ids]))
            assert all(row['State']['Running'] and not row['State']['OOMKilled'] and row['Image']==original[row['Id']]['Image'] for row in rows)
            memory={line.split(':')[0]:int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemAvailable:','SwapTotal:','SwapFree:'))}
            assert memory['MemAvailable']>=768*1024**2 and shutil.disk_usage('/').free>=5*1024**3
            content=api.request('GET','/attachments/'+source['id']+'/content',binary=True)[1]
            assert len(content)==source['bytes'] and hashlib.sha256(content).hexdigest()==source['sha256']
            assert api.request('GET','/me/storage-usage')[1]['reservedBytes']==0
            request=urllib.request.Request('http://127.0.0.1/api/v1/readiness',headers={'Host':'agentplatform.lailihao.cloud'})
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request,timeout=10) as response:assert json.load(response)=={'status':'READY'}
            usages=[]
            for row in rows:
                base=Path('/sys/fs/cgroup/system.slice/docker-'+row['Id']+'.scope')
                usages.append({'id':row['Id'],'memoryBytes':int((base/'memory.current').read_text()) if (base/'memory.current').exists() else None,'pids':int((base/'pids.current').read_text()) if (base/'pids.current').exists() else None})
            sample={'time':time.time(),'elapsedSeconds':time.monotonic()-started,**memory,'rootFreeBytes':shutil.disk_usage('/').free,'containers':usages}
            samples.append(sample)
            with (root/(name+'-samples.jsonl')).open('a') as target:target.write(json.dumps(sample)+'\n')
            if not args.final and not fault and time.monotonic()-started>=40:
                broker=next(row['Id'] for row in rows if row['Config']['Labels']['com.docker.compose.service']=='rabbitmq')
                subprocess.run(['docker','restart','-t','20',broker],check=True,capture_output=True,timeout=90)
                for _ in range(60):
                    state=json.loads(read(['docker','inspect',broker]))[0]['State']
                    if state['Running'] and state.get('Health',{}).get('Status')=='healthy':break
                    time.sleep(1)
                else:raise RuntimeError('Original broker did not recover')
                fault=True
            if time.monotonic()-started>=180:break
            time.sleep(5)
        result={'status':'PASS','elapsedSeconds':round(time.monotonic()-started,3),'samples':len(samples),'brokerRestart':fault,'minAvailableBytes':min(s['MemAvailable'] for s in samples),'minRootFreeBytes':min(s['rootFreeBytes'] for s in samples),'swapUsedFirstBytes':samples[0]['SwapTotal']-samples[0]['SwapFree'],'swapUsedLastBytes':samples[-1]['SwapTotal']-samples[-1]['SwapFree'],'supplierCalls':0,'samplesNotAbsolutePeaks':True,'requiredContainers':len(ids)}
    except BaseException:
        (root/(name+'.json')).write_text(json.dumps({'status':'FAIL','elapsedSeconds':time.monotonic()-started,'samples':len(samples)}));raise
    (root/(name+'.json')).write_text(json.dumps(result,indent=2));print(json.dumps(result))


if __name__=='__main__':main()
