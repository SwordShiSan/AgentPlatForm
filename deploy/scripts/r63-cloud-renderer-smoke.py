"""Run the R6.3 TEXT_EXTRACT renderer cases on the cloud Linux runsc host.

This is a physical renderer check only. It does not touch R5 services, mount
production data, or claim that the durable application pipeline was exercised.
"""
from pathlib import Path
import argparse, hashlib, importlib.util, json, os, re, subprocess, tarfile, time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache' / 'r63-local'
IMAGE = 'sha256:502ba213ee70a1ed10e6a01aec519fb64c757a0c8201c774553c49d58f646545'
REVISION = 'e4c1cda2e5c20ac3d07bc4e3bc2be3cbb7873200'
RUNSC_SHA256 = '210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a'
# The cloud R5 host has a small, variable MemAvailable window. TEXT_EXTRACT
# uses the same 192 MiB Java heap and no LibreOffice child process; this
# one-shot probe uses a tighter 384 MiB cgroup cap to preserve host reserve.
TEST_MEMORY_MIB = 384
BASELINE_FILES = [
    'backend/platform-app/src/renderer/java/com/agentplatform/renderer/PreviewRendererMain.java',
    'backend/platform-app/src/renderer/java/com/agentplatform/renderer/DocumentTextExtractor.java',
    'backend/platform-app/pom.xml', 'deploy/preview-renderer.Dockerfile',
    'deploy/preview-renderer.lock.json',
]
FIXTURE_SOURCE = ROOT / '.cache' / 'r54-local' / 'preview-fixtures'
CASES = ('sample.docx', 'sample.xlsx', 'sample.pptx')

spec = importlib.util.spec_from_file_location('native_fixture', ROOT / 'deploy/scripts/files-native-fixture.py')
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)
native.DATA = DATA


def local(command, timeout=180):
    return native.local(command, timeout=timeout)


def remote(code, timeout=240):
    return json.loads(native.remote(code, timeout=timeout))


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def assert_renderer_inputs_unchanged():
    for name in BASELINE_FILES:
        changed = subprocess.run(['git', 'diff', '--quiet', REVISION, '--', name], cwd=ROOT).returncode
        if changed:
            raise RuntimeError('The cloud image cannot be reused because renderer input changed: ' + name)


def preflight(folder):
    code = f'''from pathlib import Path
import hashlib,json,re,shutil,subprocess
folder=Path({str(folder)!r}); assert str(folder).startswith('/srv/agentplatform/acceptance/r63-renderer-') and not folder.exists()
free=shutil.disk_usage('/').free
mem=next(int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:'))
blockers=[]
if free<5*1024**3: blockers.append('root disk reserve below 5 GiB')
if mem<({TEST_MEMORY_MIB}+128+768)*1024**2: blockers.append('host memory reserve below bounded renderer budget plus 768 MiB')
runtime=Path('/usr/local/lib/agentplatform-gvisor/20261005.0/runsc'); assert runtime.is_file()
runsc=hashlib.sha256(runtime.read_bytes()).hexdigest(); assert runsc=={RUNSC_SHA256!r}
runtimes=json.loads(subprocess.check_output(['docker','info','--format','{{{{json .Runtimes}}}}'],text=True)); assert 'runsc' in runtimes
image=json.loads(subprocess.check_output(['docker','image','inspect',{IMAGE!r}],text=True))[0]
assert image['Id']=={IMAGE!r} and image['Architecture']=='amd64' and image['Config']['Labels']['org.opencontainers.image.revision']=={REVISION!r}
services=subprocess.check_output(['docker','ps','-q','--filter','name=agentplatform-'],text=True).split()
prod={{row['Id']:row['Name'] for row in json.loads(subprocess.check_output(['docker','inspect',*services],text=True))}} if services else {{}}
print(json.dumps({{'eligible':not blockers,'blockers':blockers,'freeBytes':free,'memAvailableBytes':mem,'runtime':'runsc','runscSha256':runsc,'imageId':image['Id'],'imageRevision':image['Config']['Labels']['org.opencontainers.image.revision'],'productionContainers':prod}}))
'''
    return remote(code)


