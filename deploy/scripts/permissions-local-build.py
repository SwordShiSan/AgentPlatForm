"""Build the exact clean R4.2 source locally; never build or deploy on the cloud host."""
from pathlib import Path
import hashlib, json, os, subprocess, time

ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'.cache/r42-local';out=DATA/('build-'+str(time.time_ns()));out.mkdir(parents=True,exist_ok=False)
sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip(),'Build requires clean tracked source'
java=ROOT/'.tools/jdk-21.0.12.1/jdk-21.0.12.1+1';node=ROOT/'.tools/node-v22.23.3-win-x64/node.exe'
env=dict(os.environ,JAVA_HOME=str(java),PATH=str(java/'bin')+os.pathsep+str(node.parent)+os.pathsep+os.environ['PATH'],
         VITE_CHAT_ENABLED='true',VITE_PLAN_ENABLED='true',VITE_PERMISSIONS_ENABLED='true',VITE_WORKSPACE_ENABLED='false')
records=[]
def run(name,args,cwd=ROOT):
    log=out/(name+'.log');start=time.monotonic()
    with log.open('xb') as stream:result=subprocess.run([str(x) for x in args],cwd=cwd,env=env,stdout=stream,stderr=subprocess.STDOUT)
    records.append({'name':name,'exitCode':result.returncode,'seconds':round(time.monotonic()-start,2),'logSha256':hashlib.sha256(log.read_bytes()).hexdigest()})
    (out/'commands.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf-8',newline='\n')
    if result.returncode:raise RuntimeError(name+' failed; inspect protected build log')
    print(name+' PASS',flush=True)
run('maven-package',[ROOT/'backend/mvnw.cmd','-B','-ntp','-f',ROOT/'backend/pom.xml','-DskipTests','package'])
run('frontend-build',[node,ROOT/'frontend/node_modules/vite/bin/vite.js','build'],ROOT/'frontend')
images={}
for component in ['api','gateway','provider-fixture']:
    tag='agentplatform-'+component+':r42-'+sha[:12]
    args=['docker','build','--platform','linux/amd64','--pull=false','--build-arg','SOURCE_REVISION='+sha,'-f','deploy/'+component+'.Dockerfile','-t',tag]
    if component=='gateway':args+=['--build-arg','NGINX_CONFIG=deploy/nginx.chat-local.conf']
    run(component+'-image',args+['.'])
    image=json.loads(subprocess.check_output(['docker','image','inspect',tag],text=True))[0]
    assert image['Architecture']=='amd64' and image['Os']=='linux'
    assert image['Config']['Labels']['org.opencontainers.image.revision']==sha
    images[component]={'tag':tag,'configId':image['Id'],'source':sha,'architecture':'linux/amd64'}
record={'status':'PASS','source':sha,'images':images,'jarSha256':hashlib.sha256((ROOT/'backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar').read_bytes()).hexdigest(),'commands':records}
(out/'result.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8',newline='\n');(DATA/'images.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8',newline='\n')
print('R42_LOCAL_IMAGES_BOUND_TO_SOURCE '+sha,flush=True)
