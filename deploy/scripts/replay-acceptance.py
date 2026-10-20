"""R2.2 isolated real HTTP/PG replay, observation and old-image compatibility gates."""
import argparse
import hashlib
import importlib.util
import json
import time
import socket
import subprocess
import shutil
from concurrent.futures import ThreadPoolExecutor
import urllib.error
import urllib.request
import urllib.parse
from pathlib import Path

from execution_fixture import ROOT, DATA, ENV, COMPOSE, BASE, PROJECT, STAGE
from execution_images import pin_exported

assert STAGE in {'r22', 'r23', 'r24'} and PROJECT == 'agentplatform-' + STAGE + '-local', 'Only explicit isolated R2.2/R2.3/R2.4 fixtures are authorized'
spec = importlib.util.spec_from_file_location('execution_acceptance', Path(__file__).with_name('execution-acceptance.py'))
execution = importlib.util.module_from_spec(spec)
spec.loader.exec_module(execution)
sql, command, wait = execution.sql, execution.command, execution.wait
OUT = DATA / 'acceptance'


def stream(client, conversation, cursor=0, last=None):
    headers = {'Accept': 'text/event-stream'}
    if last is not None:
        headers['Last-Event-ID'] = last
    request = urllib.request.Request(BASE + '/api/v1/conversations/' + conversation + '/events?after=' + str(cursor), headers=headers)
    return client.opener.open(request, timeout=35)


def status(client, conversation, cursor):
    try:
        with stream(client, conversation, cursor) as response:
            assert response.status == 200
            assert response.readline().startswith(b':')
            return 200
    except urllib.error.HTTPError as failure:
        return failure.code


def completed(client):
    task = client.submit('fixture:normal:r22-replay-' + str(time.time_ns()))
    wait(lambda: execution.status(task) == 'SUCCEEDED', 90)
    return client.request('GET', '/api/v1/tasks/' + task)['conversationId']


def protocol():
    client = execution.Client()
    conversation = completed(client)
    path = '/api/v1/conversations/' + conversation + '/snapshot'
    snapshot = client.request('GET', path)
    cursor = snapshot['cursor']
    assert cursor > 0
    for value, expected in [('bad',400), ('1.5',400), ('9007199254740992',400), (-1,409), (cursor+1,409)]:
        assert status(client, conversation, value) == expected, (value, expected)
    with stream(client, conversation, cursor, 'bad') as response:
        assert response.status == 200 and response.readline().startswith(b':')
    # A socket close is observed asynchronously (or by the servlet deadline).
    # Isolate capacity assertions using the specified35second cleanup bound.
    time.sleep(35)
    opened = []
    try:
        for _ in range(8):
            response = stream(client, conversation, cursor)
            assert response.status == 200 and response.readline().startswith(b':')
            opened.append(response)
        # Cross multiple watchdog ticks: immediate admission alone missed the
        # original delayed-read interval overflow seen around the sixth tick.
        time.sleep(14)
        assert status(client, conversation, cursor) == 503
        assert sql("SELECT count(*) FROM pg_stat_activity WHERE usename='ap_chat_api' AND state='idle in transaction'") == '0'
        assert int(sql("SELECT count(*) FROM pg_stat_activity WHERE usename='ap_chat_api'")) <= 4
        assert client.request('GET', '/api/v1/readiness')['status'] == 'READY'
    finally:
        for response in opened:
            response.close()
    closed_at=time.monotonic()
    wait(lambda: status(client, conversation, cursor) == 200, 35)
    returned=time.monotonic()-closed_at
    assert returned < 35
    assert sql("SELECT has_function_privilege('ap_chat_api','pf.collect_expired_replay(uuid)','EXECUTE')") == 't'
    assert sql("SELECT has_function_privilege('ap_chat_worker','pf.collect_expired_replay(uuid)','EXECUTE')") == 'f'
    for role in ['ap_chat_api','ap_chat_worker']:
        assert sql("SELECT has_table_privilege('"+role+"','pf.platform_event','DELETE')") == 'f'
        assert sql("SELECT has_column_privilege('"+role+"','pf.conversation','replay_pruned_through','UPDATE')") == 'f'
    version = sql("SELECT version::text||'/'||updated_at::text FROM pf.conversation WHERE id='"+conversation+"'")
    sql("UPDATE pf.platform_event SET created_at=now()-interval '31 days' WHERE conversation_id='"+conversation+"'")
    sql("SET ROLE ap_chat_api; SELECT pf.collect_expired_replay('"+conversation+"'); RESET ROLE")
    assert status(client, conversation, 0) == 410
    assert status(client, conversation, cursor) == 200
    assert snapshot == client.request('GET', path)
    assert version == sql("SELECT version::text||'/'||updated_at::text FROM pf.conversation WHERE id='"+conversation+"'")
    assert int(sql("SELECT replay_pruned_through FROM pf.conversation WHERE id='"+conversation+"'")) == cursor
    assert sql('SELECT physical_calls FROM pf.acceptance_model_meter WHERE id=1') == '0'
    return {'status':'PASS','cursor':cursor,'preflightCleanupWaitSeconds':35,'eightConnections':True,'eightConnectionHoldSeconds':14,'ninthStatus':503,'idleTransactions':0,
            'disconnectAdmissionReturnedWithinSeconds':returned,
            'prunedCursorStatus':410,'watermarkCursorStatus':200,'canonicalSnapshotUnchanged':True,'leastPrivilege':True,
            'sample':execution.sample(),'realProviderCalls':0}


