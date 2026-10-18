"""Real local R2.1 process crash tests. Only agentplatform-r21-local; never real provider calls."""
import argparse
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from stopped_owner import reconcile
from execution_images import pin_exported

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r21-local'
OUT = DATA / 'acceptance'
ENV = dict(os.environ, R21_ROOT=DATA.as_posix(), R21_MQ_HOSTNAME='agentplatform-r21-mq',
           R21_PROVIDER_BASE='http://fixture:8080', R21_FIXTURE_PROVIDER='true')
COMPOSE = ['docker', 'compose', '-p', 'agentplatform-r21-local', '-f', 'deploy/compose.execution-local.yaml']
BASE = 'http://127.0.0.1:18088'
POINTS = ['claim-before-ack', 'ack-before-model', 'response-before-round', 'round-before-checkpoint', 'checkpoint-before-seal', 'checkpoint-after-seal']


def command(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, env=ENV, check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def sql(query):
    return command(COMPOSE + ['exec', '-T', 'postgres', 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'chat', '-c', query])


def wait(work, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = work()
        if value:
            return value
        time.sleep(.5)
    raise AssertionError('Condition timed out; inspect protected evidence')


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = self.request('GET', '/api/v1/auth/csrf')['csrfToken']
        result = self.request('POST', '/api/v1/auth/login', {'username': 'alice', 'password': (DATA / 'alice-password').read_text().strip()})
        self.csrf = result['csrfToken']

    def request(self, method, path, body=None):
        headers = {'Origin': BASE}
        if body is not None:
            headers.update({'Content-Type': 'application/json', 'X-CSRF-Token': self.csrf, 'Idempotency-Key': str(uuid.uuid4())})
        request = urllib.request.Request(BASE + path, None if body is None else json.dumps(body).encode(), headers, method=method)
        with self.opener.open(request, timeout=15) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def submit(self, prompt):
        result = self.request('POST', '/api/v1/conversations', {'title': 'R2.1 process fixture', 'initialTask': {'options': {'modelId': '0199e000-0000-7000-8000-000000000002', 'credentialSource': 'PLATFORM'}, 'input': {'text': prompt}}})
        return result['initialTask']['taskId']


def workers(point=None):
    assert sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL') == '0', 'Reconcile the current owner before replacing workers'
    command(COMPOSE + ['stop', 'worker', 'worker-2'])
    override = DATA / 'fault.override.json'
    override.write_text(json.dumps({'services': {'worker': {'environment': {'PLATFORM_CHAT_FIXTURE_FAULT': point or ''}}}}))
    args = COMPOSE + ['-f', override.as_posix()]
    command(args + ['up', '-d', '--no-deps', '--force-recreate', 'worker'])
    wait(lambda: command(['docker', 'inspect', 'agentplatform-r21-local-worker-1', '--format', '{{.State.Health.Status}}']) == 'healthy')
    inspection=json.loads(command(['docker','inspect','agentplatform-r21-local-worker-1']))[0]
    assert 'PLATFORM_CHAT_PROVIDER_BASE=http://fixture:8080' in inspection['Config']['Env']
    assert 'PLATFORM_CHAT_FIXTURE_PROVIDER=true' in inspection['Config']['Env']
    return args


def status(task):
    return sql("SELECT status FROM pf.task WHERE id='" + str(uuid.UUID(task)) + "';")


def sample():
    ids = command(COMPOSE + ['ps', '-q']).splitlines()
    inspections = json.loads(command(['docker', 'inspect', *ids]))
    assert all(row['Config']['Labels']['com.docker.compose.project'] == 'agentplatform-r21-local' for row in inspections)
    total = sum(row['HostConfig']['Memory'] for row in inspections)
    assert all(row['HostConfig']['Memory'] > 0 for row in inspections) and total <= 2887*1024*1024
    assert all(row['HostConfig']['MemorySwap'] == row['HostConfig']['Memory'] for row in inspections)
    for row in inspections:
        if row['Config']['Labels']['com.docker.compose.service'] in {'api', 'worker', 'worker-2'}:
            assert row['Config']['User'] == '10001:10001' and row['HostConfig']['ReadonlyRootfs']
            assert all('docker.sock' not in mount['Destination'] and 'stop_proof' not in mount['Destination'] for mount in row['Mounts'])
    host = command(COMPOSE + ['exec', '-T', 'postgres', 'cat', '/proc/meminfo'])
    memory = {line.split(':')[0]: int(line.split()[1])*1024 for line in host.splitlines() if line.startswith(('MemAvailable:', 'SwapTotal:', 'SwapFree:'))}
    assert memory['MemAvailable'] >= 768*1024*1024
    stats = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids])
    return {'time': time.time(), 'containerLimitBytes': total, 'host': memory, 'databaseBytes': int(sql("SELECT pg_database_size(current_database())")), 'stats': [json.loads(line) for line in stats.splitlines()],
            'containers': [{'id': row['Id'], 'image': row['Image'], 'running': row['State']['Running'], 'oom': row['State']['OOMKilled'], 'limit': row['HostConfig']['Memory']} for row in inspections]}


