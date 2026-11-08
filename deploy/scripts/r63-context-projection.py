"""Bounded deterministic R6.3 context-projection persistence check.

Uses only the isolated R63 API/database and deterministic provider. No local
sandbox is started and no supplier request is made.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache' / 'r63-local'
PRIVATE = DATA / 'fixture'
manifest = json.loads((PRIVATE / 'manifest.json').read_text(encoding='utf-8'))
database = manifest['database']
postgres = 'agentplatform-r63-local-postgres-1'
api_container = 'agentplatform-r63-local-api-1'
provider_container = 'agentplatform-r63-local-fixture-1'


def client_type():
    spec = importlib.util.spec_from_file_location('r63_knowledge_acceptance', ROOT / 'deploy/scripts/r63-knowledge-acceptance.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Client


def docker(args, *, input_text=None, timeout=30):
    result = subprocess.run(['docker', *args], input=input_text, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('Isolated R63 context-projection probe failed; inspect protected process output')
    return result.stdout.strip()


def sql(statement):
    result = subprocess.run(['docker', 'exec', '-i', postgres, 'psql', '-v', 'ON_ERROR_STOP=1', '-X', '-A', '-t',
                             '-U', 'postgres', '-d', database], input=statement, text=True,
                           capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError('Isolated context-projection SQL failed; inspect protected process output')
    return result.stdout.strip()


def provider_calls():
    code = "import json,urllib.request; print(json.dumps(json.load(urllib.request.urlopen('http://127.0.0.1:8080/status'))))"
    return json.loads(docker(['exec', provider_container, 'python3', '-c', code]))['physical_calls']


def wait_task(client, task_id, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        task = client.call('GET', '/api/v1/tasks/' + task_id)
        if task['status'] == 'SUCCEEDED':
            return task
        if task['status'] in {'FAILED', 'BLOCKED', 'CANCELLED'}:
            raise RuntimeError('Deterministic context task ended ' + task['status'] + ':' + str(task.get('reasonCode')))
        time.sleep(1)
    raise RuntimeError('Context task remains pending; query its frozen task identity')


def context(client, task_id):
    return client.call('GET', '/api/v1/tasks/' + task_id + '/context')


def wait_readiness(port, timeout=90):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with opener.open('http://127.0.0.1:' + str(port) + '/api/v1/readiness', timeout=5) as response:
                if response.status == 200 and json.loads(response.read()).get('status') == 'READY':
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('R63 API did not return READY after the isolated process restart')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case-id', default='durable-projection', help='Unique isolated acceptance identity')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,31}', args.case_id):
        raise ValueError('Case identity must be lowercase letters, digits, and hyphens')
    state_path = DATA / f'context-projection-input-{database}-{args.case_id}.json'
    receipt_path = DATA / f'context-projection-result-{database}-{args.case_id}.json'
    if receipt_path.exists():
        raise RuntimeError('Context-projection receipt is immutable; choose a new case identity')

    Client = client_type()
    admin = Client('r63_admin')
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding='utf-8'))
        if state.get('database') != database or state.get('caseId') != args.case_id:
            raise RuntimeError('Frozen context-projection fixture identity changed')
    else:
        state = {
            'database': database,
            'caseId': args.case_id,
            'conversationKey': str(uuid.uuid4()),
            'taskKeys': [str(uuid.uuid4()) for _ in range(6)],
            'texts': [f'fixture:r63-context-projection-seed-{i}-{args.case_id}' for i in range(5)]
                     + [f'fixture:r63-context-projection-final-{args.case_id}'],
            'conversationId': None,
            'taskIds': [],
            'createPhase': 'PREPARED',
            'submitPhase': 'IDLE',
            'seedMessageId': None,
            'canonicalBeforeSha256': None,
            'finalTaskId': None,
        }
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')

    if state.get('providerCallsBefore') is None and not state['taskIds']:
        state['providerCallsBefore'] = provider_calls()
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')

    if state['conversationId'] is None:
        if state['createPhase'] == 'SENT':
            raise RuntimeError('Conversation creation outcome is unknown; do not replay its request')
        state['createPhase'] = 'SENT'
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
        body = {'title': 'R63 durable context projection ' + args.case_id,
                'initialTask': {'input': {'text': state['texts'][0], 'attachments': []},
                                'options': {'planMode': False, 'permissionMode': 'RISK_BASED'}}}
        created = admin.call('POST', '/api/v1/conversations', body,
                             {'Idempotency-Key': state['conversationKey']})
        state['conversationId'] = created['id']
        state['taskIds'] = [created['initialTask']['taskId']]
        state['createPhase'] = 'ACKNOWLEDGED'
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
    elif state['createPhase'] != 'ACKNOWLEDGED':
        raise RuntimeError('Frozen conversation request is not acknowledged; do not replay')

    for index, task_id in enumerate(state['taskIds']):
        wait_task(admin, task_id)
    while len(state['taskIds']) < 5:
        index = len(state['taskIds'])
        body = {'input': {'text': state['texts'][index], 'attachments': []},
                'options': {'planMode': False, 'permissionMode': 'RISK_BASED'}}
        body_hash = hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        if state['submitPhase'] == 'SENT':
            raise RuntimeError('Task submission outcome is unknown; do not replay its request')
        if state['submitPhase'] == 'REJECTED':
            rejected = state.get('submitRejection', {})
            if (rejected.get('status') != 422 or rejected.get('code') != 'CONTEXT_REQUIRED_TOO_LARGE'
                    or rejected.get('index') != index or rejected.get('requestSha256') != body_hash):
                raise RuntimeError('Only the frozen explicit context-admission rejection may be retried')
        state['submitPhase'] = 'SENT'
        state['submitIndex'] = index
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
        try:
            accepted = admin.call('POST', '/api/v1/conversations/' + state['conversationId'] + '/tasks', body,
                                  {'Idempotency-Key': state['taskKeys'][index]})
        except Exception as failure:
            status = getattr(failure, 'status', None)
            code = getattr(failure, 'code', None)
            if status in range(400, 500):
                state['submitPhase'] = 'REJECTED'
                state['submitRejection'] = {'status': status, 'code': code, 'index': index, 'requestSha256': body_hash}
                state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
            raise
        state['taskIds'].append(accepted['taskId'])
        state['submitPhase'] = 'ACKNOWLEDGED'
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
        wait_task(admin, accepted['taskId'])

    if state['seedMessageId'] is None:
        assistant = sql("SELECT id::text FROM pf.message WHERE task_id='" + state['taskIds'][0] + "' AND role='ASSISTANT'")
        if not re.fullmatch(r'[0-9a-f-]{36}', assistant):
            raise RuntimeError('The first deterministic turn has no unique assistant message')
        marker = 'R63_CONTEXT_PROJECTION_' + args.case_id.replace('-', '_')
        filler = marker + ' alpha beta gamma delta epsilon '
        update = ("UPDATE pf.message SET blocks_json=jsonb_build_array(jsonb_build_object('type','TEXT','text',repeat('"
                  + filler + "',50000))),version=version+1,updated_at=clock_timestamp() WHERE id='" + assistant + "' AND role='ASSISTANT';")
        if sql(update) not in {'UPDATE 1', ''}:
            raise RuntimeError('The isolated canonical assistant fixture was not updated exactly once')
        canonical = sql("SELECT blocks_json::text FROM pf.message WHERE id='" + assistant + "'")
        state['seedMessageId'] = assistant
        state['canonicalBeforeSha256'] = hashlib.sha256(canonical.encode()).hexdigest()
        state['marker'] = marker
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')

    if state['finalTaskId'] is None:
        if state['submitPhase'] == 'SENT':
            raise RuntimeError('Final context task outcome is unknown; do not replay its request')
        index = 5
        body = {'input': {'text': state['texts'][index], 'attachments': []},
                'options': {'planMode': False, 'permissionMode': 'RISK_BASED'}}
        body_hash = hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        if state['submitPhase'] == 'REJECTED':
            rejected = state.get('submitRejection', {})
            if (rejected.get('status') != 422 or rejected.get('code') != 'CONTEXT_REQUIRED_TOO_LARGE'
                    or rejected.get('index') != index or rejected.get('requestSha256') != body_hash):
                raise RuntimeError('Only the frozen explicit context-admission rejection may be retried')
        state['submitPhase'] = 'SENT'
        state['submitIndex'] = index
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
        try:
            accepted = admin.call('POST', '/api/v1/conversations/' + state['conversationId'] + '/tasks', body,
                                  {'Idempotency-Key': state['taskKeys'][index]})
        except Exception as failure:
            status = getattr(failure, 'status', None)
            code = getattr(failure, 'code', None)
            if status in range(400, 500):
                state['submitPhase'] = 'REJECTED'
                state['submitRejection'] = {'status': status, 'code': code, 'index': index, 'requestSha256': body_hash}
                state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
            raise
        state['finalTaskId'] = accepted['taskId']
        state['submitPhase'] = 'ACKNOWLEDGED'
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
    final_task = wait_task(admin, state['finalTaskId'])
    before_restart = context(admin, state['finalTaskId'])
    projection = before_restart.get('projection')
    if not projection or int(projection.get('epoch', 0)) < 1:
        raise RuntimeError('A long canonical history did not persist a context projection')
    if int(before_restart.get('estimatedInputTokens') or 0) > int(before_restart['inputLimit']):
        raise RuntimeError('The prepared context estimate exceeded its frozen input limit')
    canonical = sql("SELECT blocks_json::text FROM pf.message WHERE id='" + state['seedMessageId'] + "'")
    canonical_after = hashlib.sha256(canonical.encode()).hexdigest()
    if canonical_after != state['canonicalBeforeSha256']:
        raise RuntimeError('Context projection rewrote a canonical chat message')

    row = json.loads(docker(['inspect', api_container]))[0]
    labels = row['Config'].get('Labels') or {}
    if labels.get('com.docker.compose.project') != 'agentplatform-r63-local' or labels.get('com.docker.compose.service') != 'api':
        raise RuntimeError('Refusing to restart a container outside the isolated R63 API')
    docker(['restart', row['Id']], timeout=90)
    wait_readiness(manifest['gatewayPort'])
    after_restart = context(admin, state['finalTaskId'])
    if after_restart.get('projection') != projection:
        raise RuntimeError('Durable projection summary changed after isolated API process restart')
    canonical = sql("SELECT blocks_json::text FROM pf.message WHERE id='" + state['seedMessageId'] + "'")
    if hashlib.sha256(canonical.encode()).hexdigest() != state['canonicalBeforeSha256']:
        raise RuntimeError('API restart altered the canonical chat history fixture')

    before_calls = state.get('providerCallsBefore')
    after_calls = provider_calls()
    if before_calls is not None and after_calls - before_calls != 6:
        raise RuntimeError('The deterministic provider call count differs from the six frozen tasks')
    receipt = {
        'status': 'PASS',
        'database': database,
        'conversationId': state['conversationId'],
        'seedTaskCount': len(state['taskIds']),
        'projectionTaskId': state['finalTaskId'],
        'taskStatus': final_task['status'],
        'projectionEpoch': projection['epoch'],
        'projectionSummarySha256': projection['summarySha256'],
        'canonicalHistoryStable': True,
        'projectionReadableAfterApiRestart': True,
        'apiContainerId': row['Id'],
        'deterministicProviderCalls': after_calls - before_calls if before_calls is not None else None,
        'supplierCalls': 0,
        'sandboxStartedLocally': False,
    }
    receipt_path.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print('R63_DURABLE_CONTEXT_PROJECTION_RESTART_PASS')


if __name__ == '__main__':
    main()
