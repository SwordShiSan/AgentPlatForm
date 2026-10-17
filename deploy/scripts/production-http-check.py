"""HTTP routing and actual container exposure; no DNS or TLS claim."""
import json,pathlib,subprocess,urllib.request,urllib.error
root=pathlib.Path('/srv/agentplatform')
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def get(path,host):
    try:
        with opener.open(urllib.request.Request('http://127.0.0.1'+path,headers={'Host':host}),timeout=10) as r:return r.status,r.read(),r.headers
    except urllib.error.HTTPError as e:return e.code,e.read(),e.headers
for host in ['agentplatform.lailihao.cloud','124.222.55.140']:
    for path,status in [('/api/v1/health','UP'),('/api/v1/readiness','READY')]:
        code,body,headers=get(path,host);assert code==200 and json.loads(body)=={'status':status} and 'Location' not in headers
    code,body,headers=get('/',host);assert code==200 and b'<html' in body and 'Location' not in headers
assert get('/','unexpected.example')[0]==404
ids=[]
for project in ['agentplatform-app','agentplatform-env']:
    ids.extend(subprocess.check_output(['docker','ps','-q','--filter','label=com.docker.compose.project='+project],text=True).split())
expected=10 if (root/'deploy/r1-enabled').is_file() else 12
rows=json.loads(subprocess.check_output(['docker','inspect',*ids]));assert len(rows)==expected
for item in rows:
    assert item['State']['Running'] and not item['State']['OOMKilled']
    assert item['HostConfig']['RestartPolicy']=={'Name':'on-failure','MaximumRetryCount':3}
    bindings=item['HostConfig'].get('PortBindings') or {}
    if item['Name']=='/agentplatform-app-gateway-1':
        assert set(bindings)=={'8080/tcp'} and len(bindings['8080/tcp'])==1
        assert bindings['8080/tcp'][0]=={'HostIp':'0.0.0.0','HostPort':'80'}
    else:assert not bindings
for name in ['nginx','ai-agent-app','big-market-app','mysql','redis','rabbitmq','zookeeper','ai-agent-pgvector','ai-agent_big-market-front-app','ai-agent-xxl-job-admin']:
    item=json.loads(subprocess.check_output(['docker','inspect',name]))[0]
    assert not item['State']['Running'] and item['HostConfig']['RestartPolicy']['Name']=='no'
result={'status':'PASS','profiles':expected,'httpPort':80,'domainAndIpHostRouting':True,'unknownHost':404,
        'legacyRetainedStopped':True,'allInternalPortsUnpublished':True,'boundedRetries':3,'dnsTls':'OUT_OF_SCOPE'}
(root/'reports/production-http.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
