"""Build or replace only the gateway of the explicitly isolated R52 acceptance stack."""
from pathlib import Path
import argparse,importlib.util,json,os,subprocess,time
ROOT=Path(__file__).resolve().parents[2]
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['build','apply']);args=p.parse_args()
 s=importlib.util.spec_from_file_location('native',ROOT/'deploy/scripts/files-native-fixture.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.configure('r52');data=m.DATA
 if args.action=='build':
  assert not m.local(['git','status','--porcelain']),'Commit source before building'
  source=m.local(['git','rev-parse','HEAD']);tag='agentplatform-gateway:r52-'+source[:12];out=data/('gateway-final-'+str(time.time_ns()))
  env=dict(os.environ,**{k:'true' for k in ['VITE_CHAT_ENABLED','VITE_PLAN_ENABLED','VITE_PERMISSIONS_ENABLED','VITE_WORKSPACE_ENABLED','VITE_WORKSPACE_FILES_ENABLED','VITE_WORKSPACE_TOOLS_ENABLED']})
  result=subprocess.run([str(ROOT/'.tools/node-v22.23.3-win-x64/node.exe'),str(ROOT/'frontend/node_modules/vite/bin/vite.js'),'build'],cwd=ROOT/'frontend',env=env,capture_output=True);assert result.returncode==0
  m.local(['docker','--context','desktop-linux','build','--platform','linux/amd64','--network=none','--pull=false','--build-arg','SOURCE_REVISION='+source,'--build-arg','NGINX_CONFIG=deploy/nginx.files-local.conf','-f','deploy/gateway.Dockerfile','-t',tag,'.'])
  m.local([os.sys.executable,ROOT/'deploy/scripts/export-project-image.py','--tag',tag,'--component','gateway','--output',out])
  (data/'gateway-final-pointer.json').write_text(json.dumps({'folder':str(out)}));print('R52_GATEWAY_EXPORTED');return
 pointer=json.loads((data/'gateway-final-pointer.json').read_text());image=json.loads((Path(pointer['folder'])/'image.json').read_text());assert m.sha(Path(image['archive']))==image['archiveSha256']
 plan=json.loads((data/'native-plan.json').read_text());cloud=plan['root'];assert cloud.startswith('/srv/agentplatform/acceptance/r44-r52-')
 m.local(['scp',*m.SSH,image['archive'],'root@124.222.55.140:'+cloud+'/gateway-final.tar.gz'],timeout=120)
 code='''from pathlib import Path
import json,hashlib,subprocess,time,urllib.request
r=Path(ROOT);image=IMAGE;c=json.loads((r/'compose.json').read_text());assert c['name']=='agentplatform-r52-local'
assert not (r/'finish-request.json').exists() and (r/'reports/ready.json').is_file()
assert hashlib.file_digest((r/'gateway-final.tar.gz').open('rb'),'sha256').hexdigest()==image['archiveSha256']
base=['docker','compose','-p',c['name'],'-f',str(r/'compose.json')]
before={n:subprocess.check_output(base+['ps','-q',n],text=True).strip() for n in ['api','worker','runner']};assert all(before.values())
subprocess.run(['docker','load','-i',str(r/'gateway-final.tar.gz')],check=True,capture_output=True)
assert json.loads(subprocess.check_output(['docker','image','inspect',image['configId']]))[0]['Config']['Labels']['org.opencontainers.image.revision']==image['source']
c['services']['gateway']['image']=image['configId'];(r/'compose.json').write_text(json.dumps(c,indent=2));subprocess.run(base+['up','-d','--no-deps','--no-build','gateway'],check=True,capture_output=True)
assert all(subprocess.check_output(base+['ps','-q',n],text=True).strip()==v for n,v in before.items())
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
for _ in range(20):
 try:
  with client.open('http://127.0.0.1:18152/api/v1/readiness',timeout=3) as response:assert json.load(response)=={'status':'READY'}
  break
 except Exception:time.sleep(1)
else:raise RuntimeError('Gateway not ready')
ready=json.loads((r/'reports/ready.json').read_text());ready.update(gatewaySource=image['source'],gatewayConfigId=image['configId']);(r/'reports/ready.json').write_text(json.dumps(ready))
print('R52_GATEWAY_UPDATED_BACKEND_IDENTITIES_UNCHANGED')
'''.replace('ROOT',repr(cloud)).replace('IMAGE',repr(image))
 print(m.remote(code,timeout=120));plan['gateway']=image;(data/'native-plan.json').write_text(json.dumps(plan,indent=2));(data/'gateway.json').write_text(json.dumps(image,indent=2))
if __name__=='__main__':main()