def remote_test(folder, fixture_hashes, production_containers):
    script = r'''from pathlib import Path
import hashlib,json,os,re,shutil,subprocess,time,uuid
ROOT=Path(ROOT_ARG); IMAGE=IMAGE_ARG; REVISION=REVISION_ARG; RUNSC=RUNSC_ARG
EXPECTED=EXPECTED_ARG; CASES=CASES_ARG; MEMORY_MIB=MEMORY_ARG; created=[]; rows=[]
def run(args,timeout=40): return subprocess.run(args,capture_output=True,timeout=timeout)
def inspect(cid): return json.loads(subprocess.check_output(['docker','inspect',cid],text=True))[0]
def mem(): return next(int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:'))
def platform_containers():
 ids=subprocess.check_output(['docker','ps','-q','--filter','name=agentplatform-'],text=True).split()
 return {{r['Id']:r['Name'] for r in json.loads(subprocess.check_output(['docker','inspect',*ids],text=True))}} if ids else {{}}
assert mem()>=(MEMORY_MIB+128+768)*1024**2, 'host memory reserve changed after preflight'
assert hashlib.sha256(Path('/usr/local/lib/agentplatform-gvisor/20261005.0/runsc').read_bytes()).hexdigest()==RUNSC
assert json.loads(subprocess.check_output(['docker','image','inspect',IMAGE],text=True))[0]['Id']==IMAGE
assert platform_containers()==EXPECTED_R5, 'platform container identities changed after preflight'
try:
 for filename in CASES:
  source=ROOT/'input'/filename; ext=source.suffix[1:]; expected=EXPECTED[filename]
  assert hashlib.sha256(source.read_bytes()).hexdigest()==expected
  name='ap-r63-renderer-'+uuid.uuid4().hex[:16]; started=time.monotonic()
  cmd=['docker','create','--name',name,'--runtime','runsc','--user','10001:10001','--network','none','--read-only','--cap-drop','ALL',
       '--security-opt','no-new-privileges:true','--memory',str(MEMORY_MIB)+'m','--memory-swap',str(MEMORY_MIB)+'m','--cpus','1','--pids-limit','128',
       '--tmpfs','/tmp:rw,noexec,nosuid,size=256m,uid=10001,gid=10001,mode=700',
       '--mount',f'type=bind,source={source},target=/input/source.{ext},readonly','--label','ap.r63.acceptance=renderer-text-extract',
       IMAGE,ext,'TEXT_EXTRACT',expected]
  cid=run(cmd,20); assert cid.returncode==0,cid.stderr.decode(errors='replace')[-500:]
  container=cid.stdout.decode().strip(); created.append((name,container)); run(['docker','start',container],20)
  deadline=time.monotonic()+75; manifest=None
  while time.monotonic()<deadline:
   state=inspect(container)['State']
   read=run(['docker','exec',container,'/usr/bin/cat','/tmp/preview/manifest.properties'],10)
   if read.returncode==0:
    manifest=dict(line.split('=',1) for line in read.stdout.decode().splitlines() if '=' in line); break
   assert state['Running'], 'renderer exited before producing an extraction manifest'
   time.sleep(.4)
  assert manifest is not None, 'renderer did not produce a manifest within 75 seconds'
  assert manifest.get('state')=='READY' and manifest.get('format')=='TEXT' and manifest.get('mediaType')=='text/plain'
  assert manifest.get('file')=='extracted.txt' and manifest.get('sourceReferenced')=='false'
  result=run(['docker','exec',container,'/usr/bin/cat','/tmp/preview/extracted.txt'],10); assert result.returncode==0
  payload=result.stdout; actual=hashlib.sha256(payload).hexdigest(); assert len(payload)==int(manifest['bytes']) and actual==manifest['sha256'] and payload.strip() and b'\x00' not in payload
  run(['docker','stop','--time','5',container],15); state=inspect(container)['State']; row=inspect(container)
  assert not state['Running'] and state['Pid']==0 and not state['OOMKilled'] and row['HostConfig']['Runtime']=='runsc'
  row_result={{'file':filename,'inputSha256':expected,'outputSha256':actual,'outputBytes':len(payload),'seconds':round(time.monotonic()-started,3),
              'containerId':row['Id'],'imageId':row['Image'],'runtime':row['HostConfig']['Runtime'],'oomKilled':state['OOMKilled'],'exitCode':state['ExitCode']}}
  rows.append(row_result); run(['docker','rm',container],15)
 runsc=Path('/usr/local/lib/agentplatform-gvisor/20261005.0/runsc')
 prod=platform_containers()
 expected_r5=EXPECTED_R5
 assert prod==expected_r5, 'R5 production container identities changed during the standalone renderer check'
 assert not [n for n,_ in created if subprocess.run(['docker','inspect',n],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0]
 receipt={{'status':'PASS','scope':'PHYSICAL_GVISOR_TEXT_EXTRACT_ONLY','durableSignedPipelineVerified':False,'localSandboxExpected':False,'memoryLimitMiB':MEMORY_MIB,
          'sourceRevision':REVISION,'imageId':IMAGE,'runscSha256':hashlib.sha256(runsc.read_bytes()).hexdigest(),'runtime':'runsc','cases':rows,
          'memAvailableAfterBytes':mem(),'r5ContainerIdentityUnchanged':True}}
 (ROOT/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n'); print(json.dumps(receipt))
except BaseException as error:
 (ROOT/'failure.private.json').write_text(json.dumps({{'type':type(error).__name__,'message':str(error)}}))
 for name,cid in created:
  current=run(['docker','inspect',cid],10)
  if current.returncode==0:
   state=json.loads(current.stdout)[0]['State']
   if state['Running']: run(['docker','stop','--time','5',cid],15)
   check=run(['docker','inspect',cid],10)
   if check.returncode==0 and not json.loads(check.stdout)[0]['State']['Running']: run(['docker','rm',cid],15)
 raise
'''
    script = script.replace('{{', '{').replace('}}', '}')
    code = (script.replace('ROOT_ARG', repr(str(folder)))
            .replace('IMAGE_ARG', repr(IMAGE)).replace('REVISION_ARG', repr(REVISION))
            .replace('RUNSC_ARG', repr(RUNSC_SHA256)).replace('EXPECTED_ARG', repr(fixture_hashes))
            .replace('MEMORY_ARG', str(TEST_MEMORY_MIB))
            .replace('CASES_ARG', repr(list(CASES))).replace('EXPECTED_R5', repr(production_containers)))
    return remote(code, timeout=360)