def collection():
    client=execution.Client()
    conversation=completed(client)
    snapshot=client.request('GET','/api/v1/conversations/'+conversation+'/snapshot')
    old_task=snapshot['tasks'][-1]['taskId'];floor=snapshot['cursor']
    wait(lambda: sql("SELECT status FROM pf.event_outbox WHERE aggregate_id='"+old_task+"'")=='PUBLISHED')
    tables={'task':'id','message':'task_id','attempt':'task_id','model_call':'task_id','model_turn':'task_id',
            'checkpoint':'task_id','budget_reservation':'task_id','event_outbox':'aggregate_id','consumer_inbox':'aggregate_id'}
    def fingerprints():
        return {table:hashlib.sha256(sql("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text "
            +"FROM pf."+table+" t WHERE "+column+"='"+old_task+"'").encode('utf-8')).hexdigest() for table,column in tables.items()}
    before=fingerprints()
    assert int(sql("SELECT count(*) FROM pf.checkpoint WHERE task_id='"+old_task+"'"))>0
    sql("UPDATE pf.platform_event SET created_at=now()-interval '31 days' WHERE conversation_id='"+conversation+"' AND seq<="+str(floor))
    task=client.request('POST','/api/v1/conversations/'+conversation+'/tasks',{'options':{'modelId':'0199e000-0000-7000-8000-000000000002','credentialSource':'PLATFORM'},'input':{'text':'fixture:slow'}})['taskId']
    deleted=[]
    try:
        wait(lambda: sql("SELECT count(*) FROM pf.message WHERE task_id='"+task+"' AND role='ASSISTANT' AND length(blocks_json->0->>'text')>0")=='1')
        def collect(_):return int(sql("SELECT pf.collect_expired_replay('"+conversation+"')"))
        with ThreadPoolExecutor(max_workers=2) as pool:
            deleted=list(pool.map(collect,range(6)))
        assert all(0<=value<=1000 for value in deleted)
        assert int(sql("SELECT replay_pruned_through FROM pf.conversation WHERE id='"+conversation+"'"))==floor
    finally:
        client.request('POST','/api/v1/tasks/'+task+'/cancel',{})
        terminal = {'CANCELLED','SUCCEEDED','BLOCKED'} if STAGE in {'r23','r24'} else {'CANCELLED','SUCCEEDED'}
        wait(lambda: execution.status(task) in terminal,90)
        if STAGE in {'r23','r24'}:
            stopped = client.request('GET','/api/v1/tasks/'+task+'/reconciliation')
            assert stopped['stopConfirmed'] and stopped['stopStage']=='STOPPED'
            assert sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL')=='0'
            if execution.status(task)=='BLOCKED':
                assert stopped['unknownCalls']==1 and stopped['excludedFromContext']
                assert stopped['calls'][0]['inputTokens'] is None
                assert sql("SELECT count(*) FROM pf.budget_reservation WHERE task_id='"+task+"' AND state='RECONCILING'")=='1'
    assert before==fingerprints(),'GC changed a canonical message/ledger/checkpoint/outbox/inbox'
    current=client.request('GET','/api/v1/conversations/'+conversation+'/snapshot')
    assert current['cursor']>floor
    assert int(sql("SELECT count(*) FROM pf.platform_event WHERE conversation_id='"+conversation+"'"))==current['cursor']-floor
    assert status(client,conversation,0)==410 and status(client,conversation,floor)==200
    return {'status':'PASS','realApplicationHistory':True,'simulatedAgeOnly':True,'collectors':2,'oldPrefix':floor,'deletedPerCall':deleted,
            'preservedFactSha256':before,'youngTailContiguous':True,'realProviderCalls':0}


