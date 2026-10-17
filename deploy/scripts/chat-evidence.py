"""Collect sanitized local R1.2 identities and secret checks; never save raw SQL/logs."""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('chat_local', ROOT / 'deploy/scripts/chat-local.py')
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--private-key-file', type=Path, required=True)
    parser.add_argument('--stage', choices=['r1.2', 'r1.3'], default='r1.2')
    args = parser.parse_args()
    output = local.DATA / ('sanitized-evidence' if args.stage == 'r1.2' else 'r13-sanitized-evidence')
    output.mkdir(exist_ok=True)
    # Values stay in memory; output contains channel names and booleans only.
    key = args.private_key_file.read_text(encoding='utf-8-sig').strip().encode()
    assert len(key) >= 16
    needles = [key, base64.b64encode(key)]
    secret_files = ['credential-master-key', 'cursor-key', 'api-password', 'worker-password', 'postgres-password', 'alice-password', 'bob-password', 'mq-api-password', 'mq-worker-password', 'mq-operator-password']
    if args.stage == 'r1.3':
        secret_files.append('r13acceptance-password')
        if (local.DATA / 'state-probe-password').exists():
            secret_files.append('state-probe-password')
    for name in secret_files:
        value = (local.DATA / name).read_bytes() if name in ['credential-master-key', 'cursor-key'] else (local.DATA / name).read_bytes().strip()
        needles.extend([value, value.hex().encode(), base64.b64encode(value)])
    paths = subprocess.check_output(['git', 'ls-files', '-co', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    checked = []
    for name in dict.fromkeys(p for p in paths if p):
        path = ROOT / name
        if path.is_file():
            assert not any(value in path.read_bytes() for value in needles), 'Secret detected in source path: ' + name
            checked.append(name)
    runtime_needles = needles + [b'R12_FIXTURE_CREDENTIAL_SENTINEL', b'FIXTURE_HIDDEN_REASONING', b'R13_USER_KEY_SENTINEL_NOT_A_REAL_KEY']
    identities = []
    for service in ['api', 'worker', 'postgres', 'rabbitmq', 'redis', 'gateway', 'fixture']:
        name = 'agentplatform-r12-local-' + service + '-1'
        container = json.loads(subprocess.check_output(['docker', 'inspect', name], cwd=ROOT))[0]
        assert container['Config']['Labels']['com.docker.compose.project'] == 'agentplatform-r12-local'
        image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', container['Image']], cwd=ROOT))[0]
        logs = subprocess.run(['docker', 'logs', name], cwd=ROOT, capture_output=True, check=True)
        assert not any(value in logs.stdout + logs.stderr for value in runtime_needles), 'Secret detected in logs: ' + service
        swap = subprocess.check_output(['docker', 'exec', name, 'cat', '/sys/fs/cgroup/memory.swap.current'], cwd=ROOT).decode().strip()
        assert int(swap) == 0
        identities.append({'service': service, 'configId': image['Id'], 'sourceRevision': (image['Config'].get('Labels') or {}).get('org.opencontainers.image.revision'),
                           'repoDigests': image['RepoDigests'], 'platform': image['Os'] + '/' + image['Architecture'],
                           'memoryBytes': container['HostConfig']['Memory'], 'memorySwapBytes': container['HostConfig']['MemorySwap'],
                           'swapCurrentBytes': int(swap), 'running': container['State']['Running'], 'oomKilled': container['State']['OOMKilled']})
    sql = local.run(local.COMPOSE + ['exec', '-T', 'postgres', 'pg_dump', '-U', 'postgres', '-d', 'chat', '--no-owner', '--no-privileges'], capture_output=True).stdout
    assert not any(value in sql for value in runtime_needles), 'Secret/hidden reasoning detected in database projections or private state'
    jar = ROOT / 'backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar'
    actual = subprocess.check_output(['docker', 'exec', 'agentplatform-r12-local-api-1', 'sha256sum', '/app/platform.jar'], cwd=ROOT).decode().split()[0]
    assert digest(jar) == actual
    real = json.loads((local.DATA / ('real-model-browser.json' if args.stage == 'r1.2' else 'r13-real-browser.json')).read_text())
    assert real['status'] == 'PASS'
    streams = []
    calls = real['usage'] if args.stage == 'r1.2' else [call for result in real['results'] for call in result['usage']]
    for call in calls:
        call_id = str(uuid.UUID(call['callId']))
        value = local.sql("SELECT json_build_object('deltas',count(*) FILTER(WHERE e.type='message.delta'),'incremental',min(e.created_at) FILTER(WHERE e.type='message.delta') < max(e.created_at) FILTER(WHERE e.type='message.completed')) FROM pf.platform_event e JOIN pf.model_call c ON c.task_id=e.task_id WHERE c.id='" + call_id + "';")
        item = json.loads(re.search(r'\{.*\}', value).group(0))
        assert item['deltas'] > 0 and item['incremental'] is True
        streams.append(item)
    if args.stage == 'r1.2':
        assert streams[0]['deltas'] > 1, 'Real first response must persist multiple live batches'
    meter = json.loads(re.search(r'\{.*\}', local.sql('SELECT row_to_json(m) FROM pf.acceptance_model_meter m;')).group(0))
    assert meter['physical_calls'] <= 20 and meter['reserved_micros'] + meter['consumed_micros'] <= 1000000
    result = {'schemaVersion': 1, 'status': 'PASS', 'sourceHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip(),
              'images': identities, 'jarSha256': actual, 'sourceFilesChecked': len(checked),
              'secretChannels': ['source', 'runtime logs', 'database including private state/events'],
              'rawSqlOrLogsSaved': False, 'realStreams': streams, 'multiBatchObserved': any(item['deltas'] > 1 for item in streams), 'acceptanceMeter': meter,
              'contractSha256': digest(ROOT / 'contracts' / (args.stage + '-baseline.lock.json')),
              'artifactLockSha256': digest(ROOT / 'deploy/maven-artifacts.lock.json'),
              'deploymentConfigSha256': digest(ROOT / 'deploy/compose.chat-local.yaml'),
              'nginxConfigSha256': digest(ROOT / 'deploy/nginx.chat-local.conf')}
    (output / 'runtime-secret-proof.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print('PASS sanitized identities, zero current swap, real committed streaming and source/log/database secret checks')


if __name__ == '__main__':
    main()
