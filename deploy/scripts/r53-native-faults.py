"""One bounded R53 broker outage and original-message duplicate, never production operations.

Requires the already running independent R53 window and SSH API tunnel. Uses the
existing Spring outbox publisher for duplicate delivery; no additional MQ client.
"""
from pathlib import Path
import argparse, hashlib, importlib.util, json, re, time, uuid

ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'.cache/r53-local'


def load(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'deploy/scripts'/file)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value)
    return value


preview=load('r53_preview_fault_helpers','r53-native-acceptance.py')
api=preview.api
native=load('r53_preview_fault_native','files-native-fixture.py');native.configure('r53')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['run','status'],nargs='?',default='run');args=parser.parse_args()
    result_file=DATA/'native-preview-faults-result.json'
    if args.action=='status':
        print(result_file.read_text() if result_file.exists() else json.dumps({'status':'NOT_RUN'}));return
    assert not result_file.exists(),'Original fault receipt exists; inspect it, do not blindly repeat the fault'
    plan=json.loads((DATA/'native-plan.json').read_text());root=plan['root']
    assert plan['project']=='agentplatform-r53-local' and re.fullmatch('/srv/agentplatform/acceptance/r44-r53-[0-9]{14}',root)
    assert api.BASE=='http://127.0.0.1:18153'
    prefix='''from pathlib import Path
import importlib.util,json,subprocess,time,re,uuid,base64,ipaddress,urllib.request,urllib.parse
r=Path(ROOT_VALUE)
assert re.fullmatch('/srv/agentplatform/acceptance/r44-r53-[0-9]{14}',str(r))
s=importlib.util.spec_from_file_location('r53_window_fault',r/'files-native-window.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
assert m.PLAN['root']==str(r) and m.PLAN['project']=='agentplatform-r53-local' and m.PLAN.get('schema')==20
c=json.loads((r/'compose.json').read_text());assert c['name']=='agentplatform-r53-local'
def exact(service,identity=None):
 value=m.cid(service);assert re.fullmatch('[a-f0-9]{64}',value)
 if identity is not None:assert value==identity,'Original test container changed'
 row=json.loads(m.call(['docker','inspect',value]))[0]
 assert row['Config'].get('Labels',{}).get('com.docker.compose.project')=='agentplatform-r53-local'
 assert row['Config'].get('Labels',{}).get('com.docker.compose.service')==service
 return row
def ensure_running(service,identity):
 row=exact(service,identity)
 if not row['State']['Running']:m.call(['docker','start',identity],timeout=30)
 deadline=time.monotonic()+100
 while time.monotonic()<deadline:
  row=exact(service,identity);state=row['State'];assert not state.get('OOMKilled',False)
  if state['Running'] and state.get('Health',{}).get('Status','healthy')=='healthy':return
  time.sleep(1)
 raise RuntimeError('Original isolated service did not recover: '+service)
def queue_state():
 row=exact('rabbitmq');assert row['State']['Running']
 address=row['NetworkSettings']['Networks']['agentplatform-r53-local-control']['IPAddress'];assert ipaddress.ip_address(address).is_private
 secret=Path(c['secrets']['mq_password']['file']);assert secret.parent==r and not secret.is_symlink()
 env=c['services']['api']['environment'];user=env.get('PLATFORM_WORKSPACE_MESSAGING_USER',env.get('PLATFORM_CHAT_MQ_USER'));assert user=='r53'
 class NoRedirect(urllib.request.HTTPRedirectHandler):
  def redirect_request(self,*args,**kwargs):raise RuntimeError('Unexpected isolated management redirect')
 http=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
 endpoint='http://'+address+':15672/api/queues/'+urllib.parse.quote('/agentplatform',safe='')+'/ap.asset.v1'
 request=urllib.request.Request(endpoint,headers={'Authorization':'Basic '+base64.b64encode((user+':'+secret.read_text().strip()).encode()).decode()})
 with http.open(request,timeout=5) as response:q=json.load(response)
 stats=q.get('message_stats',{})
 return {'ready':q.get('messages_ready',0),'unacked':q.get('messages_unacknowledged',0),'consumers':q.get('consumers',0),'delivered':stats.get('deliver_get',stats.get('deliver',0)),'acked':stats.get('ack',0)}
'''.replace('ROOT_VALUE',repr(root))

    def remote(body,timeout=120):return native.remote(prefix+body,timeout=timeout)
    snapshot=json.loads(remote('''rows={name:exact(name) for name in ['rabbitmq','worker','postgres','api']}
assert all(v['State']['Running'] and not v['State'].get('OOMKilled',False) for v in rows.values())
assert m.sql("SELECT count(*) FROM pf.heavy_runtime_slot WHERE kind IS NOT NULL")=='0','Wait for the original heavy operation; do not interrupt it'
assert m.sql("SELECT count(*) FROM pf.preview_job WHERE state='RUNNING'")=='0'
assert m.sql("SELECT count(*) FROM pf.file_transfer_slot WHERE owner_id IS NOT NULL")=='0'
print(json.dumps({'root':str(r),'containers':{k:v['Id'] for k,v in rows.items()},'images':{k:v['Image'] for k,v in rows.items()},'queue':queue_state(),'modelCalls':int(m.sql('SELECT count(*) FROM pf.model_call'))}))
'''))
    assert snapshot['queue']['ready']==snapshot['queue']['unacked']==0
    client=api.Client();journal=preview.Journal();seed=json.loads((DATA/'native-preview-seed.json').read_text())
    assert seed['spaceId']==client.space and seed['userId']==client.me['id']
    name='broker-preview-'+snapshot['images']['api'][-12:]+'.docx'
    content=(preview.FIXTURES/'sample.docx').read_bytes()
    entry=preview.upload(client,journal,seed['workspaceId'],name,content)
    result={'status':'FAIL','root':root,'supplierCalls':0,'scope':'actual isolated PG/MQ/S3 broker outage, original outbox handoff, deliberate client-response discard recovery, duplicate managed publication and same-version hash',
            'notInjectedHere':['worker crash during physical rendering','database outage','S3 outage','unconfirmed physical stop'],
            'otherEvidenceScope':'PreviewPostgresTest and deterministic PreviewRunnerServiceTest cover transaction/fence/UNKNOWN/close-proof failures; these are not physical outage evidence',
            'originalContainers':snapshot['containers'],'originalImages':snapshot['images']}

    def facts(identity):
        selected=str(uuid.UUID(identity))
        query="SELECT json_build_object('state',j.state,'revision',j.revision,'generation',j.generation,'attempts',(SELECT count(*) FROM pf.preview_attempt a WHERE a.preview_id=j.id),'assets',(SELECT count(*) FROM pf.asset_object a WHERE a.category='preview' AND a.space_id=j.space_id AND a.version_id=j.id),'references',(SELECT count(*) FROM pf.preview_asset_reference a WHERE a.preview_id=j.id),'inbox',(SELECT count(*) FROM pf.preview_inbox i WHERE i.preview_id=j.id),'outboxId',(SELECT o.id::text FROM pf.preview_outbox o WHERE o.preview_id=j.id AND o.generation=j.generation),'outboxStatus',(SELECT o.status FROM pf.preview_outbox o WHERE o.preview_id=j.id AND o.generation=j.generation),'quarantine',(SELECT count(*) FROM pf.preview_delivery_quarantine),'committedBytes',s.committed_bytes,'reservedBytes',s.reserved_bytes) FROM pf.preview_job j JOIN pf.space_storage s ON s.space_id=j.space_id WHERE j.id='"+selected+"';"
        return json.loads(remote('print(m.sql('+repr(query)+'))\n'))

    try:
        broker=snapshot['containers']['rabbitmq']
        remote("row=exact('rabbitmq',"+repr(broker)+");m.call(['docker','stop','-t','5',row['Id']]);assert not exact('rabbitmq',row['Id'])['State']['Running'];print('ISOLATED_R53_BROKER_STOPPED')\n")
        body={'versionId':entry['versionId'],'format':'AUTO'}
        request={'method':'POST','path':'/api/v1/files/'+entry['id']+'/previews','body':body}
        key='fault:broker-preview:'+snapshot['images']['api'];frozen=journal.freeze(key,request)
        assert frozen['phase']=='FROZEN','Prior fault request exists; only query its original request identity'
        frozen['phase']='SENT';journal.write()
        status,_,_=client.request('POST',request['path'],body,{'Idempotency-Key':frozen['key']})
        assert status==202
        # Intentionally discard the accepted response body, then recover only the frozen request.
        frozen['phase']='RESPONSE_DISCARDED';journal.write()
        fingerprint=hashlib.sha256((entry['id']+'\n'+entry['versionId']+'\nAUTO').encode()).hexdigest()
        query="SELECT p.preview_id::text FROM pf.preview_request p JOIN pf.preview_job j ON j.id=p.preview_id AND j.space_id=p.space_id WHERE p.space_id='"+str(uuid.UUID(client.space))+"' AND p.actor_id='"+str(uuid.UUID(client.me['id']))+"' AND p.request_id='"+str(uuid.UUID(frozen['key']))+"' AND p.request_hash='"+fingerprint+"' AND p.kind='CREATE' AND j.file_id='"+str(uuid.UUID(entry['id']))+"' AND j.version_id='"+str(uuid.UUID(entry['versionId']))+"';"
        identity=remote('print(m.sql('+repr(query)+'))\n');assert str(uuid.UUID(identity))==identity
        original=preview.get(client,'/api/v1/previews/'+identity)[1]
        frozen.update(phase='ACKNOWLEDGED_BY_DATABASE_QUERY',response=original,responseDiscarded=True);journal.write()
        queued=facts(identity)
        assert queued['state']=='QUEUED' and queued['attempts']==0 and queued['assets']==0
        assert queued['outboxStatus'] in ['PENDING','PUBLISHING']
        result.update(previewId=identity,queuedWhileBrokerDown=queued,responseDiscardRecovery={'requestId':frozen['key'],'originalId':identity,'additionalPostRequests':0})
        remote("ensure_running('rabbitmq',"+repr(broker)+");print('SAME_ISOLATED_BROKER_RECOVERED')\n",timeout=140)
        states=[];deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            current=preview.get(client,'/api/v1/previews/'+identity)[1];states.append(current['state'])
            if current['state'] in ['READY','FAILED']:break
            assert current['state']!='BLOCKED','Query the original blocked command; do not restart the conversion'
            time.sleep(.25)
        else:raise RuntimeError('Original preview did not converge within the bounded fault window')
        assert current['state']=='READY',current.get('errorCode')
        output=preview.preview_bytes(client,current);assert output.startswith(b'%PDF-')
        baseline=facts(identity);assert baseline['inbox']==1 and baseline['assets']==baseline['references']==1
        assert baseline['outboxStatus']=='PUBLISHED'
        before_queue=json.loads(remote('print(json.dumps(queue_state()))\n'))
        event=str(uuid.UUID(baseline['outboxId']))
        replay="UPDATE pf.preview_outbox o SET status='PENDING',relay_owner=NULL,relay_until=NULL FROM pf.preview_job j WHERE o.id='"+event+"' AND o.preview_id=j.id AND j.id='"+identity+"' AND o.generation=j.generation AND o.status='PUBLISHED' AND j.state='READY' RETURNING o.id::text;"
        assert remote('print(m.sql('+repr(replay)+'))\n')==event
        # Existing Spring relay republishes this same event; no raw AMQP tool or new message authority.
        deadline=time.monotonic()+40;after_queue=None
        while time.monotonic()<deadline:
            after_queue=json.loads(remote('print(json.dumps(queue_state()))\n'))
            observed=facts(identity)
            if observed['outboxStatus']=='PUBLISHED' and after_queue['ready']==after_queue['unacked']==0 and after_queue['acked']>before_queue['acked'] and after_queue['delivered']>before_queue['delivered']:break
            time.sleep(1)
        else:raise RuntimeError('Original duplicate publication not confirmed/drained; inspect the original event instead of publishing again')
        assert observed==baseline,'Duplicate changed durable preview, attempt, asset, reservation or settlement facts'
        assert preview.preview_bytes(client,current)==output
        result.update(status='PASS',statesObserved=states,ready=baseline,duplicate={'eventId':event,'beforeQueue':before_queue,'afterQueue':after_queue,'factsUnchanged':True},
                      outputSha256=hashlib.sha256(output).hexdigest(),workerRestarted=False,workerRecovery='original worker Spring AMQP reconnect; same container')
    finally:
        # Reversible cleanup only: never recreates a service or resolves an UNKNOWN by replay.
        recovery="ensure_running('rabbitmq',"+repr(snapshot['containers']['rabbitmq'])+")\nensure_running('worker',"+repr(snapshot['containers']['worker'])+")\n"
        recovery+="assert exact('postgres',"+repr(snapshot['containers']['postgres'])+")['State']['Running']\n"
        recovery+="assert int(m.sql('SELECT count(*) FROM pf.model_call'))=="+str(snapshot['modelCalls'])+"\nprint(json.dumps({'sameTestBrokerAndWorkerRunning':True,'modelCallDelta':0}))\n"
        try:result['recovery']=json.loads(remote(recovery,timeout=230))
        except BaseException:result.update(status='FAIL',recovery={'sameTestBrokerAndWorkerRunning':False});raise
        finally:preview.atomic(result_file,result)
    print(json.dumps({'status':result['status'],'result':str(result_file),'supplierCalls':0}))


if __name__=='__main__':main()
