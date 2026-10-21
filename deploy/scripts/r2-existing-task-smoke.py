"""R1 rollback / same-candidate R2 smoke, using the existing successful task.

Login and reads only. Never submit or replay a supplier call.
"""
import argparse
import importlib.util
import json
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('r2observe', Path(__file__).with_name('r2-cloud-observe.py'))
OBSERVE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OBSERVE)
CONTROL = OBSERVE.CONTROL


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['rollback', 'final'])
    parser.add_argument('window', type=Path)
    args = parser.parse_args()
    window = CONTROL.Window(args.window)
    window.done('restore')
    if args.mode == 'final':
        window.done('redeploy')
    accounts = json.loads((CONTROL.ROOT / 'operations/r1-accounts/accounts.json').read_text())['accounts']
    account = next(a for a in accounts if a['username'] == 'r1acceptance')
    task = json.loads((window.folder / 'cloud-task.json').read_text())
    prior = json.loads((window.folder / 'http-smoke.json').read_text())
    for host in ('124.222.55.140', 'agentplatform.lailihao.cloud'):
        session = OBSERVE.Session(host)
        session.login(account)
        assert session.request('GET', '/readiness') == (200, {'status': 'READY'})
        code, value = session.request('GET', '/tasks/' + task['taskId'])
        assert code == 200 and value['status'] == 'SUCCEEDED'
        code, usage = session.request('GET', '/tasks/' + task['taskId'] + '/usage')
        assert code == 200 and usage['items'] == prior['usage'], 'No new physical call/accounting drift'
        assert session.request('GET', '/conversations/' + task['conversationId'] + '/snapshot')[0] == 200
        if args.mode == 'final':
            for projection in ('recovery', 'reconciliation', 'diagnostics'):
                assert session.request('GET', '/tasks/' + task['taskId'] + '/' + projection)[0] == 200
        assert session.request('POST', '/auth/logout')[0] == 204
        assert session.request('GET', '/me')[0] == 401
    ids = CONTROL.output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-app']).split()
    values = json.loads(CONTROL.output(['docker', 'inspect', *ids]))
    expected = {v['service']: v['image'] for v in window.admission['sourceContainers'] if v['project'] == 'agentplatform-app'}
    if args.mode == 'final':
        images = {v['component']: v['configId'] for v in window.admission['artifacts']}
        expected = dict(api=images['api'], worker=images['api'], gateway=images['gateway'])
    assert {v['Config']['Labels']['com.docker.compose.service']: v['Image'] for v in values} == expected
    assert all(v['State']['Running'] and not v['State']['OOMKilled'] for v in values)
    window.capacity()
    assert window.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;') == '9'
    window.receipt(args.mode + '-smoke', dict(status='PASS', hosts=['IP', 'DOMAIN_HOST_HEADER'], actualImages=expected, migration=9, historicalTaskAndUsagePreserved=True, newProviderCalls=0, logoutRevocation=True))
    print('PASS ' + args.mode + ' exact images, identity and existing chat/usage; no provider call')


if __name__ == '__main__':
    main()
