"""R2.3 deterministic local cancellation/CLI/stop-proof acceptance; never a supplier request."""
import argparse
import importlib.util
import json
import os
import subprocess
import time
import uuid
from pathlib import Path

assert os.environ.get('AGENTPLATFORM_ACCEPTANCE_STAGE') in {'r23','r24'}, 'Explicit isolated R2.3/R2.4 selection required'
from execution_fixture import ROOT, DATA, ENV, COMPOSE, PROJECT
from execution_images import pin_exported

def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

execution = load('execution_acceptance', 'execution-acceptance.py')
local = load('execution_local', 'execution-local.py')
sql, command, wait, status = execution.sql, execution.command, execution.wait, execution.status
OUT = DATA / 'acceptance/cancellation'

def record(name, details):
    assert sql('SELECT physical_calls FROM pf.acceptance_model_meter WHERE id=1') == '0'
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / (name + '.json')).write_text(json.dumps(dict(status='PASS', realProviderCalls=0, **details), indent=2), encoding='utf-8')
    print(name + ': PASS (deterministic provider only)', flush=True)

def cancel(client, task):
    client.request('POST', '/api/v1/tasks/' + task + '/cancel', {})

def projection(client, task):
    return client.request('GET', '/api/v1/tasks/' + task + '/reconciliation')

def partial(task):
    return sql("SELECT count(*) FROM pf.message WHERE task_id='" + task + "' AND role='ASSISTANT' AND length(blocks_json->0->>'text')>0") == '1'

def cancelled(client, suffix):
    task = client.submit('fixture:slow:r23:' + suffix + ':' + str(uuid.uuid4()))
    wait(lambda: partial(task))
    cancel(client, task)
    wait(lambda: status(task) == 'BLOCKED')
    p = projection(client, task)
    assert p['stopConfirmed'] and p['stopStage'] == 'STOPPED' and p['unknownCalls'] == 1
    assert p['excludedFromContext'] and p['calls'][0]['inputTokens'] is None
    assert sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL') == '0'
    assert sql("SELECT count(*) FROM pf.execution_stop_receipt WHERE task_id='" + task + "' AND source='LOCAL_QUIESCENCE'") == '1'
    version = sql("SELECT version FROM pf.task WHERE id='" + task + "'")
    for _ in range(3):
        cancel(client, task)
    assert version == sql("SELECT version FROM pf.task WHERE id='" + task + "'")
    assert sql("SELECT count(*) FROM pf.task_cancellation WHERE task_id='" + task + "'") == '1'
    assert sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'") == '1'
    return task

def protocol(client):
    execution.workers()
    command(COMPOSE + ['up', '-d', '--no-deps', 'worker-2'])
    task = cancelled(client, 'partial-repeat')
    conversation = sql("SELECT conversation_id FROM pf.task WHERE id='" + task + "'")
    result = client.request('POST', '/api/v1/conversations/' + conversation + '/tasks', {
        'options': {'modelId': '0199e000-0000-7000-8000-000000000002', 'credentialSource': 'PLATFORM'},
        'input': {'text': 'fixture:history:r23:' + str(uuid.uuid4())}})
    followup = result['taskId']
    wait(lambda: status(followup) == 'SUCCEEDED')
    assert sql("SELECT blocks_json->0->>'text' FROM pf.message WHERE task_id='" + followup + "' AND role='ASSISTANT'") == 'history-messages=2'
    before = sql("SELECT version FROM pf.task WHERE id='" + followup + "'")
    cancel(client, followup)
    assert before == sql("SELECT version FROM pf.task WHERE id='" + followup + "'")
    assert status(followup) == 'SUCCEEDED'
    record('protocol', {'task': task, 'independentTask': followup, 'unknownRoundExcluded': True, 'sample': execution.sample()})

def operator(evidence, success=True):
    # Protected stdin -> 0600 tmpfs file; no raw evidence/key/password in argv or public output.
    try:
        local.fixture.operator(['--chat-action=reconcile-call', '--evidence-file=/tmp/operator-input'], json.dumps(evidence))
        assert success, 'Invalid reconciliation was accepted'
    except RuntimeError:
        if success:
            raise

