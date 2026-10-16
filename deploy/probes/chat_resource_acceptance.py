"""Bounded local chat/login and exact-worker OOM recovery checks. Fixture only."""
import concurrent.futures
import json
import subprocess
import sys
import threading
import time
import uuid

from chat_failure_acceptance import admin, compose
from chat_local_smoke import Client, DATA, ROOT


def inspect(name):
    return json.loads(subprocess.check_output(['docker', 'inspect', name], cwd=ROOT))[0]


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'continuous'
    config = json.loads(subprocess.check_output(admin.COMPOSE + ['--profile', 'fixture', 'config', '--format', 'json'], cwd=ROOT, env=admin.ENV))
    assert str(config['services']['worker']['environment']['PLATFORM_CHAT_FIXTURE_PROVIDER']).lower() == 'true'
    names = ['agentplatform-r12-local-' + service + '-1' for service in ['api', 'worker', 'postgres', 'rabbitmq', 'redis', 'gateway', 'fixture']]
    identities = [inspect(name) for name in names]
    assert sum(i['HostConfig']['Memory'] for i in identities) <= 2887 * 1024 * 1024
    assert all(i['HostConfig']['MemorySwap'] == i['HostConfig']['Memory'] for i in identities)
    client = Client('alice')
    result = {'mode': mode, 'container_cap_bytes': sum(i['HostConfig']['Memory'] for i in identities),
              'host_target_mib': 3655, 'reserved_host_mib': 768,
              'note': 'Docker Desktop engine has more physical memory; this validates container caps, not cloud host capacity or absolute peaks.'}
    if mode == 'continuous':
        done = threading.Event(); samples = []; sampling_errors = []

        def sample():
            while not done.is_set():
                try:
                    raw = subprocess.check_output(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *names], cwd=ROOT, text=True)
                    samples.append({'elapsed': round(time.monotonic() - start, 2), 'containers': [json.loads(row) for row in raw.splitlines()]})
                    assert '(0 rows)' in admin.sql("SELECT 1 FROM pf.task HAVING count(*) FILTER (WHERE status='RUNNING')>1;")
                except Exception as error:
                    sampling_errors.append(type(error).__name__)
                done.wait(0.5)

        start = time.monotonic(); watcher = threading.Thread(target=sample); watcher.start()
        latencies = []
        try:
            for index in range(8):
                task = client.submit(client.conversation(), 'fixture:normal:resource:' + str(uuid.uuid4()))['taskId']
                if index < 4:
                    before = time.monotonic(); Client('bob'); latencies.append(round(time.monotonic() - before, 3))
                assert client.terminal(task)['status'] == 'SUCCEEDED'
            # Explicit running cancellation and a subsequent successful task prove cleanup.
            task = client.submit(client.conversation(), 'fixture:slow:resource:' + str(uuid.uuid4()))['taskId']
            until = time.monotonic() + 10
            while client.request('GET', '/tasks/' + task)[1]['status'] == 'QUEUED':
                assert time.monotonic() < until; time.sleep(0.1)
            assert client.request('POST', '/tasks/' + task + '/cancel', {}, str(uuid.uuid4()))[0] == 202
            assert client.terminal(task)['status'] == 'CANCELLED'
            next_task = client.submit(client.conversation(), 'fixture:normal:after-cancel:' + str(uuid.uuid4()))['taskId']
            assert client.terminal(next_task)['status'] == 'SUCCEEDED'
        finally:
            done.set(); watcher.join()
        assert samples and not sampling_errors
        assert all(inspect(name)['State']['Running'] and not inspect(name)['State']['OOMKilled'] for name in names)
        result.update({'samples': samples, 'sequential_tasks': 8, 'login_overlap_seconds': latencies, 'cancel_then_next_task': 'PASS',
                       'duration_seconds': round(time.monotonic() - start, 2), 'sampling_errors': sampling_errors})
    elif mode == 'oom':
        name = 'agentplatform-r12-local-worker-1'; before = inspect(name)
        assert before['Config']['Labels']['com.docker.compose.project'] == 'agentplatform-r12-local'
        owner = str(uuid.UUID(compose('exec', '-T', 'worker', 'cat', '/tmp/chat-worker-ready').stdout.strip()))
        task = client.submit(client.conversation(), 'fixture:slow:oom:' + str(uuid.uuid4()))['taskId']
        deadline = time.monotonic() + 12
        while '(0 rows)' in admin.sql("SELECT 1 FROM pf.model_call WHERE task_id='" + task + "' AND status='STARTED';"):
            assert time.monotonic() < deadline; time.sleep(0.2)
        try:
            admin.run(['docker', 'update', '--memory', '64m', '--memory-swap', '64m', name], capture_output=True)
            deadline = time.monotonic() + 20
            while inspect(name)['State']['Running'] and time.monotonic() < deadline:
                time.sleep(0.5)
            after = inspect(name)
            assert after['State']['OOMKilled'] and not after['State']['Running'], 'Expected actual cgroup OOM, not an injected process exit'
            assert after['Image'] == before['Image'] and after['Id'] == before['Id']
        finally:
            admin.run(['docker', 'update', '--memory', str(before['HostConfig']['Memory']), '--memory-swap', str(before['HostConfig']['MemorySwap']), name], capture_output=True)
        assert client.request('GET', '/health')[0] == 200
        # A dead worker is proven above; no replacement starts until its lease expires and the ledger is reconciled.
        deadline = time.monotonic() + 65
        while '(1 row)' in admin.sql("SELECT 1 FROM pf.execution_slot WHERE owner_id='" + owner + "' AND lease_until>now();"):
            assert time.monotonic() < deadline; time.sleep(1)
        admin.operator(['--chat-action=reconcile-stopped-owner', '--owner-id=' + owner, '--stopped-owner-confirmed'])
        assert client.request('GET', '/tasks/' + task)[1]['status'] == 'FAILED'
        assert '(1 row)' in admin.sql("SELECT 1 FROM pf.model_call WHERE task_id='" + task + "' AND status='UNKNOWN';")
        assert '(1 row)' in admin.sql("SELECT 1 FROM pf.budget_reservation WHERE task_id='" + task + "' AND state='RECONCILING';")
        compose('up', '-d', '--wait', '--wait-timeout', '120', 'worker')
        next_task = client.submit(client.conversation(), 'fixture:normal:after-oom:' + str(uuid.uuid4()))['taskId']
        assert client.terminal(next_task)['status'] == 'SUCCEEDED'
        result.update({'actual_cgroup_oom': True, 'fault_memory_bytes': 64 * 1024 * 1024,
                       'original_worker_cap_restored_bytes': before['HostConfig']['Memory'],
                       'interrupted_call': 'UNKNOWN', 'reservation': 'RECONCILING', 'replay': False, 'next_task': 'SUCCEEDED'})
    else:
        raise ValueError('Unknown resource mode')
    result['status'] = 'PASS'
    (DATA / ('resource-' + mode + '.json')).write_text(json.dumps(result, indent=2), encoding='utf-8')
    print('PASS resource', mode, flush=True)


if __name__ == '__main__':
    main()