def observe():
    retained=collection()
    (OUT/'replay-collection.json').write_text(json.dumps(retained,indent=2),encoding='utf-8')
    client=execution.Client()
    started=time.monotonic()
    samples=[]
    completed_conversations=[]
    interruption=None
    while True:
        conversation=completed(client)
        snapshot=client.request('GET','/api/v1/conversations/'+conversation+'/snapshot')
        sql("UPDATE pf.platform_event SET created_at=now()-interval '31 days' WHERE conversation_id='"+conversation+"'")
        # Exercise both retained and subsequently expired cursor paths; never resubmit a task.
        response_status=status(client,conversation,0)
        assert response_status in (200,410)
        if response_status==410:
            assert status(client,conversation,snapshot['cursor'])==200
        assert snapshot==client.request('GET','/api/v1/conversations/'+conversation+'/snapshot')
        completed_conversations.append(conversation)
        if interruption is None and time.monotonic()-started>=600:
            began=time.monotonic()-started
            if (OUT/'outages.json').exists():
                shutil.copy2(OUT/'outages.json',OUT/'outages-before-observation.json')
            execution.outages(client)
            command(COMPOSE+['up','-d','--no-deps','--wait','--wait-timeout','180','worker','worker-2'])
            interruption={'status':'PASS','startedAtSeconds':began,'endedAtSeconds':time.monotonic()-started,
                          'brokerAndActiveDatabaseFailure':True,'unknownNotReplayed':True,
                          'evidenceSha256':hashlib.sha256((OUT/'outages.json').read_bytes()).hexdigest()}
        item=execution.sample()
        item['elapsedSeconds']=time.monotonic()-started
        item['replayRows']=int(sql('SELECT count(*) FROM pf.platform_event'))
        item['prunedConversations']=int(sql('SELECT count(*) FROM pf.conversation WHERE replay_pruned_through>0'))
        assert sql('SELECT physical_calls FROM pf.acceptance_model_meter WHERE id=1')=='0'
        samples.append(item)
        (OUT/'replay-observation-progress.json').write_text(json.dumps({'status':'RUNNING','samples':samples},indent=2),encoding='utf-8')
        print('Observation sample '+str(len(samples))+': '+str(round(item['elapsedSeconds']))+'s',flush=True)
        if item['elapsedSeconds']>=1800:
            break
        time.sleep(min(30,1800-item['elapsedSeconds']))
    assert samples[-1]['prunedConversations']>samples[0]['prunedConversations'], 'Actual scheduled collection must run'
    assert interruption is not None and interruption['endedAtSeconds']>=600
    return {'status':'PASS','durationSeconds':time.monotonic()-started,'samples':samples,'tasks':len(completed_conversations),
            'retentionConcurrency':retained,'inWindowDependencyFailure':interruption,
            'realProviderCalls':0,'capacityClaim':'Single-slot container limits and sampled usage only; actual host identity is in samples'}


