"""Host-only, receipted R2 window. No Docker privilege is given to the app.

Use an admitted private directory under operations. Each action is exclusive;
failed mutations restore the recorded R1 config/images, never the legacy stack.
The caller must retain stdout/stderr privately. This is not a release marker.
"""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path('/srv/agentplatform')
ENV = ['postgres', 'rabbitmq', 'redis', 'master', 'volume', 'filer', 's3']
APP = ['api', 'worker', 'gateway']


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def output(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024**2), b''):
            h.update(block)
    return h.hexdigest()


class Window:
    def __init__(self, folder):
        self.folder = folder.resolve(strict=True)
        assert re.fullmatch(r'/srv/agentplatform/operations/r2-release-[a-f0-9]{7,40}-\d{8}', str(self.folder))
        assert self.folder.stat().st_mode & 0o077 == 0
        self.admission = json.loads((self.folder / 'admission.json').read_text())
        self.mutated = False
        assert self.admission['status'] == 'PASS'
        assert sha(self.folder / 'deploy/images.env') == self.admission['stagedDeployHashes']['images.env']

    def receipt(self, name, value):
        path = self.folder / (name + '.json')
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(dict(value, recordedAt=time.time()), stream, indent=2)

    def done(self, name):
        assert json.loads((self.folder / (name + '.json')).read_text())['status'] == 'PASS'

    def compose(self, kind, args, *, candidate=True, deploy=None):
        deploy = deploy or ROOT / 'deploy'
        command = ['docker', 'compose', '--env-file', str(deploy / 'images.env'), '-p', 'agentplatform-' + kind]
        for filename in ['compose.' + kind + '.yaml', 'compose.production.' + kind + '.yaml', 'compose.r1-http.' + kind + '.yaml']:
            command += ['-f', str(deploy / filename)]
        if kind == 'app' and candidate:
            command += ['-f', str(deploy / 'compose.r2-http.app.yaml')]
        return command + args

    def call(self, kind, args, **kwargs):
        return run(self.compose(kind, args, **kwargs), env=dict(os.environ, PLATFORM_ROOT=str(ROOT)))

    def pg(self):
        ids = output(['docker', 'ps', '-q', '--no-trunc', '--filter', 'label=com.docker.compose.project=agentplatform-env', '--filter', 'label=com.docker.compose.service=postgres']).split()
        assert len(ids) == 1
        return ids[0]

    def sql(self, sql):
        return output(['docker', 'exec', '-i', self.pg(), 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'ap_admin', '-d', 'agentplatform'], input=sql)

    def capacity(self, extra=0):
        assert shutil.disk_usage(ROOT).free >= 5 * 1024**3 + extra, 'DISK_RESERVE'
        mem = {line.split(':')[0]: int(line.split()[1]) * 1024 for line in Path('/proc/meminfo').read_text().splitlines() if len(line.split()) >= 2}
        assert mem['MemAvailable'] >= 768 * 1024**2, 'AVAILABLE_MEMORY_RESERVE'

    def readiness(self):
        result = json.loads(output(['curl', '--noproxy', '*', '--max-time', '15', '-fsS', '-H', 'Host: agentplatform.lailihao.cloud', 'http://127.0.0.1/api/v1/readiness']))
        assert result == {'status': 'READY'}

    def import_images(self):
        assert time.time() - self.admission['createdAt'] < 3600, 'Refresh stale admission'
        for filename, expected in self.admission['rollbackDeployHashes'].items():
            assert sha(ROOT / 'deploy' / filename) == expected, 'Live deployment drift'
        for image in self.admission['artifacts']:
            archive = self.folder / (image['component'] + '.tar.gz')
            assert archive.stat().st_size == image['archiveBytes'] and sha(archive) == image['archiveSha256']
        self.capacity(512 * 1024**2)
        for image in self.admission['artifacts']:
            run(['docker', 'load', '-i', str(self.folder / (image['component'] + '.tar.gz'))])
            actual = json.loads(output(['docker', 'image', 'inspect', image['configId']]))[0]
            assert actual['Id'] == image['configId'] and actual['Architecture'] == 'amd64' and actual['Os'] == 'linux'
            assert actual['Config']['Labels']['org.opencontainers.image.revision'] == image['source']
        self.capacity(8 * 1024**3)
        shutil.copytree(ROOT / 'deploy', self.folder / 'rollback-deploy')
        self.receipt('import', dict(status='PASS', artifacts=self.admission['artifacts'], r1Unchanged=True))

    def backup(self, generation):
        self.done('import')
        if generation == 'r2':
            self.done('activate')
        else:
            for row in self.admission['sourceContainers']:
                current = json.loads(output(['docker', 'inspect', row['id']]))[0]
                assert current['State']['Running'] and current['State']['StartedAt'] == row['startedAt'] and current['Image'] == row['image'], 'Source container identity changed'
            for filename, expected in self.admission['rollbackDeployHashes'].items():
                assert sha(ROOT / 'deploy' / filename) == expected, 'Live deployment changed'
        backup = self.folder / ('backup-' + generation)
        assert not backup.exists()
        # Cleanup/rollback is installed by main before the first service mutation.
        self.capacity(8 * 1024**3 + int(output(['du', '-sb', str(ROOT / 'data')]).split()[0]) * 2)
        if generation == 'r1':
            self.receipt('source-revalidation', dict(status='PASS', exactOriginalContainersRechecked=True,
                         exactOriginalDeploymentRechecked=True, diskAndMemoryRechecked=True,
                         admissionSha256=sha(self.folder / 'admission.json')))
        backup.mkdir(mode=0o700)
        self.mutated = True
        self.call('app', ['--profile', 'worker', 'stop', '-t', '60', 'gateway'], candidate=generation == 'r2')
        self.call('app', ['--profile', 'worker', 'stop', '-t', '60', 'api', 'worker'], candidate=generation == 'r2')
        assert self.sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL;') == '0', 'Unresolved owner: no quiescent backup'
        assert self.sql("SELECT count(*) FROM pf.task WHERE status IN ('QUEUED','RUNNING');") == '0', 'Drain existing work before changing runtime bundles'
        assert not output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-app'])
        expected = '9' if generation == 'r2' else '5'
        assert self.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;') == expected
        if generation == 'r2':
            run(['python3', str(ROOT / 'deploy/scripts/r2-database-evidence.py'), self.pg(), '--output', str(backup / 'database.json')])
        with (backup / 'postgres.sql').open('wb') as stream:
            run(['docker', 'exec', self.pg(), 'pg_dumpall', '-U', 'ap_admin'], stdout=stream)
        mq = output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-env', '--filter', 'label=com.docker.compose.service=rabbitmq'])
        run(['docker', 'exec', mq, 'rabbitmqctl', 'export_definitions', '/tmp/r2-definitions.json'])
        run(['docker', 'cp', mq + ':/tmp/r2-definitions.json', str(backup / 'rabbitmq-definitions.json')])
        self.call('env', ['stop', '-t', '60'])
        assert not output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-env'])
        run(['tar', '-C', str(ROOT), '-czf', str(backup / 'cold.tgz'), 'data', 'deploy', 'secrets', 'config'])
        frozen = False
        try:
            # Refuse an unmounted source; never write into its ordinary directory.
            mount = json.loads(output(['findmnt', '-J', '--target', str(ROOT / 'workspaces')]))['filesystems'][0]
            assert mount['target'] == str(ROOT / 'workspaces') and mount['fstype'] == 'xfs' and 'prjquota' in mount['options']
            run(['sync'])
            run(['xfs_freeze', '-f', str(ROOT / 'workspaces')]); frozen = True
            run(['cp', '--sparse=always', str(ROOT / 'quota/workspaces.xfs'), str(backup / 'workspaces.xfs')])
            assert (backup / 'workspaces.xfs').stat().st_size == 4 * 1024**3
            assert sha(backup / 'workspaces.xfs') == sha(ROOT / 'quota/workspaces.xfs')
        finally:
            if frozen:
                run(['xfs_freeze', '-u', str(ROOT / 'workspaces')])
        run(['python3', str(ROOT / 'deploy/scripts/workspace-manifest.py'), 'write', str(ROOT / 'workspaces'), str(backup / 'workspace-files.json')])
        (backup / 'quota-report.txt').write_text(output(['xfs_quota', '-x', '-c', 'report -p -b -i', str(ROOT / 'workspaces')]) + '\n')
        shutil.copy2(ROOT / 'reports/object-manifest.json', backup / 'object-manifest.json')
        keys = {str(p.relative_to(ROOT)): sha(p) for directory in ('secrets', 'config') for p in (ROOT / directory).rglob('*') if p.is_file()}
        (backup / 'key-identities.json').write_text(json.dumps(keys, indent=2))
        hashes = {p.name: sha(p) for p in backup.iterdir() if p.is_file()}
        (backup / 'checksums.json').write_text(json.dumps(hashes, indent=2))
        self.capacity(4 * 1024**3)
        self.receipt('backup-' + generation, dict(status='PASS', path=str(backup), migration=int(expected), hashes=hashes, originalXfsUuid=self.admission['originalXfsUuid'], sparseCopyLogicalBytes=4 * 1024**3))

    def activate(self, action='activate'):
        self.done('backup-r1')
        if action == 'redeploy':
            self.done('restore')
            self.done('rollback-smoke')
            self.mutated = True
            self.call('app', ['--profile', 'worker', 'stop', '-t', '60', *APP], candidate=False)
            assert self.sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL;') == '0'
        assert not output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-app'])
        for filename, expected in self.admission['stagedDeployHashes'].items():
            assert sha(self.folder / 'deploy' / filename) == expected
        assert {str(p.relative_to(self.folder / 'deploy')) for p in (self.folder / 'deploy').rglob('*') if p.is_file()} == set(self.admission['stagedDeployHashes']), 'Unexpected staged deployment file'
        self.capacity(4 * 1024**3)
        self.mutated = True
        shutil.copytree(self.folder / 'deploy', ROOT / 'deploy', dirs_exist_ok=True)
        self.call('env', ['up', '-d', '--wait', '--wait-timeout', '240', *ENV])
        self.call('app', ['--profile', 'migration', 'run', '--rm', '-T', '--interactive=false', 'migrate'])
        assert self.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;') == '9'
        assert self.sql('SELECT schema_version FROM pf.baseline_version WHERE id=1;') == '1'
        run(['docker', 'exec', '-i', self.pg(), 'psql', '-Xq', '-v', 'ON_ERROR_STOP=1', '-U', 'ap_admin', '-d', 'agentplatform'], input=(ROOT / 'deploy/config/r2-runtime-grants.sql').read_bytes())
        denial = self.sql("SELECT has_table_privilege('ap_api','pf_runtime.state_slot','SELECT') OR has_column_privilege('ap_api','pf.checkpoint','manifest_json','SELECT') OR has_column_privilege('ap_api','pf.credential','ciphertext','SELECT') OR has_table_privilege('ap_api','pf.stopped_owner_proof','INSERT');")
        assert denial == 'f', 'API private state/manifest/ciphertext/proof permissions'
        self.call('app', ['--profile', 'worker', 'up', '-d', '--wait', '--wait-timeout', '240', *APP])
        self.readiness()
        self.receipt(action, dict(status='PASS', migration=9, runtimeBaseline=1, privateApiPermissionsDenied=True, fixtureEnabled=False))

    def rollback(self):
        assert (self.folder / 'rollback-deploy/images.env').is_file()
        if (ROOT / 'deploy/compose.r2-http.app.yaml').is_file():
            self.call('app', ['--profile', 'worker', 'stop', '-t', '60', *APP])
        shutil.copytree(self.folder / 'rollback-deploy', ROOT / 'deploy', dirs_exist_ok=True)
        self.call('env', ['up', '-d', '--wait', '--wait-timeout', '240', *ENV], candidate=False)
        assert self.sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL;') == '0', 'Unresolved owner: retain evidence and do not start old worker'
        self.call('app', ['--profile', 'worker', 'up', '-d', '--wait', '--wait-timeout', '240', *APP], candidate=False)
        self.readiness()
        ids = output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-app']).split()
        rows = json.loads(output(['docker', 'inspect', *ids]))
        expected = {r['service']: r['image'] for r in self.admission['sourceContainers'] if r['project'] == 'agentplatform-app'}
        assert {r['Config']['Labels']['com.docker.compose.service']: r['Image'] for r in rows} == expected
        print('PASS R1 application/config identities and public readiness restored; legacy untouched')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['import', 'backup-r1', 'activate', 'backup-r2', 'rollback', 'redeploy'])
    parser.add_argument('window', type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    window = Window(args.window)
    with (window.folder / 'window.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        action = args.action
        # Refuse reruns: interruptions require inspection, not automatic replay.
        if action != 'rollback':
            assert not (window.folder / (action + '.json')).exists(), 'Completed action: inspect receipt instead of rerunning'
        try:
            if action == 'import':
                window.import_images()
            elif action.startswith('backup-'):
                window.backup(action.removeprefix('backup-'))
            elif action in ('activate', 'redeploy'):
                window.activate(action)
            else:
                window.rollback()
        except BaseException:
            if action in ('backup-r1', 'activate', 'backup-r2', 'redeploy') and window.mutated:
                try:
                    window.rollback()
                except BaseException:
                    print('FAIL R1 rollback requires operator attention; private window retained', flush=True)
            raise


if __name__ == '__main__':
    main()
