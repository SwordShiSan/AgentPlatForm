"""Fixed offline Linux capture probe; no supplier, production or host write mounts."""
from pathlib import Path
import hashlib
import json
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]
IMAGE='python:3.12.15-slim-trixie@sha256:2b4f19dae3a777dfc3b76730bda1e82e1f66ab2a2686fa93ca78edbfb4f04ffe'
INPUTS=['deploy/scripts/file-capture-bridge.py','deploy/scripts/file-stream-bridge.py',
        'deploy/tests/test_file_capture_bridge.py','deploy/scripts/verify-file-capture-protocol.py','deploy/file-helper.Dockerfile']


def manifest():
    return {name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in INPUTS}


def main():
    before=manifest()
    # Inspect the existing pinned platform image; never pull or restart Docker.
    inspect=json.loads(subprocess.check_output(['docker','--context','desktop-linux','image','inspect',IMAGE],text=True))[0]
    if inspect['Os']!='linux' or inspect['Architecture']!='amd64':
        raise SystemExit('R44_LINUX_AMD64_IMAGE_REQUIRED')
    folder=ROOT/'.cache/r44-local'/f'capture-protocol-{time.time_ns()}'
    folder.mkdir(parents=True)
    command=['docker','--context','desktop-linux','run','--rm','--pull','never','--network','none','--read-only',
        '--memory','256m','--memory-swap','256m','--pids-limit','32','--cap-drop','ALL','--security-opt','no-new-privileges',
        '--user','10001:10001','--tmpfs','/tmp:rw,noexec,nosuid,size=128m,uid=10001,gid=10001,mode=700',
        '--mount',f'type=bind,source={ROOT.as_posix()},target=/repo,readonly','--workdir','/repo',
        '--env','PYTHONDONTWRITEBYTECODE=1','--entrypoint','python3',IMAGE,
        '-m','unittest','discover','-s','deploy/tests','-p','test_file_capture_bridge.py','-v']
    started=time.monotonic()
    with (folder/'probe.log').open('wb') as log:
        completed=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=120)
    result=dict(status='PASS' if completed.returncode==0 and before==manifest() else 'FAIL',exitCode=completed.returncode,
        inputUnchanged=before==manifest(),source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        imageReference=IMAGE,imageLocalIdentity=inspect['Id'],inputs=before,supplierCalls=0,
        scope='Linux helper framing/path/type/hash/100MiB bounded-stream experiment; no native XFS, signed job, S3 or version-sealing claim',
        seconds=round(time.monotonic()-started,2),logSha256=hashlib.sha256((folder/'probe.log').read_bytes()).hexdigest())
    (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(folder);print(json.dumps(result))
    print((folder/'probe.log').read_text(encoding='utf-8'))
    return 0 if result['status']=='PASS' else 1


if __name__=='__main__':
    raise SystemExit(main())
