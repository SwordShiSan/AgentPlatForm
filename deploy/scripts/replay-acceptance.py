"""R2.2 isolated real HTTP/PG replay, observation and old-image compatibility gates."""
import argparse
import importlib.util
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

from execution_fixture import ROOT, DATA, ENV, COMPOSE, BASE, PROJECT, STAGE
from execution_images import pin_exported

assert STAGE == 'r22' and PROJECT == 'agentplatform-r22-local', 'Only the isolated R2.2 fixture is authorized'
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
    # Let previously closed sockets leave admission before the exact eight/ninth test.
    time.sleep(3)
    opened = []
    started = time.monotonic()
    try:
        for _ in range(8):
            response = stream(client, conversation, cursor)
            assert response.status == 200 and response.readline().startswith(b':')
            opened.append(response)
        assert status(client, conversation, cursor) == 503
        assert sql("SELECT count(*) FROM pg_stat_activity WHERE usename='ap_chat_api' AND state='idle in transaction'") == '0'
        assert int(sql("SELECT count(*) FROM pg_stat_activity WHERE usename='ap_chat_api'")) <= 4
        assert client.request('GET', '/api/v1/readiness')['status'] == 'READY'
    finally:
        for response in opened:
            response.close()
    wait(lambda: status(client, conversation, cursor) == 200, 35)
    assert time.monotonic()-started < 40
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
    return {'status':'PASS','cursor':cursor,'eightConnections':True,'ninthStatus':503,'idleTransactions':0,
            'prunedCursorStatus':410,'watermarkCursorStatus':200,'canonicalSnapshotUnchanged':True,'leastPrivilege':True,
            'sample':execution.sample(),'realProviderCalls':0}


def observe():
    client=execution.Client()
    started=time.monotonic()
    samples=[]
    completed_conversations=[]
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
    return {'status':'PASS','durationSeconds':time.monotonic()-started,'samples':samples,'tasks':len(completed_conversations),
            'realProviderCalls':0,'capacityClaim':'Single-slot container limits and sampled usage only; actual host identity is in samples'}


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
    parser.add_argument('mode',choices=['protocol','observe','rollback'])
    args=parser.parse_args()
    pin_exported(ENV,DATA,required=True)
    OUT.mkdir(parents=True,exist_ok=True)
    result={'status':'FAIL','mode':args.mode}
    try:
        result=globals()[{'protocol':'protocol','observe':'observe','rollback':'rollback'}[args.mode]]()
    finally:
        result['mode']=args.mode
        result['recordedAt']=time.time()
        (OUT/('replay-'+args.mode+'.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(args.mode+': PASS',flush=True)


if __name__=='__main__':
    main()