def accounting(client):
    execution.workers()
    task = cancelled(client, 'operator')
    call = projection(client, task)['calls'][0]['callId']
    evidence = {'callId': call, 'commandId': str(uuid.uuid4()), 'expectedRevision': 0, 'decision': 'KEEP_UNKNOWN',
                'evidenceReference': str(uuid.uuid4()), 'evidenceKind': 'REVIEW', 'operator': 'r23-local-acceptance',
                'providerRequestId': None, 'inputTokens': None, 'outputTokens': None}
    operator(evidence); operator(evidence)
    assert projection(client, task)['calls'][0]['revision'] == 1
    assert sql("SELECT count(*) FROM pf.model_call_reconciliation WHERE call_id='" + call + "'") == '1'
    evidence.update(commandId=str(uuid.uuid4()), decision='CONFIRMED_USAGE', evidenceKind='PROVIDER_RECORD',
                    providerRequestId='fixture-1', inputTokens=100, outputTokens=20)
    operator(evidence, False)  # stale revision
    evidence.update(expectedRevision=1, inputTokens=9000)
    operator(evidence, False)  # over-reservation must not truncate usage
    evidence.update(inputTokens=None)
    operator(evidence, False)  # unknown usage is not zero
    evidence.update(inputTokens=100)
    operator(evidence); operator(evidence)
    assert status(task) == 'CANCELLED'
    p = projection(client, task)
    assert p['unknownCalls'] == 0 and p['calls'][0]['reservationState'] == 'SETTLED'
    evidence.update(commandId=str(uuid.uuid4()), expectedRevision=2, decision='CONFIRMED_NOT_EXECUTED')
    operator(evidence, False)
    assert sql("SELECT count(*) FROM pf.model_call_reconciliation WHERE call_id='" + call + "'") == '2'
    record('operator-accounting', {'task': task, 'revision': 2, 'idempotencyAndInvalidEvidenceRejected': True})

def stuck(client):
    cases = []
    for point in ['response-before-round', 'checkpoint-before-seal']:
        args = execution.workers(point)
        task = client.submit('fixture:normal:r23:stuck:' + point + ':' + str(uuid.uuid4()))
        def marker():
            r = subprocess.run(['docker', 'exec', PROJECT + '-worker-1', 'cat', '/tmp/chat-fault-ready'], capture_output=True, text=True)
            return r.returncode == 0 and r.stdout.strip() == point
        wait(marker)
        cancel(client, task)
        wait(lambda: projection(client, task)['stopStage'] == 'UNCONFIRMED', 15)
        assert status(task) == 'RUNNING'
        assert sql("SELECT count(*) FROM pf.execution_slot WHERE task_id='" + task + "'") == '1'
        assert sql("SELECT count(*) FROM pf.execution_stop_receipt WHERE task_id='" + task + "'") == '0'
        command(COMPOSE + ['up', '-d', '--no-deps', 'worker-2'])
        followup = client.submit('fixture:normal:r23:waiting:' + str(uuid.uuid4()))
        time.sleep(3)
        assert status(followup) == 'QUEUED'
        proof = execution.reconcile(ROOT, DATA, ENV, args, force=True)
        expected = 'BLOCKED' if point == 'response-before-round' else 'CANCELLED'
        wait(lambda: status(task) == expected)
        wait(lambda: status(followup) == 'SUCCEEDED', 90)
        assert projection(client, task)['stopConfirmed']
        assert sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'") == '1'
        cases.append({'point': point, 'task': task, 'waitingTask': followup, 'proof': proof, 'result': expected})
        print('Blocked callback keeps slot: ' + point + ' PASS', flush=True)
    record('unconfirmed-host-proof', {'cases': cases, 'keptSlotUntilProof': True})

