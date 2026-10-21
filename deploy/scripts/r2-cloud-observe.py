"""Fixed-candidate cloud smoke/1800s observation, one new supplier task maximum.

Credentials come only from the existing protected account file. Persist command
identity before a submission; network ambiguity never generates another task.
"""
import argparse
import fcntl
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import uuid

SPEC = importlib.util.spec_from_file_location('r2window', Path(__file__).with_name('r2-release-control.py'))
CONTROL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTROL)
ROOT = CONTROL.ROOT


def foreign_sse_denied(session, conversation):
    assert session.request('GET', '/conversations/' + conversation + '/events?after=0', stream=True)[0] == 404


def validate_reused_task(proof, detail, usage, configuration, source):
    assert proof['status'] == 'PASS' and proof['applicationSource'] == source
    for key in ('taskId', 'conversationId'):
        assert str(uuid.UUID(proof[key])) == proof[key]
        assert detail[key] == proof[key], 'Reused task identity changed'
    assert detail['status'] == proof['taskStatus'] == 'SUCCEEDED', 'Only definitive success can be reused'
    assert proof['model'] == configuration['values']['modelName'] == 'deepseek-flash'
    assert usage['items'] == proof['usage'], 'Persisted usage differs from prior evidence'
    assert 1 <= len(proof['usage']) <= 4 and proof['physicalCalls'] == len(proof['usage'])
    assert len({item['callId'] for item in proof['usage']}) == len(proof['usage'])
    assert all(item['status'] == 'COMPLETED' and item['inputTokens'] is not None and item['outputTokens'] is not None for item in proof['usage'])