def crash(client, point):
    print('Starting actual worker crash window: ' + point, flush=True)
    args = workers(point)
    task = client.submit('fixture:normal:r21:' + point + ':' + str(uuid.uuid4()))
    def marker():
        result = subprocess.run(['docker', 'exec', 'agentplatform-r21-local-worker-1', 'cat', '/tmp/chat-fault-ready'], cwd=ROOT, capture_output=True, text=True)
        return result.returncode == 0 and result.stdout.strip() == point
    wait(marker)
    command(COMPOSE + ['up', '-d', '--no-deps', 'worker-2'])
    competing = client.submit('fixture:normal:competing:' + str(uuid.uuid4()))
    time.sleep(2)
    assert sql("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL") == '1'
    assert sql("SELECT count(*) FROM pf.attempt WHERE task_id='" + task + "'") == '1'
    before = {'taskId': task, 'competingTaskId': competing, 'point': point, 'status': status(task), 'sample': sample(),
              'calls': int(sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'"))}
    (OUT / (point + '-before.json')).write_text(json.dumps(before, indent=2))
    proof = reconcile(ROOT, DATA, ENV, args, force=True)
    expected = 'BLOCKED' if point == 'response-before-round' else 'SUCCEEDED'
    wait(lambda: status(task) == expected, 90)
    wait(lambda: status(competing) == 'SUCCEEDED', 90)
    assert int(sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'")) == 1
    if expected == 'BLOCKED':
        assert sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "' AND status='UNKNOWN'") == '1'
        assert sql("SELECT count(*) FROM pf.budget_reservation WHERE task_id='" + task + "' AND state='RECONCILING'") == '1'
    else:
        assert sql("SELECT count(*) FROM pf.model_turn WHERE task_id='" + task + "' AND status='COMMITTED'") == '1'
    if point == 'checkpoint-after-seal':
        assert int(sql("SELECT count(*) FROM pf.checkpoint WHERE task_id='" + task + "'")) >= 1
    assert sql('SELECT physical_calls FROM pf.acceptance_model_meter WHERE id=1') == '0'
    result = {'status': 'PASS', 'point': point, 'taskId': task, 'result': expected, 'proof': proof, 'after': sample(),
              'calls': 1, 'realProviderCalls': 0, 'generation': int(sql("SELECT generation FROM pf.execution_dispatch WHERE task_id='" + task + "'"))}
    (OUT / (point + '.json')).write_text(json.dumps(result, indent=2))
    print(json.dumps({'point': point, 'status': 'PASS', 'result': expected, 'physicalFixtureCalls': 1, 'realProviderCalls': 0}), flush=True)


def continuous(client):
    workers()
    command(COMPOSE + ['up', '-d', '--no-deps', 'worker-2'])
    samples = [sample()]
    tasks = []
    for index in range(12):
        task = client.submit('fixture:normal:sequence:' + str(uuid.uuid4()))
        tasks.append(task)
        wait(lambda: status(task) == 'SUCCEEDED')
        assert sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL') == '0'
        samples.append(sample())
    assert all(not c['oom'] for s in samples for c in s['containers'])
    assert sql('SELECT physical_calls FROM pf.acceptance_model_meter WHERE id=1') == '0'
    result = {'status': 'PASS', 'tasks': tasks, 'samples': samples, 'realProviderCalls': 0, 'note': 'Sampled extrema only; not absolute peak or higher concurrency capacity'}
    (OUT / 'continuous.json').write_text(json.dumps(result, indent=2))
    print('Sequential single-slot tasks and bounded resource samples PASS', flush=True)


def http_status(client, path):
    try:
        client.request('GET', path)
        return 200
    except urllib.error.HTTPError as error:
        return error.code


def healthy(service):
    return command(['docker', 'inspect', 'agentplatform-r21-local-' + service + '-1', '--format', '{{.State.Health.Status}}']) == 'healthy'


def outages(client):
    print('Starting actual broker/DB interruption acceptance', flush=True)
    workers()
    command(COMPOSE + ['up', '-d', '--no-deps', 'worker-2'])
    command(COMPOSE + ['stop', 'rabbitmq'])
    wait(lambda: http_status(client, '/api/v1/readiness') == 503)
    queued = client.submit('fixture:normal:broker-outage:' + str(uuid.uuid4()))
    time.sleep(3)
    assert status(queued) == 'QUEUED'
    assert sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + queued + "'") == '0'
    command(COMPOSE + ['start', 'rabbitmq'])
    wait(lambda: healthy('rabbitmq'), 120)
    wait(lambda: status(queued) == 'SUCCEEDED', 90)
    assert sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + queued + "'") == '1'
    print('Broker outage queued without PG-direct fallback, then completed once: PASS', flush=True)
    task = client.submit('fixture:slow:active-db-outage:' + str(uuid.uuid4()))
    wait(lambda: sql("SELECT count(*) FROM pf.message WHERE task_id='" + task + "' AND role='ASSISTANT' AND length(blocks_json->0->>'text')>0") == '1')
    owner = sql("SELECT owner_id FROM pf.execution_slot WHERE task_id='" + task + "'")
    owned_service = None
    for service in ['worker', 'worker-2']:
        marker = command(['docker', 'exec', 'agentplatform-r21-local-' + service + '-1', 'cat', '/tmp/chat-worker-ready'])
        if marker == owner:
            owned_service = service
    assert owned_service is not None
    command(COMPOSE + ['stop', 'postgres'])
    wait(lambda: http_status(client, '/api/v1/me') == 503, 30)
    wait(lambda: http_status(client, '/api/v1/readiness') == 503, 30)
    time.sleep(8)  # Exceed connection/socket timeout, without extending the lease or replaying work.
    command(COMPOSE + ['start', 'postgres'])
    wait(lambda: healthy('postgres'), 60)
    wait(lambda: http_status(client, '/api/v1/me') == 200, 60)
    wait(lambda: command(['docker', 'inspect', 'agentplatform-r21-local-' + owned_service + '-1', '--format', '{{.State.Health.Status}}']) == 'healthy', 60)
    if status(task) == 'RUNNING':
        proof = reconcile(ROOT, DATA, ENV, COMPOSE, service=owned_service, force=True)
        assert status(task) == 'BLOCKED'
    else:
        # The original owner may finish failure cleanup after DB recovery. Do not
        # fabricate an active slot/proof merely to force one scheduling outcome.
        assert status(task) in {'FAILED', 'CANCELLED', 'BLOCKED'}
        container = json.loads(command(['docker','inspect','agentplatform-r21-local-' + owned_service + '-1']))[0]
        command(['docker','stop','--time','15',container['Id']])
        after = json.loads(command(['docker','inspect',container['Id']]))[0]
        assert not after['State']['Running'] and after['State']['StartedAt'] == container['State']['StartedAt']
        proof = {'kind':'already-terminal-before-stop','owner':owner,'container':container['Id'],'image':container['Image'],'stoppedAt':after['State']['FinishedAt']}
    assert sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "' AND status='UNKNOWN'") == '1'
    assert sql("SELECT count(*) FROM pf.budget_reservation WHERE task_id='" + task + "' AND state='RECONCILING'") == '1'
    followup = client.submit('fixture:normal:db-restored:' + str(uuid.uuid4()))
    wait(lambda: status(followup) == 'SUCCEEDED', 90)
    assert sql('SELECT physical_calls FROM pf.acceptance_model_meter WHERE id=1') == '0'
    result = {'status': 'PASS', 'brokerQueuedTask': queued, 'interruptedTask': task, 'restoredTask': followup, 'proof': proof, 'sample': sample(), 'realProviderCalls': 0}
    (OUT / 'outages.json').write_text(json.dumps(result, indent=2))
    print('Active DB outage failed closed, retained UNKNOWN, restored session and single-slot execution: PASS', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=POINTS + ['crashes', 'continuous', 'outages'])
    args = parser.parse_args()
    pin_exported(ENV, DATA, required=True)
    OUT.mkdir(parents=True, exist_ok=True)
    client = Client()
    if args.mode == 'continuous':
        continuous(client)
    elif args.mode == 'outages':
        outages(client)
    else:
        for point in POINTS if args.mode == 'crashes' else [args.mode]:
            crash(client, point)


if __name__ == '__main__':
    main()
