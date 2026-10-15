"""Check original legacy identities/policies and actual HTTP contracts, without secrets."""
import hashlib,json,pathlib,subprocess,sys,time,urllib.request
root=pathlib.Path('/srv/agentplatform')
policy=pathlib.Path(sys.argv[2]) if len(sys.argv)>2 else root/'operations/restore-policies.txt'
assert policy.is_relative_to(root/'operations')
expected=[line.split('|') for line in policy.read_text().splitlines()]
names=[row[0].lstrip('/') for row in expected]
actual={item['Name']:item for item in json.loads(subprocess.check_output(['docker','inspect',*names]))}
for name,identity,image,restart,running in expected:
    item=actual[name]
    assert item['Id']==identity and item['State']['Running']==(running=='true')
    assert item['HostConfig']['RestartPolicy']['Name']==restart
backend=actual['/ai-agent-app']
host='127.0.0.1' if backend['HostConfig']['NetworkMode']=='host' else next(v['IPAddress'] for v in backend['NetworkSettings']['Networks'].values() if v['IPAddress'])
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
for attempt in range(120):
    try:
        with opener.open('http://'+host+':8091/api/v1/platform/readiness',timeout=10) as response:
            raw=response.read();assert response.status==200 and json.loads(raw)['data']['ready'] is True
        break
    except Exception:time.sleep(2)
else:raise AssertionError('Legacy actual readiness failed')
request=urllib.request.Request('http://127.0.0.1/',headers={'Host':'agent.lailihao.cloud'})
with opener.open(request,timeout=10) as response:
    assert response.status==200;entry=response.read()
record={'status':'PASS','containers':len(expected),'originalIdentitiesPoliciesRunningState':True,
        'backendReadiness':200,'backendReady':True,'entry':200,
        'backendSha256':hashlib.sha256(raw).hexdigest(),'entrySha256':hashlib.sha256(entry).hexdigest()}
if len(sys.argv)>1:pathlib.Path(sys.argv[1]).write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps(record))
