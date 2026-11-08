"""Focused real-API R6.3 memory expiry, concurrency, and ownership checks.

Uses only the isolated R63 fixture. Expiry is advanced directly in its test
database because the product TTLs are 30/90/180 days; no production data is
accessed and this script never sends provider requests.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache' / 'r63-local'
PRIVATE = DATA / 'fixture'
manifest = json.loads((PRIVATE / 'manifest.json').read_text(encoding='utf-8'))
database = manifest['database']
postgres = 'agentplatform-r63-local-postgres-1'
receipt_path = DATA / f'memory-matrix-{database}.json'


def load_client():
    spec = importlib.util.spec_from_file_location('r63_knowledge_acceptance', ROOT / 'deploy/scripts/r63-knowledge-acceptance.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Client


def sql(statement):
    result = subprocess.run(['docker', 'exec', '-i', postgres, 'psql', '-v', 'ON_ERROR_STOP=1', '-X', '-A', '-t',
                             '-U', 'postgres', '-d', database], input=statement, text=True,
                           capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError('Isolated memory fixture SQL failed; inspect protected process output')
    return result.stdout.strip()


def main():
    if receipt_path.exists():
        raise RuntimeError('Memory matrix receipt is immutable once written')
    Client = load_client()
    admin = Client('r63_admin')
    user = Client('r63_user')
    if admin.me['id'] == user.me['id'] or admin.space == user.space:
        raise RuntimeError('Fixture accounts must have separate user and personal-space identities')

    workspace_rows = sql("SELECT id::text||'|'||actor_id::text||'|'||space_id::text FROM pf.workspace WHERE actor_id='" + admin.me['id'] + "' AND space_id='" + admin.space + "' ORDER BY created_at LIMIT 1")
    if not workspace_rows:
        raise RuntimeError('Admin-owned workspace fixture is required for scope checks')
    workspace_id = workspace_rows.split('|')[0]

    def candidate(client, category, topic, scope='PERSONAL', workspace=None):
        body = {'scopeKind': scope, 'workspaceId': workspace, 'category': category,
                'topicKey': topic, 'content': 'R63 isolated memory check ' + topic}
        return client.call('POST', '/api/v1/me/memories/candidates', body,
                           {'Idempotency-Key': str(uuid.uuid4())})

    def action(client, row, name):
        return client.call('POST', '/api/v1/me/memories/' + row['id'] + '/actions',
                           {'action': name, 'expectedRevision': row['revision']},
                           {'Idempotency-Key': str(uuid.uuid4())})

    workspace_pending = candidate(admin, 'FACT', 'r63-workspace-scope-' + uuid.uuid4().hex,
                                  'WORKSPACE', workspace_id)
    workspace_active = action(admin, workspace_pending, 'CONFIRM')
    if workspace_active['status'] != 'ACTIVE' or workspace_active['workspaceId'] != workspace_id:
        raise AssertionError('A memory scoped to the owning workspace did not activate')

    try:
        candidate(user, 'FACT', 'r63-foreign-workspace-' + uuid.uuid4().hex, 'WORKSPACE', workspace_id)
    except Exception as error:
        if getattr(error, 'status', None) != 404 or getattr(error, 'code', None) != 'MEMORY_SCOPE_NOT_FOUND':
            raise
    else:
        raise AssertionError('A second account was allowed to create memory in the admin workspace')

    admin_personal = action(admin, candidate(admin, 'FACT', 'r63-admin-personal-' + uuid.uuid4().hex), 'CONFIRM')
    user_personal = action(user, candidate(user, 'FACT', 'r63-user-personal-' + uuid.uuid4().hex), 'CONFIRM')
    admin_rows = admin.call('GET', '/api/v1/me/memories?status=ACTIVE')
    user_rows = user.call('GET', '/api/v1/me/memories?status=ACTIVE')
    if not any(row['id'] == admin_personal['id'] for row in admin_rows):
        raise AssertionError('Admin cannot read own personal memory')
    if not any(row['id'] == user_personal['id'] for row in user_rows):
        raise AssertionError('Restricted account cannot read own personal memory')
    if any(row['id'] == user_personal['id'] for row in admin_rows) or any(row['id'] == admin_personal['id'] for row in user_rows):
        raise AssertionError('Personal memory crossed account boundary')

    topic = 'r63-concurrent-topic-' + uuid.uuid4().hex
    first, second = candidate(user, 'PREFERENCE', topic), candidate(user, 'PREFERENCE', topic)
    barrier = __import__('threading').Barrier(2)
    # Authenticate serially because the isolated profile intentionally has one
    # password-hash slot. Only the state-changing confirmations are concurrent.
    confirm_clients = [Client('r63_user'), Client('r63_user')]

    def confirm_concurrently(pair):
        row, client = pair
        barrier.wait(timeout=15)
        try:
            result = action(client, row, 'CONFIRM')
            return result['status']
        except Exception as error:
            if getattr(error, 'status', None) in (409, 412):
                return 'CONFLICT'
            raise

    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(confirm_concurrently, zip([first, second], confirm_clients)))
    active_topic = [row for row in user.call('GET', '/api/v1/me/memories?status=ACTIVE') if row['topicKey'] == topic]
    if len(active_topic) != 1 or any(value not in ('ACTIVE', 'CONFLICT') for value in outcomes):
        raise AssertionError('Concurrent confirmation did not preserve exactly one active topic')

    expired_fact = action(user, candidate(user, 'FACT', 'r63-expiry-fact-' + uuid.uuid4().hex), 'CONFIRM')
    expired_preference = action(user, candidate(user, 'PREFERENCE', 'r63-expiry-pref-' + uuid.uuid4().hex), 'CONFIRM')
    changed = sql("UPDATE pf.personal_memory SET expires_at=clock_timestamp()-interval '1 second' WHERE id IN ('" +
                  expired_fact['id'] + "','" + expired_preference['id'] + "') AND status='ACTIVE';\nSELECT count(*) FROM pf.personal_memory WHERE id IN ('" +
                  expired_fact['id'] + "','" + expired_preference['id'] + "') AND expires_at<=clock_timestamp();")
    if changed.splitlines()[-1] != '2':
        raise AssertionError('Could not advance only the two isolated memory expirations')
    user.call('GET', '/api/v1/me/memories?status=ALL')
    expired_rows = {row['id']: row for row in user.call('GET', '/api/v1/me/memories?status=ALL')}
    if expired_rows[expired_fact['id']]['status'] != 'EXPIRED':
        raise AssertionError('Expired FACT was not transitioned using database time')
    if expired_rows[expired_preference['id']]['status'] != 'REVIEW_REQUIRED':
        raise AssertionError('Expired PREFERENCE was not moved to review')
    audit = sql("SELECT count(*) FROM pf.personal_memory_audit WHERE memory_id IN ('" + expired_fact['id'] + "','" +
                expired_preference['id'] + "') AND action IN ('EXPIRE','REVIEW');")
    if audit != '2':
        raise AssertionError('Expiry did not create the expected audit facts')

    receipt = {
        'status': 'PASS', 'database': database, 'supplierCalls': 0,
        'assertions': [
            'valid workspace memory accepted for owner',
            'foreign workspace memory candidate denied',
            'admin and restricted-account personal memories remain isolated',
            'concurrent same-topic confirmation leaves exactly one ACTIVE item',
            'database-clock FACT expiry transitions to EXPIRED with audit',
            'database-clock PREFERENCE expiry transitions to REVIEW_REQUIRED with audit',
        ],
        'concurrentConfirmOutcomes': outcomes,
        'activeRowsForConcurrentTopic': len(active_topic),
        'expiryAuditRows': int(audit),
    }
    receipt_path.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print('R63_MEMORY_SCOPE_CONCURRENCY_EXPIRY_PASS')


if __name__ == '__main__':
    main()
