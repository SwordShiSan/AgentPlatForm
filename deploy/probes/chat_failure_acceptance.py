"""Serial real-API dependency/failure checks against the isolated deterministic fixture.

Never run this while the worker is configured for a real provider. No real keys are read.
"""
import importlib.util
import json
import subprocess
import sys
import time
import uuid

from chat_local_smoke import Client, DATA, ROOT

spec = importlib.util.spec_from_file_location('chat_fixture_admin', ROOT / 'deploy/scripts/chat-local.py')
admin = importlib.util.module_from_spec(spec)
sys.path.insert(0, str(ROOT / 'deploy/scripts'))
spec.loader.exec_module(admin)


def compose(*args):
    return admin.run(admin.COMPOSE + list(args), capture_output=True, text=True)


def fixture_counts():
    result = compose('exec', '-T', 'fixture', 'python', '-c',
                     "import urllib.request; print(urllib.request.urlopen('http://localhost:8080/status').read().decode())")
    return json.loads(result.stdout)['scenarios']


def wait_ready(client, timeout=100):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        try:
            if client.request('GET', '/readiness')[0] == 200:
                return
        except Exception:
            pass
        time.sleep(1)
    raise AssertionError('Dependency did not recover')


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'provider'
    config = json.loads(subprocess.check_output(admin.COMPOSE + ['--profile', 'fixture', 'config', '--format', 'json'], cwd=ROOT, env=admin.ENV))
    env = config['services']['worker']['environment']
    assert str(env['PLATFORM_CHAT_FIXTURE_PROVIDER']).lower() == 'true'
    assert env['PLATFORM_CHAT_PROVIDER_BASE'] == 'http://fixture:8080'
    # Keep repeated deterministic UNKNOWN reservations in Bob's fixture budget;
    # Alice owns the separately capped real-provider acceptance evidence.
    client = Client('bob' if mode == 'provider' else 'alice')
    results = []
    if mode == 'provider':
        cases = [('429', 'SUCCEEDED', 3), ('5xx', 'SUCCEEDED', 3), ('empty', 'FAILED', 2),
                 ('truncated', 'FAILED', 1), ('partial', 'FAILED', 1), ('missing-usage', 'SUCCEEDED', 1),
                 ('timeout', 'FAILED', 1)]
        for name, expected, count in cases:
            prompt = 'fixture:' + name + ':' + str(uuid.uuid4())
            conversation = client.conversation()
            task = client.submit(conversation, prompt)['taskId']
            terminal = client.terminal(task, timeout=145 if name == 'timeout' else 45)
            assert terminal['status'] == expected, name + ': unexpected task state ' + str(terminal.get('reasonCode'))
            _, usage = client.request('GET', '/tasks/' + task + '/usage')
            assert len(usage['items']) == count, name + ': ledger call count mismatch'
            assert fixture_counts().get(prompt) == count, name + ': unmetered SDK retry or hidden replay'
            _, snapshot = client.request('GET', '/conversations/' + conversation + '/snapshot')
            message = [m for m in snapshot['messages'] if m['role'] == 'ASSISTANT'][-1]
            text = ''.join(b['text'] for b in message['blocks'])
            assert 'HIDDEN' not in text
            if name in ['partial', 'truncated']:
                assert message['status'] == 'PARTIAL' and text
            if name in ['partial', 'timeout']:
                assert usage['items'][0]['status'] == 'UNKNOWN', 'Incomplete transport outcome must stay unknown'
            if name == 'missing-usage':
                assert usage['items'][0]['inputTokens'] is None and usage['items'][0]['outputTokens'] is None
                assert usage['items'][0]['costMicros'] is None
            results.append({'case': name, 'status': terminal['status'], 'reason': terminal.get('reasonCode'),
                            'ledger_calls': len(usage['items']), 'physical_calls': count, 'message': message['status']})
            print('PASS', name, 'physical_calls=' + str(count), flush=True)
    elif mode == 'outage':
        conversation = client.conversation()
        try:
            compose('stop', '-t', '30', 'rabbitmq')
            task = client.submit(conversation, 'fixture:normal:' + str(uuid.uuid4()))['taskId']
            time.sleep(3)
            assert client.request('GET', '/tasks/' + task)[1]['status'] == 'QUEUED'
            assert '(0 rows)' in admin.sql("SELECT 1 FROM pf.attempt WHERE task_id='" + task + "';")
        finally:
            compose('up', '-d', '--wait', '--wait-timeout', '120', 'rabbitmq')
        assert client.terminal(task, timeout=100)['status'] == 'SUCCEEDED'
        results.append({'case': 'broker-outage', 'result': 'queued-without-direct-execution-then-recovered'})
        try:
            compose('stop', '-t', '20', 'postgres')
            assert client.request('GET', '/models')[0] == 503
            assert client.request('POST', '/conversations', {'title': 'Must reject'}, str(uuid.uuid4()))[0] == 503
        finally:
            compose('up', '-d', '--wait', '--wait-timeout', '120', 'postgres')
        wait_ready(client)
        assert client.request('GET', '/models')[0] == 200
        task = client.submit(client.conversation(), 'fixture:normal:' + str(uuid.uuid4()))['taskId']
        assert client.terminal(task)['status'] == 'SUCCEEDED'
        results.append({'case': 'database-outage', 'result': 'authentication-and-write-fail-closed-then-recovered'})
        print('PASS broker/database outage recovery', flush=True)
    elif mode == 'active-db':
        prompt = 'fixture:slow:active-db:' + str(uuid.uuid4())
        task = client.submit(client.conversation(), prompt)['taskId']
        deadline = time.monotonic() + 15
        while '(0 rows)' in admin.sql("SELECT 1 FROM pf.platform_event WHERE task_id='" + task + "' AND type='message.delta' LIMIT 1;"):
            assert time.monotonic() < deadline
            time.sleep(0.2)
        try:
            compose('stop', '-t', '20', 'postgres')
            assert client.request('GET', '/models')[0] == 503
        finally:
            compose('up', '-d', '--wait', '--wait-timeout', '120', 'postgres')
        wait_ready(client)
        deadline = time.monotonic() + 15
        while True:
            try:
                compose('exec', '-T', 'worker', 'test', '-s', '/tmp/chat-worker-ready')
                break
            except subprocess.CalledProcessError:
                assert time.monotonic() < deadline
                time.sleep(1)
        admin.run([sys.executable, str(ROOT / 'deploy/scripts/chat-local.py'), 'reconcile-stopped'], capture_output=True, text=True)
        assert client.request('GET', '/tasks/' + task)[1]['status'] == 'FAILED'
        _, usage = client.request('GET', '/tasks/' + task + '/usage')
        assert len(usage['items']) == 1 and usage['items'][0]['status'] == 'UNKNOWN'
        compose('up', '-d', '--wait', '--wait-timeout', '120', 'worker')
        next_task = client.submit(client.conversation(), 'fixture:normal:after-active-db:' + str(uuid.uuid4()))['taskId']
        assert client.terminal(next_task)['status'] == 'SUCCEEDED'
        assert fixture_counts().get(prompt) == 1, 'Unknown interrupted call was replayed'
        results.append({'case': 'active-database-outage', 'interrupted_call': 'UNKNOWN', 'physical_calls': 1,
                        'stopped_owner_reconciled': True, 'replay': False, 'next_task': 'SUCCEEDED'})
        print('PASS active database outage, stopped-owner reconciliation and no call replay', flush=True)
    else:
        raise ValueError('Unknown acceptance mode')
    (DATA / ('failure-' + mode + '.json')).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