def finalize(token):
    if not re.fullmatch(r'[a-f0-9]{12}', token):
        raise ValueError('Invalid protected acceptance receipt token')
    local_folder = DATA / ('cloud-renderer-' + token)
    if not local_folder.is_dir():
        raise RuntimeError('No local fixture directory matches this acceptance token')
    manifest = {name: sha(local_folder / name) for name in CASES}
    remote_folder = '/srv/agentplatform/acceptance/r63-renderer-' + token
    remote_json = native.remote(f'''from pathlib import Path
import json
p=Path({remote_folder!r});receipt=p/'receipt.json';assert p.is_dir() and receipt.is_file()
print(receipt.read_text())
''', timeout=30)
    receipt = json.loads(remote_json)
    assert receipt.get('status') == 'PASS' and receipt.get('scope') == 'PHYSICAL_GVISOR_TEXT_EXTRACT_ONLY'
    assert receipt.get('imageId') == IMAGE and receipt.get('sourceRevision') == REVISION
    assert receipt.get('runtime') == 'runsc' and receipt.get('runscSha256') == RUNSC_SHA256
    assert receipt.get('memoryLimitMiB') == TEST_MEMORY_MIB and receipt.get('r5ContainerIdentityUnchanged') is True
    cases = {row['file']: row for row in receipt.get('cases', [])}
    assert set(cases) == set(CASES)
    for name, digest in manifest.items():
        row = cases[name]
        assert row.get('inputSha256') == digest and row.get('outputBytes', 0) > 0
        assert len(row.get('outputSha256', '')) == 64 and row.get('oomKilled') is False
    archive = local_folder / 'input.tar'
    archive_sha = sha(archive)
    local_receipt = local_folder / 'receipt.json'
    native.local(['scp', *native.SSH, 'root@124.222.55.140:' + remote_folder + '/receipt.json', local_receipt], timeout=90)
    assert json.loads(local_receipt.read_text(encoding='utf-8')) == receipt
    result = {'status': 'PASS', 'remoteRoot': remote_folder, 'archiveSha256': archive_sha,
              'fixtureHashes': manifest, 'receiptSha256': sha(local_receipt), 'receipt': receipt}
    (DATA / 'cloud-renderer-result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print('R63_CLOUD_GVISOR_TEXT_EXTRACTION_PASS; ' + json.dumps({'imageId': IMAGE, 'cases': len(cases), 'receiptSha256': result['receiptSha256']}))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['preflight', 'run', 'finalize'])
    parser.add_argument('--token')
    args = parser.parse_args()
    assert_renderer_inputs_unchanged()
    DATA.mkdir(parents=True, exist_ok=True)
    if args.action == 'finalize':
        if not args.token: raise ValueError('finalize requires --token from the existing fixture directory')
        finalize(args.token); return
    token = hashlib.sha256((str(time.time_ns()) + IMAGE).encode()).hexdigest()[:12]
    folder = '/srv/agentplatform/acceptance/r63-renderer-' + token
    if args.action == 'preflight':
        print(json.dumps(preflight(folder), indent=2)); return
    baseline = preflight(folder)
    if not baseline.get('eligible'):
        raise RuntimeError('Cloud renderer acceptance is safely held by capacity preflight: ' + '; '.join(baseline.get('blockers', [])))
    fixtures = DATA / ('cloud-renderer-' + token); fixtures.mkdir(parents=True, exist_ok=False)
    manifest = {}
    for name in CASES:
        source = (FIXTURE_SOURCE / name).resolve(strict=True)
        if source.is_symlink() or source.stat().st_size > 100 * 1024 * 1024:
            raise RuntimeError('Invalid bounded renderer fixture: ' + name)
        target = fixtures / name; target.write_bytes(source.read_bytes()); manifest[name] = sha(target)
    archive = fixtures / 'input.tar'
    with tarfile.open(archive, 'w') as tar:
        for name in CASES:
            tar.add(fixtures / name, arcname='input/' + name, recursive=False)
    archive_sha = sha(archive)
    folder_arg = folder
    native.remote(f'''from pathlib import Path
import os,shutil
p=Path({folder_arg!r});assert str(p).startswith('/srv/agentplatform/acceptance/r63-renderer-') and not p.exists()
assert shutil.disk_usage('/').free>=5*1024**3
os.umask(0o077);p.mkdir(mode=0o700)
''')
    native.local(['scp', *native.SSH, archive, 'root@124.222.55.140:' + folder + '/input.tar'], timeout=180)
    native.remote(f'''from pathlib import Path
import hashlib,tarfile,shutil,json
p=Path({folder_arg!r});archive=p/'input.tar';h=hashlib.sha256(archive.read_bytes()).hexdigest();assert h=={archive_sha!r}
with tarfile.open(archive) as tar:
 for m in tar.getmembers():assert not m.issym() and not m.islnk() and not Path(m.name).is_absolute() and '..' not in Path(m.name).parts
 tar.extractall(p,filter='data')
for name,digest in {manifest!r}.items():assert hashlib.sha256((p/'input'/name).read_bytes()).hexdigest()==digest
print(json.dumps({{'status':'INPUTS_STAGED','archiveSha256':h,'fixtureHashes':{manifest!r}}}))
''')
    remote_folder = folder
    result = remote_test(remote_folder, manifest, baseline['productionContainers'])
    receipt = result if isinstance(result, dict) else json.loads(result)
    if receipt.get('status') != 'PASS' or not receipt.get('r5ContainerIdentityUnchanged'):
        raise RuntimeError('Cloud physical renderer receipt failed its required assertions')
    local_receipt = fixtures / 'receipt.json'
    native.local(['scp', *native.SSH, 'root@124.222.55.140:' + folder + '/receipt.json', local_receipt], timeout=120)
    downloaded = json.loads(local_receipt.read_text(encoding='utf-8'))
    if downloaded != receipt:
        raise RuntimeError('Cloud and local physical renderer receipts differ')
    (DATA / 'cloud-renderer-result.json').write_text(json.dumps({'status':'PASS','remoteRoot':folder,'archiveSha256':archive_sha,
        'fixtureHashes':manifest,'receiptSha256':sha(local_receipt),'receipt':receipt},indent=2)+'\n',encoding='utf-8')
    print('R63_CLOUD_GVISOR_TEXT_EXTRACTION_PASS; ' + json.dumps({'imageId':IMAGE,'cases':len(receipt['cases']),'receiptSha256':sha(local_receipt)}))


if __name__ == '__main__':
    main()