def windows(client):
    results = []
    for point in ['claim-before-ack', 'ack-before-model', 'round-before-checkpoint', 'checkpoint-before-seal', 'checkpoint-after-seal']:
        args = execution.workers(point)
        task = client.submit('fixture:normal:r23:cancel-window:' + point + ':' + str(uuid.uuid4()))
        def marker():
            r = subprocess.run(['docker', 'exec', PROJECT + '-worker-1', 'cat', '/tmp/chat-fault-ready'], capture_output=True, text=True)
            return r.returncode == 0 and r.stdout.strip() == point
        wait(marker)
        cancel(client, task)
        assert sql("SELECT count(*) FROM pf.execution_slot WHERE task_id='" + task + "'") == '1'
        proof = execution.reconcile(ROOT, DATA, ENV, args, force=True)
        assert status(task) == 'CANCELLED'
        expected = 0 if point in ['claim-before-ack', 'ack-before-model'] else 1
        assert int(sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'")) == expected
        assert projection(client, task)['unknownCalls'] == 0
        results.append({'point': point, 'task': task, 'calls': expected, 'proof': proof})
        print('Cancelled crash boundary: ' + point + ' PASS', flush=True)
    execution.workers()
    prompt = 'fixture:timeout:r23:before-output:' + str(uuid.uuid4())
    task = client.submit(prompt)
    wait(lambda: sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "' AND status='STARTED'") == '1')
    time.sleep(1)
    cancel(client, task)
    wait(lambda: status(task) == 'BLOCKED')
    assert projection(client, task)['stopConfirmed'] and projection(client, task)['unknownCalls'] == 1
    results.append({'point': 'before-first-output', 'task': task, 'calls': 1})
    task = client.submit('fixture:429:r23:backoff:' + str(uuid.uuid4()))
    wait(lambda: int(sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'")) >= 1)
    cancel(client, task)
    wait(lambda: status(task) in {'CANCELLED', 'BLOCKED'})
    count = sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'")
    time.sleep(5)
    assert count == sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'")
    assert projection(client, task)['stopConfirmed']
    results.append({'point': 'retry-backoff', 'task': task, 'calls': int(count), 'lateRetryRejected': True})
    record('cancel-windows', {'windows': results})

def rollback(client):
    assert sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL') == '0'
    previous = json.loads((ROOT / '.cache/r22-local/artifacts/images.json').read_text(encoding='utf-8'))
    old = next(row for row in previous if row['component'] == 'api')
    assert json.loads(command(['docker', 'inspect', old['localId']]))[0]['Id'] == old['localId']
    override = DATA / 'previous-image.override.json'
    override.write_text(json.dumps({'services': {service: {'image': old['localId']} for service in ['api', 'worker', 'worker-2']}}), encoding='utf-8')
    switched = COMPOSE + ['-f', str(override)]
    try:
        command(COMPOSE + ['stop', 'api', 'worker', 'worker-2'])
        command(switched + ['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '120', 'api', 'worker', 'worker-2'])
        old_client = execution.Client()
        assert old_client.request('GET', '/api/v1/readiness')['status'] == 'READY'
        task = old_client.submit('fixture:normal:r23:previous-v7:' + str(uuid.uuid4()))
        wait(lambda: status(task) == 'SUCCEEDED')
        assert sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success') == '8'
        assert sql('SELECT schema_version FROM pf.baseline_version WHERE id=1') == '1'
        result = {'previousSource': old['source'], 'previousImage': old['localId'], 'schema': 8,
                  'runtimeBaseline': 1, 'task': task, 'noDownMigration': True}
    finally:
        command(COMPOSE + ['stop', 'api', 'worker', 'worker-2'])
        command(COMPOSE + ['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '120', 'api', 'worker', 'worker-2'])
    assert execution.Client().request('GET', '/api/v1/readiness')['status'] == 'READY'
    record('previous-image-compatibility', result)

def observe(client):
    candidate=json.loads((DATA/'final-candidate.json').read_text(encoding='utf-8'))
    assert subprocess.check_output(['git','rev-parse','HEAD:backend'],cwd=ROOT,text=True).strip()==candidate['backendTree']
    assert not subprocess.check_output(['git','diff','--name-only','HEAD','--','backend'],cwd=ROOT,text=True).strip(), 'Freeze/rebuild changed backend before observation'
    execution.workers()
    command(COMPOSE + ['up', '-d', '--no-deps', 'worker-2'])
    start = time.monotonic()
    samples, tasks = [], []
    interrupted = False
    while time.monotonic() - start < 1800:
        elapsed = time.monotonic() - start
        if elapsed >= 600 and not interrupted:
            execution.outages(client)
            # A failed owner is replaced only after the outage routine's exact stop proof/terminal stop.
            execution.workers()
            command(COMPOSE + ['up', '-d', '--no-deps', 'worker-2'])
            interrupted = True
        else:
            if len(tasks) % 3 == 0:
                task = cancelled(client, 'observe')
            else:
                task = client.submit('fixture:normal:r23:observe:' + str(uuid.uuid4()))
                wait(lambda: status(task) == 'SUCCEEDED')
            tasks.append(task)
        s = execution.sample()
        s['elapsedSeconds'] = time.monotonic() - start
        s['javaThreads'] = {service: command(['docker', 'exec', PROJECT + '-' + service + '-1', 'sh', '-c', "awk '/^Threads:/{print $2}' /proc/1/status"]) for service in ['api', 'worker', 'worker-2']}
        s['databaseConnections'] = int(sql('SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()'))
        samples.append(s)
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / 'observation-progress.json').write_text(json.dumps({'samples': samples, 'tasks': tasks, 'elapsedSeconds': s['elapsedSeconds']}, indent=2), encoding='utf-8')
        print('Observation sample: ' + str(round(s['elapsedSeconds'])) + 's, tasks=' + str(len(tasks)), flush=True)
        time.sleep(15)
    assert interrupted and all(not c['oom'] for sample in samples for c in sample['containers'])
    record('observation', {'elapsedSeconds': time.monotonic() - start, 'tasks': tasks, 'samples': samples,
                           'note': 'Actual Docker Desktop VM; samples only, not minimum physical host, absolute peaks or dual-execution capacity'})

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['protocol', 'accounting', 'stuck', 'windows', 'rollback', 'observe'])
    mode = parser.parse_args().mode
    assert os.environ['AGENTPLATFORM_ACCEPTANCE_STAGE'] == 'r23' or mode != 'rollback', 'R2.4 validates its own R1/V9 compatibility; do not substitute the old V7 rollback probe'
    pin_exported(ENV, DATA, required=True)
    OUT.mkdir(parents=True, exist_ok=True)
    globals()[mode](execution.Client())