def failures():
    client=execution.Client()
    conversation=completed(client)
    cursor=client.request('GET','/api/v1/conversations/'+conversation+'/snapshot')['cursor']
    actor=client.request('GET','/api/v1/me')['id']
    durations={}
    # Membership is rechecked in every replay transaction, including for administrators.
    response=stream(client,conversation,cursor)
    assert response.readline().startswith(b':')
    started=time.monotonic()
    try:
        sql("UPDATE pf.membership SET status='REVOKED' WHERE user_id='"+actor+"'")
        tail=response.read()
        durations['membership']=time.monotonic()-started
        assert durations['membership']<5 and b'problem' not in tail
    finally:
        response.close()
        sql("UPDATE pf.membership SET status='ACTIVE' WHERE user_id='"+actor+"'")
    response=stream(client,conversation,cursor)
    assert response.readline().startswith(b':')
    started=time.monotonic()
    try:
        sql("UPDATE pf.auth_session SET revoked_at=now() WHERE user_id='"+actor+"' AND revoked_at IS NULL")
        tail=response.read()
        durations['session']=time.monotonic()-started
        assert durations['session']<6 and b'problem' not in tail
    finally:
        response.close()
    client=execution.Client()
    before=sql('SELECT count(*) FROM pf.model_call')
    response=stream(client,conversation,cursor)
    assert response.readline().startswith(b':')
    started=time.monotonic()
    try:
        command(COMPOSE+['stop','postgres'])
        tail=response.read()
        durations['database']=time.monotonic()-started
        assert durations['database']<12 and b'problem' not in tail
    finally:
        response.close()
        command(COMPOSE+['up','-d','--wait','--wait-timeout','90','postgres'])
    wait(lambda: execution.http_status(client,'/api/v1/me')==200,60)
    assert before==sql('SELECT count(*) FROM pf.model_call'), 'Replay dependency recovery never initiates a model call'
    assert status(client,conversation,cursor)==200
    return {'status':'PASS','closureSeconds':durations,'noPostHeaderProblem':True,'modelCallsUnchanged':True,'realProviderCalls':0}


def pressure():
    client=execution.Client()
    actor=client.request('GET','/api/v1/me')
    space=next(item['id'] for item in actor['spaces'] if item['type']=='PERSONAL')
    conversation=client.request('POST','/api/v1/conversations',{'title':'R2.2 synthetic transport fixture'})['id']
    # Explicit transport-only fixture; these rows do not claim a canonical model/tool history.
    sql("INSERT INTO pf.platform_event(id,space_id,conversation_id,seq,type,schema_version,entity_version,payload_json) "
        +"SELECT gen_random_uuid(),'"+space+"','"+conversation+"',n,'message.delta',1,n,"
        +"jsonb_build_object('messageId','00000000-0000-4000-8000-000000000001','blockId','00000000-0000-4000-8000-000000000001','offset',(n-1)*2048,'text',repeat('x',2048)) "
        +"FROM generate_series(1,8192) n; UPDATE pf.conversation SET next_seq=8192 WHERE id='"+conversation+"'")
    jar=next(handler.cookiejar for handler in client.opener.handlers if hasattr(handler,'cookiejar'))
    cookie='; '.join(item.name+'='+item.value for item in jar)
    opened=[]
    sock=socket.socket()
    sock.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,1024)
    time.sleep(35)  # Do not include previous gates' asynchronous socket cleanup.
    started=time.monotonic()
    try:
        endpoint=urllib.parse.urlsplit(BASE)
        assert endpoint.hostname=='127.0.0.1' and endpoint.port is not None
        sock.connect((endpoint.hostname,endpoint.port))
        wire=('GET /api/v1/conversations/'+conversation+'/events?after=0 HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\nCookie: '+cookie+'\r\n\r\n').encode('ascii')
        sock.sendall(wire)
        # Consume headers, then deliberately stop reading the socket.
        sock.settimeout(5)
        header=b''
        while b'\r\n\r\n' not in header:
            header+=sock.recv(1)
        assert b' 200 ' in header
        for _ in range(7):
            response=stream(client,conversation,8192)
            assert response.readline().startswith(b':')
            opened.append(response)
        assert client.request('GET','/api/v1/readiness')['status']=='READY'
        assert sql("SELECT count(*) FROM pg_stat_activity WHERE usename='ap_chat_api' AND state='idle in transaction'")=='0'
        assert status(client,conversation,8192)==503
        wait(lambda: status(client,conversation,8192)==200,35)
        released=time.monotonic()-started
        assert released<35
    finally:
        sock.close()
        for response in opened:
            response.close()
    normal=completed(client)
    assert client.request('GET','/api/v1/conversations/'+normal+'/snapshot')['tasks'][-1]['status']=='SUCCEEDED'
    sql("UPDATE pf.platform_event SET created_at=now()-interval '31 days' WHERE conversation_id='"+conversation+"'")
    return {'status':'PASS','syntheticTransportEvents':8192,'perEventTextBytes':2048,'capacityReturnedWithinSeconds':released,
            'specificSocketLifetimeClaim':False,'specificSlowWriterBoundEvidence':'ChatStreamingHttpTest stopped-reader uses direct admission/writer counters',
            'canonicalHistoryClaim':False,'platformReady':True,'postPressureTask':'SUCCEEDED','sample':execution.sample(),'realProviderCalls':0}


