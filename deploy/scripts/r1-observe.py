"""At least30min of HTTP cloud observation; explicit bound reuse avoids repeated real calls."""
import argparse,hashlib,http.cookiejar,json,os,shutil,subprocess,time,urllib.error,urllib.request,uuid
from pathlib import Path

def reused_model(path,root,request):
    path=path.resolve();assert path.is_relative_to(root/'operations') and path.name=='prior-observation.json'
    prior=json.loads(path.read_text());assert prior['status']=='PASS' and prior['durationSeconds']>=1800
    model=prior['model'];assert model['status']=='PASS' and model['model']=='deepseek-v4-pro'
    task=str(uuid.UUID(model['taskId']))
    code,current=request('GET','/tasks/'+task);assert code==200 and current['status']=='SUCCEEDED'
    code,usage=request('GET','/tasks/'+task+'/usage');assert code==200 and usage['items']==model['usage']
    assert usage['items'] and all(item['status']=='COMPLETED' and item['inputTokens'] is not None and item['outputTokens'] is not None for item in usage['items'])
    code,config=request('GET','/tasks/'+task+'/configuration');assert code==200 and config['values']['modelName']==model['model']
    return model,{'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'taskId':task,'persistedUsageMatches':True,'newProviderCalls':0}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--seconds',type=int,default=1800)
    parser.add_argument('--reuse-model-evidence',type=Path);args=parser.parse_args()
    assert args.seconds>=1800,'A shortened window is not release evidence'
    root=Path('/srv/agentplatform');assert (root/'deploy/r1-enabled').is_file()
    private=root/'operations/r1-accounts';os.umask(0o077)
    account=next(a for a in json.loads((private/'accounts.json').read_text())['accounts'] if a['username']=='r1acceptance')
    jar=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(jar))
    origin='http://124.222.55.140';csrf=None
    def request(method,path,body=None,key=None):
        headers={'Host':'124.222.55.140','Accept':'application/json'}
        data=None
        if body is not None:headers['Content-Type']='application/json';data=json.dumps(body).encode()
        if method!='GET':headers.update(Origin=origin,**{'X-CSRF-Token':csrf})
        if key:headers['Idempotency-Key']=key
        try:
            with opener.open(urllib.request.Request('http://127.0.0.1/api/v1'+path,data=data,method=method,headers=headers),timeout=12) as response:
                raw=response.read();return response.status,json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw=error.read();return error.code,json.loads(raw) if raw else None
    code,body=request('GET','/auth/csrf');assert code==200;csrf=body['csrfToken']
    code,body=request('POST','/auth/login',{'username':account['username'],'password':account['password']});assert code==200;csrf=body['csrfToken']
    samples=[];events=[];start=time.monotonic();injected=set();model_result=None;reused=None
    if args.reuse_model_evidence:
        model_result,reused=reused_model(args.reuse_model_evidence,root,request)
    expected=['agentplatform-app-'+s+'-1' for s in ['api','worker','gateway']]+['agentplatform-env-'+s+'-1' for s in ['postgres','rabbitmq','redis','master','volume','filer','s3']]
    def snapshot():
        rows=json.loads(subprocess.check_output(['docker','inspect',*expected]));assert len(rows)==10
        assert all(row['State']['Running'] and not row['State']['OOMKilled'] for row in rows)
        assert all(row['RestartCount']==0 for row in rows),'Unexpected automatic container restart'
        assert sum(row['HostConfig']['Memory'] for row in rows)<=2887*1024**2
        assert all(row['HostConfig']['Memory']==row['HostConfig']['MemorySwap'] for row in rows)
        mem={line.split(':')[0]:int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemAvailable:','SwapTotal:','SwapFree:'))}
        assert mem['MemAvailable']>=768*1024,'Host available memory below admission reserve'
        disk=shutil.disk_usage(root);assert disk.free>=5*1024**3
        code,body=request('GET','/readiness');assert code==200 and body=={'status':'READY'}
        code,_=request('GET','/me');assert code==200
        if model_result is not None:
            assert request('GET','/tasks/'+model_result['taskId'])[1]['status']=='SUCCEEDED'
        result=subprocess.run(['docker','exec','agentplatform-env-postgres-1','psql','-XAt','-U','ap_admin','-d','agentplatform','-c',"SELECT count(*) FILTER(WHERE status='RUNNING') FROM pf.task;"],capture_output=True,text=True,check=True)
        assert int(result.stdout.strip())<=1
        return {'elapsedSeconds':round(time.monotonic()-start,2),'memAvailableKiB':mem['MemAvailable'],'swapUsedKiB':mem['SwapTotal']-mem['SwapFree'],'rootFreeBytes':disk.free,'runningTasks':int(result.stdout.strip()),'allContainersLive':True}
    def restart_dependency(name):
        container='agentplatform-env-'+name+'-1'
        subprocess.run(['docker','stop','-t','20',container],check=True,capture_output=True)
        try:
            code,_=request('GET','/readiness');assert code==503
            if name=='postgres':assert request('GET','/me')[0]==503
        finally:subprocess.run(['docker','start',container],check=True,capture_output=True)
        deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            if request('GET','/readiness')[0]==200:
                state=json.loads(subprocess.check_output(['docker','inspect',container]))[0]
                if state['State'].get('Health',{}).get('Status')=='healthy':break
            time.sleep(2)
        else:raise RuntimeError('Dependency readiness did not recover')
        events.append({'dependency':name,'elapsedSeconds':round(time.monotonic()-start,2),'failureReadiness':503,'recovered':True})
    def cold_master(index):
        # Resource-only validation uses the same official image and persisted data.
        # A forced recreation is recorded; automatic restarts remain a failure.
        command=['docker','compose','--env-file',str(root/'deploy/images.env'),'-p','agentplatform-env',
            '-f',str(root/'deploy/compose.env.yaml'),'-f',str(root/'deploy/compose.production.env.yaml'),
            '-f',str(root/'deploy/compose.r1-http.env.yaml'),'up','-d','--force-recreate','--no-deps','--wait','--wait-timeout','180','master']
        subprocess.run(command,env=dict(os.environ,PLATFORM_ROOT=str(root)),capture_output=True,check=True)
        row=json.loads(subprocess.check_output(['docker','inspect','agentplatform-env-master-1']))[0]
        assert row['State']['Health']['Status']=='healthy' and not row['State']['OOMKilled'] and row['RestartCount']==0
        events.append({'dependency':'master','coldRecreation':index,'elapsedSeconds':round(time.monotonic()-start,2),'containerId':row['Id'],'restartCount':0,'leaderHealthy':True})
    while time.monotonic()-start<args.seconds:
        elapsed=time.monotonic()-start
        for name,threshold in [('rabbitmq',120),('postgres',240)]:
            if elapsed>=threshold and name not in injected:restart_dependency(name);injected.add(name)
        if reused:
            for index,threshold in enumerate([600,900,1200],1):
                key='master-'+str(index)
                if elapsed>=threshold and key not in injected:cold_master(index);injected.add(key)
        if elapsed>=900 and model_result is None:
            key=str(uuid.uuid4());body={'title':'R1观察验收','initialTask':{'input':{'text':'只回复：观察验证通过','attachments':[]},'options':{'modelId':'0199e000-0000-7000-8000-000000000003','credentialSource':'PLATFORM','planMode':False,'permissionMode':'RISK_BASED'}}}
            (private/'observation-submission.json').write_text(json.dumps({'key':key,'body':body}))
            code,value=request('POST','/conversations',body,key);assert code==201,'Observation submission failed; do not replay blindly'
            task=value['initialTask']['taskId'];deadline=time.monotonic()+130
            while time.monotonic()<deadline:
                code,current=request('GET','/tasks/'+task);assert code==200
                if current['status'] not in ['RUNNING','QUEUED']:break
                time.sleep(1)
            assert current['status']=='SUCCEEDED','Model acceptance failed or provider quota reached; stop calls'
            code,usage=request('GET','/tasks/'+task+'/usage');assert code==200 and 1<=len(usage['items'])<=4
            assert usage['items'][-1]['inputTokens'] is not None
            model_result={'taskId':task,'status':'PASS','model':'deepseek-v4-pro','usage':usage['items'],'physicalCalls':len(usage['items'])}
        samples.append(snapshot())
        (root/'reports/r1-observation-progress.json').write_text(json.dumps({'status':'RUNNING','samples':len(samples),'elapsedSeconds':round(time.monotonic()-start,2),'dependencyRestarts':events}))
        time.sleep(min(10,max(0,args.seconds-(time.monotonic()-start))))
    samples.append(snapshot());expected_injections={'rabbitmq','postgres'}|({'master-1','master-2','master-3'} if reused else set())
    assert model_result is not None and injected==expected_injections
    result={'status':'PASS','durationSeconds':round(time.monotonic()-start,2),'samples':samples,'dependencyRestarts':events,'model':model_result,
        'sampledMinimumAvailableKiB':min(s['memAvailableKiB'] for s in samples),'swapStartKiB':samples[0]['swapUsedKiB'],'swapEndKiB':samples[-1]['swapUsedKiB'],
        'sampledRootGrowthBytes':samples[0]['rootFreeBytes']-samples[-1]['rootFreeBytes'],'reusedModelEvidence':reused,
        'newProviderCalls':0 if reused else model_result['physicalCalls'],
        'scope':'Sampled values and live-state checks, not absolute peaks, exhaustive event delivery or higher concurrency capacity.'}
    (root/'reports/r1-observation.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='samples'}))

if __name__=='__main__':main()
