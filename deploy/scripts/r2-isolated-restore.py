"""Serial R2 cold restore; cleanup exists before the first mutation.

No source data overwrite, source UUID change, worker start or provider request.
Failure leaves private evidence and stops only the dedicated restore projects.
"""
import argparse
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time

SPEC = importlib.util.spec_from_file_location('r2window', Path(__file__).with_name('r2-release-control.py'))
CONTROL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTROL)
ROOT = CONTROL.ROOT
run, output, sha = CONTROL.run, CONTROL.output, CONTROL.sha


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('window', type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    window = CONTROL.Window(args.window)
    with (window.folder / 'window.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            restore_window(window)
        except BaseException:
            if getattr(window, '_restore_mutated', False):
                window.rollback()
            raise


def restore_window(window):
    window.done('backup-r2')
    assert not (window.folder / 'restore.json').exists()
    backup = window.folder / 'backup-r2'
    for filename, digest in json.loads((backup / 'checksums.json').read_text()).items():
        assert sha(backup / filename) == digest
    for project in ('agentplatform-env', 'agentplatform-app', 'agentplatform-r2-restore-env', 'agentplatform-r2-restore-app'):
        assert not output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=' + project]), 'Source/temporary stacks must be stopped'
    assert shutil.disk_usage(ROOT).free >= 9 * 1024**3 + int(output(['du', '-sb', str(ROOT / 'data')]).split()[0])
    restore = ROOT / ('operations/r2-restore-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()))
    assert restore.parent.resolve(strict=True) == ROOT / 'operations' and not restore.exists()
    assert not restore.is_relative_to(ROOT / 'data') and not (ROOT / 'data').is_relative_to(restore)
    with socket.socket() as port:
        port.bind(('127.0.0.1', 18093))
    mounted = False
    commands = {}
    environment = dict(os.environ, PLATFORM_ROOT=str(restore), PLATFORM_BACKPLANE='agentplatform-r2-restore-backplane',
                       PLATFORM_MODEL_EGRESS='agentplatform-r2-restore-model-egress',
                       PLATFORM_INGRESS='agentplatform-r2-restore-ingress',
                       PLATFORM_EGRESS_CONTROL='agentplatform-r2-restore-egress', HTTP_BIND='127.0.0.1', HTTP_PORT='18093',
                       PRODUCTION_NGINX_CONFIG=str(restore / 'deploy/nginx.production.conf'))
    def call(kind, *args, **kwargs):
        return run(commands[kind] + list(args), env=environment, **kwargs)
    original_uuid = window.admission['originalXfsUuid']
    source_uuid = lambda: output(['blkid', '-p', '-s', 'UUID', '-o', 'value', str(ROOT / 'quota/workspaces.xfs')])
    assert source_uuid() == original_uuid
    # A cleanup failure must fail the entire restore, even after successful probes.
    try:
        window._restore_mutated = True
        restore.mkdir(mode=0o700)
        # Archive was made by this driver from exactly these directories, never untrusted input.
        import tarfile
        with tarfile.open(backup / 'cold.tgz') as archive:
            for member in archive.getmembers():
                assert (restore / member.name).resolve().is_relative_to(restore)
                assert member.isfile() or member.isdir(), 'Unexpected archive special file/link'
        run(['tar', '-xzf', str(backup / 'cold.tgz'), '-C', str(restore)])
        for name in ('quota', 'workspaces', 'reports', 'operations'):
            (restore / name).mkdir(mode=0o700)
        # These keys/configs remain local to the private restore tree.
        for filename, digest in json.loads((backup / 'key-identities.json').read_text()).items():
            assert sha(restore / filename) == digest
        run(['cp', '--sparse=always', str(backup / 'workspaces.xfs'), str(restore / 'quota/workspaces.xfs')])
        assert sha(restore / 'quota/workspaces.xfs') == sha(backup / 'workspaces.xfs')
        # Replay a frozen log only on this private copy, then assign a new UUID.
        run(['mount', '-o', 'loop,nouuid,prjquota', str(restore / 'quota/workspaces.xfs'), str(restore / 'workspaces')]); mounted = True
        run(['umount', str(restore / 'workspaces')]); mounted = False
        run(['xfs_admin', '-U', 'generate', str(restore / 'quota/workspaces.xfs')])
        copy_uuid = output(['blkid', '-p', '-s', 'UUID', '-o', 'value', str(restore / 'quota/workspaces.xfs')])
        assert copy_uuid != original_uuid and source_uuid() == original_uuid
        (restore / 'quota/filesystem.uuid').write_text(copy_uuid + '\n')
        run(['mount', '-o', 'loop,prjquota', str(restore / 'quota/workspaces.xfs'), str(restore / 'workspaces')]); mounted = True
        workspace = Path(__file__).with_name('r2-workspace-restore.py')
        manifest = ROOT / 'deploy/scripts/workspace-manifest.py'
        run(['python3', str(manifest), 'verify', str(restore / 'workspaces'), str(backup / 'workspace-files.json')])
        before_quota = output(['xfs_quota', '-x', '-c', 'report -p -b -i', str(restore / 'workspaces')])
        assert before_quota.splitlines()[1:] == (backup / 'quota-report.txt').read_text().strip().splitlines()[1:]
        run(['python3', str(workspace), 'quota', str(restore)])
        run(['umount', str(restore / 'workspaces')]); mounted = False
        missing = subprocess.run(['python3', str(workspace), 'verify-mount', str(restore)], capture_output=True)
        assert missing.returncode != 0 and not list((restore / 'workspaces').iterdir()), 'Missing-mount guard failed'
        run(['mount', '-o', 'loop,prjquota', str(restore / 'quota/workspaces.xfs'), str(restore / 'workspaces')]); mounted = True
        run(['python3', str(workspace), 'verify-mount', str(restore)])
        run(['python3', str(manifest), 'verify', str(restore / 'workspaces'), str(backup / 'workspace-files.json')])
        for kind in ('env', 'app'):
            command = ['docker', 'compose', '--env-file', str(restore / 'deploy/images.env'), '-p', 'agentplatform-r2-restore-' + kind]
            filenames = ['compose.' + kind + '.yaml', 'compose.production.' + kind + '.yaml', 'compose.r1-http.' + kind + '.yaml']
            if kind == 'app':
                filenames.append('compose.r2-http.app.yaml')
            for filename in filenames:
                command += ['-f', str(restore / 'deploy' / filename)]
            commands[kind] = command
            call(kind, 'config', '--quiet')
        call('env', 'up', '-d', '--wait', '--wait-timeout', '240', *CONTROL.ENV)
        pg = output(commands['env'] + ['ps', '-q', 'postgres'], env=environment)
        pg = json.loads(output(['docker', 'inspect', pg]))[0]['Id']
        run(['python3', str(ROOT / 'deploy/scripts/r2-database-evidence.py'), pg, '--output', str(restore / 'reports/database.json'), '--compare', str(backup / 'database.json')])
        vector = output(['docker', 'exec', pg, 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'ap_admin', '-d', 'agentplatform', '-c', "SELECT count(*)=1 AND bool_and((value <-> '[1,2,3]'::vector)=0) FROM pf.r04_restore_vector WHERE id='r04-functional-restore';"])
        assert vector == 't', 'Restored persisted vector distance mismatch'
        # Authenticated AES-GCM verification through the existing protected operator.
        result = subprocess.run(commands['app'] + ['--profile', 'r2-ops', 'run', '--rm', '-T', '--interactive=false', 'operator', '--chat-action=verify-restored-credentials'], env=environment, capture_output=True, text=True, timeout=180)
        (restore / 'reports/credential-operator.private.log').write_text(result.stdout + result.stderr)
        assert result.returncode == 0 and 'Restored credential authentication/decryption PASS' in result.stdout
        call('app', 'up', '-d', '--wait', '--wait-timeout', '240', 'api', 'gateway')
        ready = json.loads(output(['curl', '--noproxy', '*', '-fsS', '--max-time', '15', '-H', 'Host: agentplatform.lailihao.cloud', 'http://127.0.0.1:18093/api/v1/readiness']))
        assert ready == {'status': 'READY'}
        assert not output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-r2-restore-app', '--filter', 'label=com.docker.compose.service=worker'])
        # Immutable old storage probe compares PG/vector, Redis/MQ and saved S3 object hashes.
        shutil.copy2(backup / 'object-manifest.json', restore / 'reports/object-manifest.json')
        os.chown(restore / 'reports', 10001, 10001)
        os.chown(restore / 'reports/object-manifest.json', 10001, 10001)
        images = dict(line.split('=', 1) for line in (restore / 'deploy/images.env').read_text().splitlines() if '=' in line)
        probe = ['docker', 'run', '--rm', '--network', environment['PLATFORM_BACKPLANE'], '--user', '10001:10001', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--memory', '192m', '--memory-swap', '192m', '--pids-limit', '128', '--tmpfs', '/tmp:rw,noexec,nosuid,size=16m', '-e', 'PROBE_MODE=restart', '--mount', 'type=bind,src=' + str(restore / 'reports') + ',dst=/reports']
        for name in ('pg_api', 'pg_migration', 'mq_publisher', 'mq_execution', 'redis', 's3_read', 's3_write'):
            probe += ['--mount', 'type=bind,src=' + str(restore / 'secrets' / name) + ',dst=/run/secrets/' + name + ',readonly']
        run(probe + [images['STORAGE_PROBE_IMAGE']])
        mq = output(commands['env'] + ['ps', '-q', 'rabbitmq'], env=environment)
        run(['docker', 'exec', mq, 'rabbitmqctl', 'export_definitions', '/tmp/r2-restored-definitions.json'])
        run(['docker', 'cp', mq + ':/tmp/r2-restored-definitions.json', str(restore / 'reports/rabbitmq-private.json')])
        def normalized(value):
            if isinstance(value, dict):
                return {k: normalized(v) for k, v in value.items()}
            if isinstance(value, list):
                return sorted([normalized(v) for v in value], key=lambda v: json.dumps(v, sort_keys=True))
            return value
        assert normalized(json.loads((backup / 'rabbitmq-definitions.json').read_text())) == normalized(json.loads((restore / 'reports/rabbitmq-private.json').read_text()))
        assert source_uuid() == original_uuid
        receipt = dict(status='PASS', restoreRoot=str(restore), databaseFingerprint='PASS', authenticatedCredentialDecryption='PASS', objectsAndVector='PASS', workspaceHashes='PASS', byteAndInodeQuota='PASS', missingMountRefused=True, remountConsistency=True, originalUuidUnchanged=True, restoredUuid=copy_uuid, providerRequests=0, workerStarted=False)
    finally:
        # Install-before-mutation cleanup, limited to exact independent projects/mount.
        failures = []
        for kind in ('app', 'env'):
            if kind in commands:
                result = subprocess.run(commands[kind] + ['--profile', 'worker', 'stop', '-t', '60'], env=environment)
                if result.returncode:
                    failures.append('stop-' + kind)
        if mounted:
            result = subprocess.run(['umount', str(restore / 'workspaces')])
            if result.returncode:
                failures.append('unmount-private-copy')
        assert not failures, 'RESTORE_CLEANUP_FAILED: ' + ','.join(failures)
    window.receipt('restore', receipt)
    print('PASS isolated serial R2 restore; source remains stopped; no provider requests')


if __name__ == '__main__':
    main()
