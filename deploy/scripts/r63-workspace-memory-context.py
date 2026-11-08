"""Real deterministic API check that a bound workspace memory reaches a task.

Uses only the isolated R6.3 acceptance stack, its admin fixture and provider;
no live model or cloud production resource is touched.
"""
from pathlib import Path
import importlib.util
import argparse
import json
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r63-local'
PRIVATE = DATA / 'fixture'
manifest = json.loads((PRIVATE / 'manifest.json').read_text(encoding='utf-8'))
database = manifest['database']
postgres = 'agentplatform-r63-local-postgres-1'


def client_type():
    spec = importlib.util.spec_from_file_location('r63_knowledge_acceptance', ROOT / 'deploy/scripts/r63-knowledge-acceptance.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Client


def sql(statement):
    result = subprocess.run(['docker', 'exec', '-i', postgres, 'psql', '-v', 'ON_ERROR_STOP=1', '-X', '-A', '-t',
                             '-U', 'postgres', '-d', database], input=statement, text=True,
                           capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError('Workspace-memory fixture query failed; inspect protected process output')
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--case-id', default='default', help='Immutable fixture run identity; use a new value after a terminal task result')
    case_id = parser.parse_args().case_id
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,31}', case_id):
        raise RuntimeError('Case identity must be a short lowercase identifier')
    state_path = DATA / f'workspace-memory-context-input-{database}-{case_id}.json'
    receipt_path = DATA / f'workspace-memory-context-result-{database}-{case_id}.json'
    Client = client_type()
    admin = Client('r63_admin')
    if receipt_path.exists():
        raise RuntimeError('Workspace-memory task receipt is immutable once written')
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding='utf-8'))
    else:
        marker = 'R63_WORKSPACE_MEMORY_' + uuid.uuid4().hex
        state = {
            'database': database,
            'workspaceId': None,
            'marker': marker,
            'candidateKey': str(uuid.uuid4()),
            'confirmKey': str(uuid.uuid4()),
            'deleteKey': str(uuid.uuid4()),
            'conversationKey': str(uuid.uuid4()),
            'conversationRequest': {
                'title': 'R63 workspace memory context check',
                'initialTask': {'input': {'text': 'fixture:r63-context-evidence ' + marker, 'attachments': []},
                                'options': {'planMode': False, 'permissionMode': 'RISK_BASED'}},
            },
            'conversationId': None,
            'taskId': None,
            'candidateId': None,
        }
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')

    workspace_query = ("SELECT id::text FROM pf.workspace WHERE actor_id='" + admin.me['id'] + "' AND space_id='" +
                       admin.space + "' AND state='READY' ORDER BY created_at LIMIT 1;")
    rows = sql(workspace_query).splitlines()
    if len(rows) != 1:
        raise RuntimeError('The isolated admin fixture needs one READY owned workspace')
    workspace_id = rows[0]
    if state['workspaceId'] not in (None, workspace_id):
        raise RuntimeError('Frozen workspace identity changed; refusing to rebind the test input')
    state['workspaceId'] = workspace_id
    state['conversationRequest']['initialTask']['options']['workspaceId'] = workspace_id
    state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')

    # Keep exactly one R63 memory eligible for this deterministic task. Cleanup
    # is restricted to synthetic topics in the isolated admin personal space.
    for item in admin.call('GET', '/api/v1/me/memories?status=ACTIVE'):
        topic=item['topicKey'].casefold()
        if (topic.startswith('r63-') or topic.startswith('r63_workspace_memory_')) and topic != state['marker'].casefold():
            admin.call('POST', '/api/v1/me/memories/' + item['id'] + '/actions',
                       {'action': 'DELETE', 'expectedRevision': item['revision']},
                       {'Idempotency-Key': str(uuid.uuid4())})

    if not state.get('candidateId'):
        candidate = admin.call('POST', '/api/v1/me/memories/candidates',
            {'scopeKind': 'WORKSPACE', 'workspaceId': workspace_id, 'category': 'FACT',
             'topicKey': state['marker'], 'content': 'Task-local workspace fact: ' + state['marker']},
            {'Idempotency-Key': state['candidateKey']})
        state['candidateId'] = candidate['id']
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
    candidate = admin.call('GET', '/api/v1/me/memories?status=ALL&workspaceId=' + workspace_id)
    row = next((item for item in candidate if item['id'] == state['candidateId']), None)
    if row is None:
        raise RuntimeError('Frozen workspace memory candidate cannot be queried')
    if row['status'] == 'PENDING':
        row = admin.call('POST', '/api/v1/me/memories/' + row['id'] + '/actions',
                         {'action': 'CONFIRM', 'expectedRevision': row['revision']},
                         {'Idempotency-Key': state['confirmKey']})
    if row['status'] != 'ACTIVE' or row['workspaceId'] != workspace_id:
        raise RuntimeError('Workspace memory did not become active in the bound workspace')

    if not state.get('taskId'):
        created = admin.call('POST', '/api/v1/conversations', state['conversationRequest'],
                             {'Idempotency-Key': state['conversationKey']})
        state['conversationId'] = created['id']
        state['taskId'] = created['initialTask']['taskId']
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
    permission = admin.call('GET', '/api/v1/tasks/' + state['taskId'] + '/permissions')
    if permission.get('workspaceId') != workspace_id:
        raise RuntimeError('Task did not freeze the selected workspace binding')

    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        task = admin.call('GET', '/api/v1/tasks/' + state['taskId'])
        if task['status'] == 'SUCCEEDED':
            break
        if task['status'] in {'FAILED', 'BLOCKED', 'CANCELLED'}:
            raise RuntimeError('Workspace-memory task ended in ' + task['status'] + ':' + str(task.get('reasonCode')))
        time.sleep(1)
    else:
        raise RuntimeError('The existing workspace-memory task remains pending; do not submit another task')

    snapshot = admin.call('GET', '/api/v1/conversations/' + state['conversationId'] + '/snapshot')
    text = '\n'.join(block.get('text', '') for message in snapshot['messages']
                     if message['role'] == 'ASSISTANT' and message['taskId'] == state['taskId']
                     for block in message['blocks'] if block['type'] == 'TEXT')
    observed_match = re.search(r'context-evidence-memory=(\d+);knowledge=(\d+)', text)
    active_workspace_memories = admin.call('GET', '/api/v1/me/memories?status=ACTIVE&workspaceId=' + workspace_id)
    if (observed_match is None or int(observed_match.group(1)) != 1
            or [item['id'] for item in active_workspace_memories] != [state['candidateId']]):
        raise RuntimeError('Provider did not observe exactly the bound workspace-memory evidence')
    observed = observed_match.group(0)

    deleted = admin.call('POST', '/api/v1/me/memories/' + state['candidateId'] + '/actions',
                         {'action': 'DELETE', 'expectedRevision': row['revision']},
                         {'Idempotency-Key': state['deleteKey']})
    if deleted['status'] != 'DELETED':
        raise RuntimeError('Workspace memory fixture cleanup did not reach DELETED')
    receipt = {'status': 'PASS', 'database': database, 'taskId': state['taskId'],
               'taskStatus': task['status'], 'workspaceBindingVerified': True,
               'activeWorkspaceMemoryCount': 1, 'providerObservation': observed,
               'memorySourceKind': 'WORKSPACE_MEMORY', 'memoryDeletedAfterCheck': True,
               'supplierCalls': 0}
    receipt_path.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print('R63_BOUND_WORKSPACE_MEMORY_CONTEXT_PASS')


if __name__ == '__main__':
    main()
