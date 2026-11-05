"""One local paginated-tree browser fixture; no real API, cloud change or model call."""
from pathlib import Path
import hashlib,json,os,socket,subprocess,time,urllib.request
ROOT=Path(__file__).resolve().parents[2]


def main():
    folder=ROOT/'.cache/r54-local'/('ui-contract-'+str(time.time_ns()));folder.mkdir(parents=True)
    node=ROOT/'.tools/node-v22.23.3-win-x64/node.exe'
    with socket.socket() as probe:probe.bind(('127.0.0.1',18155))
    env=dict(os.environ,BASE_URL='http://127.0.0.1:18155')
    for flag in ['CHAT','PLAN','PERMISSIONS','WORKSPACE','WORKSPACE_FILES','WORKSPACE_TOOLS','ATTACHMENTS']:env['VITE_'+flag+'_ENABLED']='true'
    with (folder/'vite.log').open('wb') as output:
        server=subprocess.Popen([str(node),'node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','18155','--strictPort'],cwd=ROOT/'frontend',env=env,stdout=output,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        try:
            client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for _ in range(40):
                assert server.poll() is None,'Original frontend server exited'
                try:
                    with client.open(env['BASE_URL'],timeout=1) as response:
                        if response.status==200:break
                except OSError:pass
                time.sleep(.5)
            else:raise RuntimeError('Frontend fixture server not ready')
            with (folder/'browser.log').open('wb') as log:
                result=subprocess.run([str(node),'node_modules/@playwright/test/cli.js','test','tests/e2e/workbench-pagination-ui.spec.ts','--project','chromium','--reporter=line','--output',str(folder/'results')],cwd=ROOT/'frontend',env=env,stdout=log,stderr=subprocess.STDOUT,timeout=90)
            receipt={'status':'PASS' if result.returncode==0 else 'FAIL','exitCode':result.returncode,'source':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'scope':'10k logical directory browser fixture only; no physical file or backend claim','supplierCalls':0,'logSha256':hashlib.sha256((folder/'browser.log').read_bytes()).hexdigest()}
            (folder/'result.json').write_text(json.dumps(receipt,indent=2));print(folder);print(json.dumps(receipt))
            if result.returncode:print((folder/'browser.log').read_text(encoding='utf-8',errors='replace')[-3000:]);raise SystemExit(result.returncode)
        finally:
            if server.poll() is None:server.terminate();server.wait(timeout=15)


if __name__=='__main__':main()
