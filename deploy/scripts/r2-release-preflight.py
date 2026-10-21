"""Read-only R2 admission. Persist receipts only in a new private output file.

Run on the production host before stopping R1. Configuration must already be
staged outside the live deploy directory; this command never starts containers.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path('/srv/agentplatform')
ENV_SERVICES = {'postgres', 'rabbitmq', 'redis', 'master', 'volume', 'filer', 's3'}
APP_SERVICES = {'api', 'worker', 'gateway'}
MIB = 1024**2
GIB = 1024**3


def output(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs)


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda: stream.read(MIB), b''):
            digest.update(part)
    return digest.hexdigest()


def private_new(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')


def validate_capacity(free, incoming, expanded, cold_data, physical, limits, available):
    assert all(isinstance(value, int) and value >= 0 for value in
               (free, incoming, expanded, cold_data, physical, limits, available))
    # Full logical XFS backup and independent restore copy, even when sparse.
    required = incoming + expanded + cold_data * 3 + 2 * 4 * GIB + 5 * GIB
    assert free >= required, 'DISK_ADMISSION: incoming + expansion + cold copies + 8GiB XFS + 5GiB reserve'
    assert 0 < limits <= 2887 * MIB, 'CONTAINER_LIMIT_ADMISSION'
    assert physical - limits >= 768 * MIB, 'PHYSICAL_MEMORY_ADMISSION'
    assert available >= 768 * MIB, 'AVAILABLE_MEMORY_ADMISSION'
    return required


def validate_external_limits(rows, platform_limit, physical):
    limits = []
    for row in rows:
        limit, swap = row['HostConfig']['Memory'], row['HostConfig']['MemorySwap']
        assert limit > 0 and swap == limit, 'UNBOUNDED_EXTERNAL_CONTAINER'
        limits.append(limit)
    total = platform_limit + sum(limits)
    assert total <= 2887 * MIB, 'COEXISTING_CONTAINER_LIMIT'
    assert physical - total >= 768 * MIB, 'COEXISTING_HOST_RESERVE'
    return sum(limits)


def compose(folder, project, kind):
    files = ['compose.' + kind + '.yaml', 'compose.production.' + kind + '.yaml',
             'compose.r1-http.' + kind + '.yaml']
    if kind == 'app':
        files.append('compose.r2-http.app.yaml')
    command = ['docker', 'compose', '--env-file', str(folder / 'images.env'), '-p', project]
    for file in files:
        command += ['-f', str(folder / file)]
    return json.loads(output(*command, '--profile', 'worker', '--profile', 'r2-ops',
                             'config', '--format', 'json', env=dict(os.environ, PLATFORM_ROOT=str(ROOT))))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--staged-deploy', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--configuration-source', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert all(re.fullmatch('[a-f0-9]{40}', x) for x in (args.source, args.configuration_source))
    folder = args.staged_deploy.resolve(strict=True)
    assert folder.is_relative_to(ROOT / 'operations') and folder != ROOT / 'deploy'
    assert args.output.parent.resolve(strict=True).is_relative_to(ROOT / 'operations')
    artifacts = json.loads(args.artifacts.read_text())
    assert {x['component'] for x in artifacts} == {'api', 'gateway'}
    assert all(x['source'] == args.source and x['platform'] == 'linux/amd64' for x in artifacts)
    configs = {kind: compose(folder, 'agentplatform-' + kind, kind) for kind in ('env', 'app')}
    selected = {}
    for kind, names in (('env', ENV_SERVICES), ('app', APP_SERVICES)):
        for name in sorted(names):
            value = configs[kind]['services'][name]
            assert re.fullmatch('sha256:[0-9a-f]{64}', value['image']), 'Mutable image rejected'
            assert int(value['mem_limit']) == int(value['memswap_limit'])
            assert value.get('privileged', False) is False
            if kind == 'app' or name == 'redis':
                assert value.get('read_only', False)
            assert 'ALL' in value.get('cap_drop', [])
            assert any(v.startswith('no-new-privileges') for v in value.get('security_opt', []))
            assert value.get('user') not in (None, '', 'root', '0', '0:0')
            assert not any('docker.sock' in str(v) for v in value.get('volumes', []))
            selected[kind + '/' + name] = value
    for artifact in artifacts:
        assert selected['app/' + artifact['component']]['image'] == artifact['configId']
    worker = selected['app/worker']['environment']
    assert worker['PLATFORM_CHAT_PROVIDER_BASE'] == 'https://api.deepseek.com'
    assert worker['SPRING_PROFILES_ACTIVE'] == 'chat-worker'
    for name in ('PLATFORM_CHAT_FIXTURE_PROVIDER', 'PLATFORM_CHAT_FIXTURE_TOOLS', 'PLATFORM_CHAT_REAL_RECOVERY_ACCEPTANCE'):
        assert str(worker[name]).lower() == 'false'
    assert not worker.get('PLATFORM_CHAT_FIXTURE_FAULT')
    assert configs['app']['services']['operator']['profiles'] == ['r2-ops']
    live = []
    for kind, names in (('env', ENV_SERVICES), ('app', APP_SERVICES)):
        ids = output('docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-' + kind).split()
        assert ids, 'Missing source project'
        values = json.loads(output('docker', 'inspect', *ids))
        assert {v['Config']['Labels']['com.docker.compose.service'] for v in values} == names
        assert len(values) == len(names), 'Unexpected duplicate source service'
        for value in values:
            state = value['State']
            assert state['Running'] and not state['OOMKilled']
            assert state.get('Health', {}).get('Status', 'healthy') == 'healthy'
            live.append(dict(id=value['Id'], project='agentplatform-' + kind,
                             service=value['Config']['Labels']['com.docker.compose.service'],
                             image=value['Image'], startedAt=state['StartedAt'],
                             restart=value['HostConfig']['RestartPolicy']))
    expected = {'api': 'sha256:6853788d51b309e45ff36200a533304431266fb3feaaf121209daa807fffe757',
                'worker': 'sha256:6853788d51b309e45ff36200a533304431266fb3feaaf121209daa807fffe757',
                'gateway': 'sha256:a926aced9585adfeef570e7da06a2f088cc0ba5c22a0574215835927e3de3468'}
    assert all(v['image'] == expected[v['service']] for v in live if v['project'] == 'agentplatform-app'), 'R1 source identity drift'
    pg = next(v['id'] for v in live if v['service'] == 'postgres')
    sql = "SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL;"
    assert output('docker', 'exec', pg, 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'ap_admin', '-d', 'agentplatform', '-c', sql).strip() == '0'
    migration = int(output('docker', 'exec', pg, 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'ap_admin', '-d', 'agentplatform', '-c', 'SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;').strip())
    assert migration in (5, 9), 'Unexpected source migration; inspect before proceeding'
    mem = {line.split(':')[0]: int(line.split()[1]) * 1024 for line in Path('/proc/meminfo').read_text().splitlines() if ':' in line and len(line.split()) >= 2}
    cold = int(output('du', '-sb', str(ROOT / 'data')).split()[0])
    # Frozen local archives are not uploaded yet. Conservative uncompressed image allowance.
    incoming = 0
    for artifact in artifacts:
        archive = folder.parent / (artifact['component'] + '.tar.gz')
        if archive.exists():
            assert archive.stat().st_size == artifact['archiveBytes'] and sha(archive) == artifact['archiveSha256']
        else:
            incoming += artifact['archiveBytes']
    # Accepted images report 199594843 aggregate bytes; allow >2.5x for import.
    expanded = 512 * MIB
    inspected = [subprocess.run(['docker', 'image', 'inspect', a['configId']], capture_output=True, text=True) for a in artifacts]
    if all(value.returncode == 0 for value in inspected):
        for artifact, value in zip(artifacts, inspected):
            image = json.loads(value.stdout)[0]
            assert image['Id'] == artifact['configId'] and image['Config']['Labels']['org.opencontainers.image.revision'] == artifact['source']
        expanded = 0  # Already imported bytes are already included in current filesystem usage.
    limits = sum(int(v['mem_limit']) for v in selected.values())
    all_ids = output('docker', 'ps', '-q', '--no-trunc').split()
    source_ids = {row['id'] for row in live}
    extra_ids = [cid for cid in all_ids if cid not in source_ids]
    extras = json.loads(output('docker', 'inspect', *extra_ids)) if extra_ids else []
    other_limits = validate_external_limits(extras, limits, mem['MemTotal'])
    required = validate_capacity(shutil.disk_usage(ROOT).free, incoming, expanded, cold,
                                 mem['MemTotal'], limits + other_limits, mem['MemAvailable'])
    operator = int(configs['app']['services']['operator']['mem_limit'])
    assert limits + other_limits + operator <= 2887 * MIB
    for name in ('chat_master', 'chat_cursor'):
        key = ROOT / 'secrets' / name
        assert key.stat().st_size == 32 and key.stat().st_mode & 0o077 == 0
    mount = json.loads(output('findmnt', '-J', '--target', str(ROOT / 'workspaces')))['filesystems'][0]
    uuid = output('blkid', '-p', '-s', 'UUID', '-o', 'value', str(ROOT / 'quota/workspaces.xfs')).strip()
    assert mount['target'] == str(ROOT / 'workspaces') and mount['fstype'] == 'xfs' and 'prjquota' in mount['options']
    assert uuid == (ROOT / 'quota/filesystem.uuid').read_text().strip()
    receipt = dict(status='PASS', purpose='READ_ONLY_ADMISSION_NOT_RELEASE', createdAt=time.time(),
                   applicationSource=args.source, configurationSource=args.configuration_source,
                   sourceMigration=migration, incomingArchiveReservation=incoming, imageExpansionReservation=expanded,
                   sourceContainers=live, artifacts=artifacts, selectedServices=selected,
                   effectiveLimitMiB=limits // MIB, withOperatorMiB=(limits + operator) // MIB,
                   externalContainerLimitMiB=other_limits // MIB,
                   rootAvailable=shutil.disk_usage(ROOT).free, requiredDisk=required,
                   physicalMemory=mem['MemTotal'], availableMemory=mem['MemAvailable'],
                   originalXfsUuid=uuid, xfsMount=mount,
                   stagedDeployHashes={str(p.relative_to(folder)): sha(p) for p in folder.rglob('*') if p.is_file()},
                   rollbackDeployHashes={str(p.relative_to(ROOT / 'deploy')): sha(p) for p in (ROOT / 'deploy').rglob('*') if p.is_file()})
    private_new(args.output, receipt)
    print(json.dumps({k: receipt[k] for k in ('status', 'purpose', 'effectiveLimitMiB', 'withOperatorMiB', 'rootAvailable', 'requiredDisk')}))


if __name__ == '__main__':
    main()
