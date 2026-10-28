"""Pinned R4.4 frontend gates with an explicit working directory and rename preflight."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]
FRONT=ROOT/'frontend'
NODE=ROOT/'.tools/node-v22.23.3-win-x64/node.exe'


def inputs():
    paths=subprocess.check_output(['git','ls-files','-c','-o','--exclude-standard','-z','--','frontend','contracts',
                                  'deploy/scripts/verify-workspace-tools-frontend.py'],cwd=ROOT).decode().split('\0')
    return {name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in sorted(set(paths)-{''}) if (ROOT/name).is_file()}


def main():
    if not NODE.is_file() or not (FRONT/'node_modules/vue-tsc/bin/vue-tsc.js').is_file():
        raise SystemExit('R44_PINNED_FRONTEND_MISSING: reuse stage setup, do not install global tools')
    folder=ROOT/'.cache/r44-local'/f'frontend-verify-{time.time_ns()}'
    temporary=folder/'tmp';temporary.mkdir(parents=True)
    env=os.environ.copy();env.update(TEMP=str(temporary),TMP=str(temporary),TMPDIR=str(temporary))
    before=inputs();gates=[]
    commands=[('rename-preflight',[str(NODE),'-e',
        "const f=require('node:fs'),p=require('node:path'),o=require('node:os');const a=p.join(o.tmpdir(),'rename-before');const b=p.join(o.tmpdir(),'rename-after');f.writeFileSync(a,'probe');f.renameSync(a,b);f.unlinkSync(b);"]),
        ('typecheck',[str(NODE),'node_modules/vue-tsc/bin/vue-tsc.js','--noEmit']),
        ('unit',[str(NODE),'node_modules/vitest/vitest.mjs','run']),
        ('build',[str(NODE),'node_modules/vite/bin/vite.js','build']),
        ('contracts',[str(NODE),'scripts/contracts.mjs','--check'])]
    for name,command in commands:
        log=folder/f'{name}.log'
        with log.open('wb') as target:
            process=subprocess.run(command,cwd=FRONT,env=env,stdout=target,stderr=subprocess.STDOUT,timeout=300)
        gates.append(dict(name=name,exitCode=process.returncode,logSha256=hashlib.sha256(log.read_bytes()).hexdigest()))
        if process.returncode:
            print(log.read_bytes().decode('utf-8',errors='replace')[-6000:]);break
    unchanged=before==inputs()
    result=dict(status='PASS' if len(gates)==len(commands) and all(x['exitCode']==0 for x in gates) and unchanged else 'FAIL',
                source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),inputUnchanged=unchanged,
                inputManifestSha256=hashlib.sha256(json.dumps(before,sort_keys=True).encode()).hexdigest(),inputs=before,gates=gates,
                scope='Frontend type/unit/build/contracts only; not actual API/browser acceptance',supplierCalls=0)
    (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(folder);print(json.dumps({k:v for k,v in result.items() if k!='inputs'}))
    return 0 if result['status']=='PASS' else 1


if __name__=='__main__':
    raise SystemExit(main())
