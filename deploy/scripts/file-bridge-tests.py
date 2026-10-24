"""Linux-only file bridge unit tests in a pinned, unprivileged disposable container.

This verifies filesystem helper logic; it does not claim XFS, quota or production runner acceptance.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid

ROOT=Path(__file__).resolve().parents[2]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();output=args.output.resolve()
    if not output.is_relative_to(ROOT/'.cache') or output.exists():parser.error('Use a new .cache evidence directory')
    image=(ROOT/'deploy/file-helper.Dockerfile').read_text(encoding='utf-8').splitlines()[0].split()[1]
    if not re.fullmatch(r'python:[a-zA-Z0-9.\-]+@sha256:[0-9a-f]{64}',image):parser.error('Pinned official Python image required')
    output.mkdir(parents=True)
    run_id=str(uuid.uuid4());name='ap-file-bridge-tests-'+run_id
    command=['docker','run','--rm','--name',name,'--platform','linux/amd64','--network','none','--read-only',
             '--user','10001:10001','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','64m','--memory-swap','64m',
             '--cpus','1','--pids-limit','32','--label','ap.fixture.run='+run_id,
             '--tmpfs','/tmp:rw,nosuid,noexec,size=16m,uid=10001,gid=10001',
             '--mount','type=bind,src='+str(ROOT/'deploy/scripts')+',dst=/source/scripts,readonly',
             '--mount','type=bind,src='+str(ROOT/'deploy/tests')+',dst=/source/tests,readonly',
             '--env','PYTHONDONTWRITEBYTECODE=1','--entrypoint','python3',image,
             '-m','unittest','discover','-s','/source/tests','-p','test_file_*bridge.py','-v']
    log=output/'tests.log';code=1
    try:
        with log.open('xb') as stream:
            code=subprocess.run(command,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,timeout=90).returncode
    except subprocess.TimeoutExpired:
        # Only this generated, labelled fixture can be stopped. No Docker socket is mounted in it.
        inspected=subprocess.run(['docker','inspect',name],capture_output=True,text=True,timeout=10)
        if inspected.returncode==0:
            row=json.loads(inspected.stdout)[0]
            if row['Config'].get('Labels',{}).get('ap.fixture.run')==run_id:
                subprocess.run(['docker','stop','--time','5',row['Id']],capture_output=True,timeout=15)
        code=124
    finally:
        result={'status':'PASS' if code==0 else 'FAIL','exitCode':code,'sourceBaseImage':image,
                'logSha256':hashlib.sha256(log.read_bytes()).hexdigest(),'scope':'Linux helper unit logic only; no XFS/runner claim'}
        (output/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(result))
    return code


if __name__=='__main__':raise SystemExit(main())
