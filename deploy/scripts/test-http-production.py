"""Actual Docker/HTTP checks for the immutable gateway with production configuration."""
import json,os,pathlib,runpy,subprocess,time,urllib.request,urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
local=runpy.run_path(str(root/'deploy/scripts/local-acceptance.py'))
env=dict(local['ENV'],HTTP_BIND='127.0.0.1',HTTP_PORT='18085',PRODUCTION_NGINX_CONFIG=(root/'deploy/nginx.production.conf').as_posix())
def run(args):return subprocess.check_output(args,cwd=root,env=env,text=True)
app=json.loads(run(['docker','compose','-p','agentplatform-r0-release-config','-f','deploy/compose.app.yaml','-f','deploy/compose.production.app.yaml','--profile','migration','--profile','worker','--profile','runner','--profile','egress','config','--format','json']))
services=app['services'];ports=services['gateway']['ports']
assert len(ports)==1 and str(ports[0]['published'])=='18085' and ports[0]['target']==8080
assert services['migrate']['restart']=='no'
for name in ['api','worker','runner','egress','gateway']:assert services[name]['restart']=='on-failure:3'
for name in ['api','worker','runner','egress','migrate']:assert not services[name].get('ports')
environment=json.loads(run(['docker','compose','-p','agentplatform-r0-release-config-env','-f','deploy/compose.env.yaml','-f','deploy/compose.production.env.yaml','config','--format','json']))
assert all(not service.get('ports') and service['restart']=='on-failure:3' for service in environment['services'].values())
name='agentplatform-r0-http-local'
probe=subprocess.run(['docker','inspect',name],capture_output=True,text=True)
if probe.returncode==0:
    assert json.loads(probe.stdout)[0]['Config']['Labels'].get('release-fixture')=='r0-http'
    run(['docker','rm','-f',name])
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(path,host):
    req=urllib.request.Request('http://127.0.0.1:18085'+path,headers={'Host':host})
    try:
        with opener.open(req,timeout=10) as response:return response.status,response.read(),response.headers
    except urllib.error.HTTPError as error:return error.code,error.read(),error.headers
try:
    run(['docker','run','-d','--name',name,'--label','release-fixture=r0-http','--network',env['PLATFORM_INGRESS'],
         '--publish','127.0.0.1:18085:8080','--user','101:101','--read-only','--cap-drop','ALL',
         '--security-opt','no-new-privileges','--memory','32m','--pids-limit','64','--tmpfs','/tmp:rw,noexec,nosuid,size=16m',
         '--mount','type=bind,src='+env['PRODUCTION_NGINX_CONFIG']+',dst=/etc/nginx/nginx.conf,readonly',env['PLATFORM_GATEWAY_IMAGE']])
    run(['docker','network','connect',local['NETWORK'],name])
    for attempt in range(30):
        try:status,_,_=request('/api/v1/health','agentplatform.lailihao.cloud')
        except Exception:time.sleep(1);continue
        if status==200:break
        time.sleep(1)
    rows=[]
    for host in ['agentplatform.lailihao.cloud','124.222.55.140']:
        for path,key,value in [('/api/v1/health','status','UP'),('/api/v1/readiness','status','READY')]:
            status,body,headers=request(path,host)
            assert status==200 and json.loads(body)=={key:value} and 'Location' not in headers
        status,body,headers=request('/',host);assert status==200 and b'<html' in body and 'Location' not in headers
        rows.append({'host':host,'httpShellHealthReadiness':'PASS'})
    assert request('/','unexpected.example')[0]==404
    status,body,_=request('/api/v1/missing','agentplatform.lailihao.cloud')
    assert status==404 and 'traceId' in json.loads(body)
    identity=json.loads(run(['docker','inspect',name]))[0]
    assert all(not mount['RW'] for mount in identity['Mounts'] if mount['Destination']=='/etc/nginx/nginx.conf')
    result={'status':'PASS','hostRouting':rows,'unknownHost':404,'apiProblem':404,'singlePublicPort':True,
            'internalServicesUnpublished':True,'boundedRetries':3,'migrationRestart':'no','dnsTls':'OUT_OF_SCOPE'}
    out=root/'evidence/r0-release';out.mkdir(parents=True,exist_ok=True)
    (out/'local-http-production.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))
finally:
    logs=subprocess.run(['docker','logs',name],capture_output=True,text=True)
    (root/'evidence/r0-release/local-http-gateway.log').write_text(logs.stdout+logs.stderr,encoding='utf-8')
    run(['docker','rm','-f',name])