class Session:
    def __init__(self, host):
        self.host = host
        self.csrf = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, method, path, body=None, key=None, stream=False):
        headers = {'Host': self.host, 'Accept': 'text/event-stream' if stream else 'application/json'}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        if method != 'GET':
            headers.update({'Origin': 'http://' + self.host, 'X-CSRF-Token': self.csrf})
        if key:
            headers['Idempotency-Key'] = key
        try:
            response = self.opener.open(urllib.request.Request('http://127.0.0.1/api/v1' + path, method=method, data=data, headers=headers), timeout=35 if stream else 12)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            if stream:
                return response.status, raw.decode()
            return response.status, json.loads(raw) if raw else None

    def login(self, account):
        code, value = self.request('GET', '/auth/csrf')
        assert code == 200
        self.csrf = value['csrfToken']
        code, value = self.request('POST', '/auth/login', {'username': account['username'], 'password': account['password']})
        assert code == 200, 'Protected account login failed; values withheld'
        self.csrf = value['csrfToken']
        assert self.request('GET', '/me')[0] == 200


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('window', type=Path)
    parser.add_argument('--seconds', type=int, default=1800)
    parser.add_argument('--reuse-task-proof', type=Path)
    args = parser.parse_args()
    assert args.seconds >= 1800
    os.umask(0o077)
    window = CONTROL.Window(args.window)
    with (window.folder / 'window.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        observe(window, args.seconds, args.reuse_task_proof)


def observe(window, seconds, reuse=None):
    window.done('activate')
    assert not (window.folder / 'cloud-submission.json').exists(), 'Never replay an ambiguous prior submission'
    accounts = json.loads((ROOT / 'operations/r1-accounts/accounts.json').read_text())['accounts']
    owner = next(a for a in accounts if a['username'] == 'r1acceptance')
    other = next(a for a in accounts if a['username'] != 'r1acceptance')
    user = Session('124.222.55.140'); user.login(owner)
    foreign = Session('agentplatform.lailihao.cloud'); foreign.login(other)
    start = time.monotonic()
    code, catalog = user.request('GET', '/models')
    assert code == 200
    reused = None
    if reuse:
        reuse = reuse.resolve(strict=True)
        assert reuse.is_relative_to(ROOT / 'operations') and reuse.name == 'prior-task-proof.json' and reuse.stat().st_size <= 131072
        proof = json.loads(reuse.read_text())
        task = str(uuid.UUID(proof['taskId'])); conversation = str(uuid.UUID(proof['conversationId']))
        code, current = user.request('GET', '/tasks/' + task); assert code == 200
        code, existing_usage = user.request('GET', '/tasks/' + task + '/usage'); assert code == 200
        code, config = user.request('GET', '/tasks/' + task + '/configuration'); assert code == 200
        validate_reused_task(proof, current, existing_usage, config, window.admission['applicationSource'])
        reused = dict(proofSha256=hashlib.sha256(reuse.read_bytes()).hexdigest(), taskId=task,
                      historicalPhysicalCalls=proof['physicalCalls'], newSupplierCalls=0)
        window.receipt('cloud-submission', dict(status='REUSED_DEFINITIVE_TASK_NO_SUBMISSION', **reused))
        window.receipt('cloud-task', dict(status='REUSED_SUCCEEDED', taskId=task, conversationId=conversation))
    else:
        task, conversation = submit_one_task(window, user)
    code, current = user.request('GET', '/tasks/' + task)
    assert code == 200 and current['status'] == 'SUCCEEDED'
    code, usage = user.request('GET', '/tasks/' + task + '/usage')
    assert code == 200 and usage['items'] and len(usage['items']) <= 4
    assert all(c['inputTokens'] is not None and c['outputTokens'] is not None for c in usage['items'])
    for path in ('/tasks/' + task, '/tasks/' + task + '/diagnostics', '/tasks/' + task + '/usage',
                 '/tasks/' + task + '/configuration', '/tasks/' + task + '/reconciliation', '/tasks/' + task + '/recovery',
                 '/conversations/' + conversation, '/conversations/' + conversation + '/snapshot'):
        assert user.request('GET', path)[0] == 200
        assert foreign.request('GET', path)[0] == 404, 'Foreign/admin content access refused'
    foreign_sse_denied(foreign, conversation)
    for session in (user, foreign):
        for path in ('/../actuator/prometheus', '/../internal/dependencies'):
            code, _ = session.request('GET', path)
            assert code in (400, 401, 403, 404)
    code, events = user.request('GET', '/conversations/' + conversation + '/events?after=0', stream=True)
    assert code == 200
    seq = [int(line.removeprefix('id:').strip()) for line in events.splitlines() if line.startswith('id:')]
    assert seq and seq == sorted(set(seq))
    code, tail = user.request('GET', '/conversations/' + conversation + '/events?after=' + str(seq[-1]), stream=True)
    assert code == 200 and not any(line.startswith('id:') for line in tail.splitlines()), 'Committed cursor replay duplicated events'
    window.receipt('http-smoke', dict(status='PASS', taskId=task, conversationId=conversation, model='deepseek-flash', usage=usage['items'], newTaskSubmissions=0 if reused else 1, physicalCalls=0 if reused else len(usage['items']), reusedTask=reused, dualUserAndAdminIsolation=True, readOnlyRecoveryAndReconciliation=True, committedSseEventCount=len(seq), sseResumeNoDuplicate=True, dnsTested=False))
    observe_resources(window, seconds, user, task, usage, start, reused)


def submit_one_task(window, user):
    # Existing production directory identity is frozen by the accepted R1/R2 contracts.
    body = {'title': 'R2发行冒烟', 'initialTask': {'input': {'text': '只回复：验证通过', 'attachments': []},
            'options': {'modelId': '0199e000-0000-7000-8000-000000000002', 'credentialSource': 'PLATFORM', 'planMode': False, 'permissionMode': 'RISK_BASED'}}}
    key = str(uuid.uuid4())
    window.receipt('cloud-submission', dict(status='RECORDED_NOT_YET_CONFIRMED', idempotencyKey=key, request=body))
    code, created = user.request('POST', '/conversations', body, key)
    assert code == 201, 'Submission refused; stop supplier calls, inspect private status'
    task = created['initialTask']['taskId']; conversation = created['id']
    window.receipt('cloud-task', dict(status='ACCEPTED', taskId=task, conversationId=conversation))
    deadline = time.monotonic() + 135
    while time.monotonic() < deadline:
        code, current = user.request('GET', '/tasks/' + task)
        assert code == 200
        if current['status'] not in ('QUEUED', 'RUNNING'):
            break
        time.sleep(1)
    assert current['status'] == 'SUCCEEDED', 'Supplier task failed/unknown/quota exhausted: no replacement request'
    return task, conversation


def observe_resources(window, seconds, user, task, usage, start, reused):
    samples = []; faults = []
    injected = set()
    def snapshot():
        ids = []
        for project in ('agentplatform-app', 'agentplatform-env'):
            ids += CONTROL.output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=' + project]).split()
        values = json.loads(CONTROL.output(['docker', 'inspect', *ids]))
        services = {(v['Config']['Labels']['com.docker.compose.project'], v['Config']['Labels']['com.docker.compose.service']) for v in values}
        assert services == ({('agentplatform-app', x) for x in CONTROL.APP} | {('agentplatform-env', x) for x in CONTROL.ENV})
        assert all(v['State']['Running'] and not v['State']['OOMKilled'] and v['State'].get('Health', {}).get('Status', 'healthy') == 'healthy' for v in values)
        assert all(v['HostConfig']['Memory'] == v['HostConfig']['MemorySwap'] for v in values)
        total = sum(v['HostConfig']['Memory'] for v in values)
        assert total == window.admission['effectiveLimitMiB'] * 1024**2
        window.capacity()
        assert int(window.sql("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL;")) <= 1
        mem = {line.split(':')[0]: int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines() if len(line.split()) >= 2}
        stats = [json.loads(line) for line in CONTROL.output(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids]).splitlines()]
        return dict(elapsedSeconds=round(time.monotonic() - start, 3), memAvailableKiB=mem['MemAvailable'], swapUsedKiB=mem['SwapTotal'] - mem['SwapFree'], rootAvailableBytes=shutil.disk_usage(ROOT).free,
                    databaseBytes=int(window.sql("SELECT pg_database_size(current_database());")), stats=stats)
    while time.monotonic() - start < seconds:
        elapsed = time.monotonic() - start
        for name, threshold in (('rabbitmq', 120), ('postgres', 300)):
            if elapsed < threshold or name in injected:
                continue
            assert window.sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL;') == '0'
            cid = CONTROL.output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-env', '--filter', 'label=com.docker.compose.service=' + name])
            CONTROL.run(['docker', 'stop', '-t', '20', cid])
            try:
                code, _ = user.request('GET', '/readiness')
                assert code == 503
                if name == 'postgres':
                    assert user.request('GET', '/me')[0] == 503
            finally:
                CONTROL.run(['docker', 'start', cid])
            recovered = time.monotonic() + 180
            while time.monotonic() < recovered:
                if user.request('GET', '/readiness')[0] == 200:
                    ids = CONTROL.output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-env']).split()
                    states = json.loads(CONTROL.output(['docker', 'inspect', *ids]))
                    if all(v['State'].get('Health', {}).get('Status', 'healthy') == 'healthy' for v in states):
                        break
                time.sleep(2)
            else:
                raise RuntimeError('Dependency recovery deadline exceeded')
            injected.add(name)
            faults.append(dict(dependency=name, failureReadiness=503, recovered=True, elapsedSeconds=round(time.monotonic() - start, 3)))
        assert user.request('GET', '/tasks/' + task + '/recovery')[0] == 200
        samples.append(snapshot())
        progress = dict(status='RUNNING', elapsedSeconds=round(time.monotonic() - start, 3), samples=len(samples), dependencyFaults=faults)
        (window.folder / 'observation-progress.json').write_text(json.dumps(progress))
        time.sleep(min(10, max(0, seconds - (time.monotonic() - start))))
    samples.append(snapshot())
    assert injected == {'rabbitmq', 'postgres'}
    window.receipt('observation', dict(status='PASS', durationSeconds=round(time.monotonic() - start, 3), samples=samples, dependencyFaults=faults, newTaskSubmissions=0 if reused else 1, supplierUsage=usage['items'], reusedTask=reused, scope='Sampled one-slot production environment and periodic completed-task read workload; not continuous model execution, absolute peaks or dual concurrency'))
    print('PASS HTTP chat/SSE/isolation and >=1800s R2 observation; supplier evidence reused=' + str(bool(reused)))


if __name__ == '__main__':
    main()
