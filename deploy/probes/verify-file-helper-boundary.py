"""Protected, fixed cross-container conformance test; never a user execution API.

Run only inside the isolated R3.2 maintenance controller after its managed
environment has a committed STOPPED receipt and its applications are stopped.
The guest keeps gVisor. The separate trusted bridge keeps its restricted runc
policy. No external endpoint, host directory or program is accepted as input.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid


def main(plan_file):
    plan = json.loads(plan_file.read_text())
    root, op = Path(plan['root']), Path(plan['operationDirectory'])
    assert re.fullmatch('/srv/agentplatform/acceptance/r32-full-[a-z0-9-]+', str(root))
    assert op.resolve().is_relative_to(root.resolve())
    admission = json.loads((op/'reports/boundary-admission.json').read_text())
    assert admission['state'] == 'STOPPED' and admission['slotFree'] and admission['activeUses'] == 0
    scope = json.loads((root/'fixture-scope.json').read_text())
    space, workspace = str(uuid.UUID(scope['spaceId'])), str(uuid.UUID(scope['workspaceId']))
    work = root/'workspaces'/space/workspace/'work'
    assert work.is_dir() and not work.is_symlink()
    for parent in [work, *work.parents]:
        assert not parent.is_symlink()
    mount = json.loads(subprocess.check_output(['findmnt', '-J', '--target', str(work)]))['filesystems'][0]
    assert mount['target'] == str(root/'workspaces') and 'prjquota' in mount['options'].split(',')
    assert subprocess.check_output(['findmnt','-n','-o','UUID','--target',str(work)],text=True).strip() == plan['filesystemUuid']
    assert shutil.disk_usage('/').free >= 5*1024**3
    available = next(int(s.split()[1])*1024 for s in Path('/proc/meminfo').read_text().splitlines() if s.startswith('MemAvailable:'))
    assert available >= 768*1024**2
    ids = subprocess.check_output(['docker','ps','-q'],text=True).split()
    rows = json.loads(subprocess.check_output(['docker','inspect',*ids])) if ids else []
    assert sum(r['HostConfig']['Memory'] for r in rows)+(512+64+128)*1024**2 <= 2887*1024**2
    before = json.loads((op/'reports/production-before.json').read_text())
    assert before['originalUuid']=='fe8f72e3-4958-4048-a091-b1933d4195da'
    assert all(r['Config'].get('Labels',{}).get('com.docker.compose.project')==plan['project'] for r in rows)
    for row in rows:
        service = row['Config'].get('Labels',{}).get('com.docker.compose.service')
        if any(m.get('Source','').startswith(str(root)+'/') for m in row['Mounts']):
            assert service in ['postgres','rabbitmq']
    images = {r['component']:r['configId'] for r in plan['images']}
    nonce = str(uuid.uuid4()); prefix = 'r32-boundary-'+nonce
    sandbox, helper = prefix+'-guest', prefix+'-bridge'
    report = {'status':'FAIL','scope':'Fixed native bridge boundary only; application ledger/fault evidence separate',
              'source':plan['source'],'supplierCalls':0,'cases':[],'containers':[]}
    guest = None

    def call(args, data=None, allowed=(0,), timeout=25):
        p = subprocess.run(args,input=data,text=True,capture_output=True,timeout=timeout)
        assert p.returncode in allowed, 'Fixed boundary command failed; no retry'
        assert len(p.stdout.encode()) <= 65536 and len(p.stderr.encode()) <= 8192
        return p.stdout.strip()

    def inspect(name):
        return json.loads(call(['docker','inspect',name]))[0]

    def create(name, image, runtime, memory, pids, tmp, program):
        args = ['docker','create','--name',name,'--label','r32.root='+str(root),'--label','r32.boundary='+nonce,
                '--runtime',runtime,'--network','none','--read-only','--user','10001:10001',
                '--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory',str(memory)+'m',
                '--memory-swap',str(memory)+'m','--cpus','1','--pids-limit',str(pids),
                '--tmpfs','/tmp:rw,noexec,nosuid,size='+str(tmp)+'m,uid=10001,gid=10001,mode=700',
                '--mount','type=bind,src='+str(work)+',dst=/workspace','--entrypoint','python3']
        if runtime == 'runsc': args += ['--ulimit','nproc=32:32']
        cid = call([*args,image,*program]); report['containers'].append({'id':cid,'image':image,'runtime':runtime})
        row = inspect(cid); host = row['HostConfig']
        assert row['Image'] == image and row['Config']['User'] == '10001:10001'
        assert host['Runtime'] == runtime and host['NetworkMode'] == 'none' and host['ReadonlyRootfs']
        assert not host['Privileged'] and not host.get('Devices') and not host.get('CapAdd')
        assert host['Memory'] == host['MemorySwap'] == memory*1024**2
        binds=[m for m in row['Mounts'] if m['Type']=='bind']
        assert len(binds)==1 and binds[0]['Source']==str(work) and binds[0]['Destination']=='/workspace'
        assert set(host['CapDrop'])=={'ALL'} and 'no-new-privileges:true' in host['SecurityOpt']
        call(['docker','start',cid]); return cid

    def bridge(action, path, content=None):
        request = {'action':action,'path':path}
        if content is not None: request['contentBase64'] = base64.b64encode(content).decode()
        output = call(['docker','exec','-i',helper,'python3','/opt/platform/sandbox-file-bridge.py'],
                      json.dumps(request)+'\n',allowed=(0,1))
        return json.loads(output)

    try:
        create(sandbox, images['sandbox'], 'runsc', 512, 128, 64, ['-c','import time;time.sleep(180)'])
        create(helper, images['workspace-quota'], 'runc', 64, 32, 8, ['/opt/platform/file-helper-entrypoint.py','idle'])
        case = 'boundary-'+nonce
        # Fixed fixture files only. The link target exists in the reader's image,
        # so a mistaken read cannot pass by merely returning ENOENT.
        init = """import os