def rollback():
    previous=json.loads((ROOT/'.cache/r2c-local/artifacts/images.json').read_text(encoding='utf-8'))
    old=next(item for item in previous if item['component']=='api')
    assert old['platform']=='linux/amd64' and old['configId'].startswith('sha256:')
    inspected=json.loads(command(['docker','image','inspect',old['localId']]))[0]
    assert inspected['Id']==old['localId']
    assert sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL')=='0'
    override=DATA/'previous-image.override.json'
    override.write_text(json.dumps({'services':{'api':{'image':old['localId'],'environment':{'PLATFORM_CHAT_RETENTION_ENABLED':'false'}}}}),encoding='utf-8')
    switched=COMPOSE+['-f',str(override)]
    result=None
    try:
        command(switched+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','120','api'])
        client=execution.Client()
        assert client.request('GET','/api/v1/readiness')['status']=='READY'
        conversation=completed(client)
        snapshot=client.request('GET','/api/v1/conversations/'+conversation+'/snapshot')
        assert status(client,conversation,snapshot['cursor'])==200
        assert sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success')=='7'
        assert sql('SELECT schema_version FROM pf.baseline_version WHERE id=1')=='1'
        result={'status':'PASS','previousSource':old['source'],'previousImage':old['localId'],'schema':7,'runtimeBaseline':1,'snapshotReadable':True,'deterministicChatRoundTrip':True,'realProviderCalls':0}
    finally:
        command(COMPOSE+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','120','api'])
        assert execution.Client().request('GET','/api/v1/readiness')['status']=='READY'
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['protocol','failures','pressure','collection','observe','rollback'])
    args=parser.parse_args()
    assert STAGE == 'r22' or args.mode not in {'rollback', 'observe'}, 'R2.3 uses cancellation-acceptance.py for version compatibility and observation'
    pin_exported(ENV,DATA,required=True)
    OUT.mkdir(parents=True,exist_ok=True)
    result={'status':'FAIL','mode':args.mode}
    try:
        result=globals()[args.mode]()
        assert sql('SELECT physical_calls FROM pf.acceptance_model_meter WHERE id=1')=='0'
    except BaseException as failure:
        result['status']='FAIL'
        result['failureType']=type(failure).__name__
        raise
    finally:
        candidate=json.loads((DATA/'final-candidate.json').read_text(encoding='utf-8'))
        result['mode']=args.mode
        result['recordedAt']=time.time()
        result['candidateSource']=candidate['source']
        result['componentSources']=candidate.get('componentSources',{})
        result['testSource']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
        result['scriptSha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        (OUT/('replay-'+args.mode+'.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(args.mode+': PASS',flush=True)


if __name__=='__main__':
    main()
