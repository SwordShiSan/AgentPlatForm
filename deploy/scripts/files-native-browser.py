"""One fresh-output browser controller for the exact prepared file acceptance stage."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, time
ROOT=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--stage',choices=['r54'],required=True);a=p.parse_args()
    data=ROOT/('.cache/'+a.stage+'-local');folder=data/('browser-'+str(time.time_ns()));folder.mkdir(parents=True)
    temporary=folder/'tmp';temporary.mkdir();node=ROOT/'.tools/node-v22.23.3-win-x64/node.exe'
    env=dict(os.environ,R54_NATIVE='1',BASE_URL='http://agentplatform.lailihao.cloud')
    command=[str(node),'node_modules/@playwright/test/cli.js','test','tests/e2e/workbench-native.spec.ts','--project','chromium','--reporter=line','--output',str(folder/'results')]
    log=folder/'browser.log'
    with log.open('wb') as target:result=subprocess.run(command,cwd=ROOT/'frontend',env=env,stdout=target,stderr=subprocess.STDOUT,timeout=300)
    plan=json.loads((data/'native-plan.json').read_text())
    receipt={'status':'PASS' if result.returncode==0 else 'FAIL','exitCode':result.returncode,'source':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
             'gatewayConfigId':plan['gateway']['configId'],'gatewaySource':plan['gateway']['candidate']['images']['gateway']['source'],'applicationSource':plan['source'],
             'logSha256':hashlib.sha256(log.read_bytes()).hexdigest(),'supplierCalls':0,'scope':'Real browser only; other release gates remain separate'}
    (folder/'result.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8');print(folder);print(json.dumps(receipt))
    if result.returncode:print(log.read_text(encoding='utf-8',errors='replace')[-4500:]);raise SystemExit(result.returncode)

if __name__=='__main__':main()