from pathlib import Path
p=Path('/workspace')/CASE;p.mkdir(mode=0o700)
(p/'safe').mkdir();(p/'safe'/'file-helper-entrypoint.py').write_bytes(b'BOUNDARY_INSIDE')
(p/'single').write_bytes(b'BOUNDARY_INSIDE');os.link(p/'single',p/'hard')
os.symlink('/opt/platform',p/'outside');os.mkfifo(p/'pipe')
print('ready')
""".replace('CASE',repr(case))
        assert call(['docker','exec','-i',sandbox,'python3','-'],init) == 'ready'
        for path in ['/etc/passwd','../outside','a/../../outside',case+'/outside/file-helper-entrypoint.py',case+'/hard',case+'/pipe']:
            assert not bridge('READ',path)['ok']; report['cases'].append('rejected:'+path)
        listing = bridge('LIST',case); assert listing['ok']
        assert all(x['kind']=='REJECTED' for x in listing['entries'] if x['name'] in ['hard','single','outside','pipe'])
        mutator = """import json,os,time
from pathlib import Path
p=Path('/workspace')/CASE;count=0;end=time.monotonic()+120
while time.monotonic()<end and not(p/'stop').exists():
 os.rename(p/'safe',p/'park');os.symlink('/opt/platform',p/'safe')
 os.unlink(p/'safe');os.rename(p/'park',p/'safe');count+=1
 if count%64==0:(p/'ready').write_text(str(count))
print(json.dumps({'mutations':count}),flush=True)
""".replace('CASE',repr(case))
        guest = subprocess.Popen(['docker','exec','-i',sandbox,'python3','-'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        guest.stdin.write(mutator);guest.stdin.close()
        deadline = time.monotonic()+15
        while not (work/case/'ready').exists():
            assert guest.poll() is None and time.monotonic()<deadline;time.sleep(.1)
        accepted = rejected = 0
        for _ in range(24):
            read = bridge('READ',case+'/safe/file-helper-entrypoint.py')
            if read['ok']:
                assert base64.b64decode(read['contentBase64']) == b'BOUNDARY_INSIDE';accepted+=1
            else: rejected+=1
        assert guest.poll() is None
        assert bridge('WRITE',case+'/stop',b'stop')['ok']
        guest.wait(timeout=15)
        output = guest.stdout.read(4097); errors = guest.stderr.read(8193)
        assert guest.returncode == 0 and not errors and len(output)<=4096
        assert json.loads(output)['mutations'] >= 64
        report.update(status='PASS',raceReads=24,acceptedSafeReads=accepted,rejectedReads=rejected,
                      mutations=json.loads(output)['mutations'])
        report['cases'] += ['cross-container-directory-link-rotation-no-outside-read','bounded-guest-client-terminated']
    finally:
        failures=[]
        for row in reversed(report['containers']):
            try:
                before=inspect(row['id'])['State']
                if before['Status']=='created' and not before['Running'] and before['Pid']==0:
                    assert before['StartedAt'].startswith('0001') and before['FinishedAt'].startswith('0001')
                    row['neverStarted']=True
                    call(['docker','rm',row['id']])
                    continue
                call(['docker','stop','-t','5',row['id']]);state=inspect(row['id'])['State']
                assert not state['Running'] and state['Pid']==0 and state['FinishedAt']>state['StartedAt']
                row['stopped']={'startedAt':state['StartedAt'],'finishedAt':state['FinishedAt'],'pid':state['Pid']}
                call(['docker','rm',row['id']])
            except BaseException as failure:failures.append(type(failure).__name__)
        if guest is not None and guest.poll() is None:
            try:guest.wait(timeout=10)
            except BaseException:failures.append('GuestCallbackStillActive')
        if failures:report.update(status='FAIL',stopFailures=failures)
        (op/'reports/file-helper-boundary.json').write_text(json.dumps(report,indent=2))
    assert report['status']=='PASS'
    print(json.dumps({'status':'PASS','raceReads':24,'supplierCalls':0}))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan',type=Path,required=True)
    main(parser.parse_args().plan)
