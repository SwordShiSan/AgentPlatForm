"""Run the production Java docker-java material mount adapter on the preserved test XFS image.

The default invocation performs read-only preflight. --run is a bounded R5 maintenance window;
it reuses the existing isolated XFS image, never formats it, and restores the exact R5 containers.
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
import signal
import stat
import subprocess
import sys
import time
import traceback
import uuid
import zipfile
from pathlib import PurePosixPath

BASE = Path('/srv/agentplatform/acceptance')
OPS = Path('/srv/agentplatform/operations/platform-switch')
SWITCH = OPS / 'platform-switch.py'
SWITCH_SHA = '92aeb5fc0ec8f363aad6195e0166f320b6bce2f304d56f61bcea3319cefe56e4'
TEST_ROOT = BASE / 'r62-mount-poc-c15bd087764e44d983f54b2868d3f7d3'
TEST_DISK = TEST_ROOT / 'test.xfs'
TEST_DISK_SHA = 'ed92aab588eca88065d318361931716cc523a045542846ea1aaf39414d16db52'
TEST_DISK_STATE = TEST_ROOT / 'r62-java-image-state.json'
RUNSC_PATH = '/usr/local/lib/agentplatform-gvisor/20261005.0/runsc'
RUNSC_SHA = '210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a'
R4_SANDBOX_ID = 'sha256:9e0bd46f894ba4a82682b933a654b888ab3cb6e34f9d28cf39a7c4f65fd09a1d'
R4_SANDBOX_REVISION = '3bde15a1ecbc8f295d5b11f9c16303d85d7222b8'
GIB = 1024 ** 3


def call(*args, timeout=120, input_text=None):
    result = subprocess.run([str(item) for item in args], capture_output=True, text=True,
                            timeout=timeout, input=input_text)
    if result.returncode:
        raise RuntimeError('Command failed: ' + str(args[0]) + ' (exit ' + str(result.returncode) + ')')
    return result.stdout.strip()


def save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.chmod(0o600)
    os.replace(temporary, path)


def switch_helper():
    assert hashlib.sha256(SWITCH.read_bytes()).hexdigest() == SWITCH_SHA, 'Switch helper identity changed'
    spec = importlib.util.spec_from_file_location('platform_switch_r62_java', SWITCH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert module.release_profile()[0] == 'agentplatform-r5-app', 'Selected release changed'
    return module


def artifact(path, expected_sha):
    path = path.resolve(strict=True)
    assert path.parent.parent == BASE and re.fullmatch(r'r62-java-artifact-[a-f0-9]{12}', path.parent.name)
    assert path.name == 'platform-canary.jar' and path.is_file() and not path.is_symlink()
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected_sha, 'Canary JAR hash changed'
    return path


def prepare_runtime(jar_path, target):
    target.mkdir(mode=0o700)
    prefixes = ('BOOT-INF/classes/', 'BOOT-INF/lib/')
    with zipfile.ZipFile(jar_path) as archive:
        names = set()
        for entry in archive.infolist():
            if not entry.filename.startswith(prefixes):
                continue
            relative = PurePosixPath(entry.filename)
            assert not relative.is_absolute() and all(part not in ('', '.', '..') for part in relative.parts)
            mode = entry.external_attr >> 16
            assert not stat.S_ISLNK(mode), 'Executable bundle contains a symbolic link'
            destination = target.joinpath(*relative.parts)
            if entry.is_dir():
                destination.mkdir(parents=True, exist_ok=True, mode=0o700)
                continue
            assert entry.filename not in names, 'Duplicate executable bundle path'
            names.add(entry.filename)
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with archive.open(entry) as source, destination.open('xb') as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)
            destination.chmod(0o600)
    assert (target / 'BOOT-INF/classes/com/agentplatform/app/workspace/R62CapabilityMountCanary.class').is_file()
    assert (target / 'BOOT-INF/lib/platform-domain-0.1.0-SNAPSHOT.jar').is_file()
    return target


def hash_file(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def test_disk(verify_hash=False):
    assert TEST_ROOT.is_dir() and TEST_ROOT.resolve() == TEST_ROOT
    assert TEST_DISK.is_file() and not TEST_DISK.is_symlink() and TEST_DISK.stat().st_size == 4 * GIB
    if verify_hash:
        expected_hash = TEST_DISK_SHA
        if TEST_DISK_STATE.exists():
            state = json.loads(TEST_DISK_STATE.read_text())
            assert state.get('uuid') == json.loads((TEST_ROOT / 'test-disk.json').read_text())['uuid']
            expected_hash = state.get('sha256')
        assert hash_file(TEST_DISK) == expected_hash, 'Preserved XFS test image changed before run'
    expected = json.loads((TEST_ROOT / 'test-disk.json').read_text())['uuid']
    assert run_blkid(TEST_DISK) == expected
    assert subprocess.run(['findmnt', '--mountpoint', str(TEST_ROOT / 'workspaces')], capture_output=True).returncode != 0
    assert not subprocess.run(['losetup', '-j', str(TEST_DISK)], capture_output=True, text=True).stdout.strip()
    return expected


def run_blkid(path):
    return call('blkid', '-p', '-s', 'UUID', '-o', 'value', path)


def preflight(jar_path, jar_sha, verify_disk_hash=False):
    switch = switch_helper()
    new, legacy = switch.inventory()
    assert len(new) == 11 and all(row['running'] for row in new), 'R5 is not fully running'
    assert all(not row['running'] for row in legacy), 'Legacy platform is running'
    running = set(call('docker', 'ps', '-q', '--no-trunc').split())
    assert running == {row['id'] for row in new}, 'Other running containers require a resource review'
    assert switch.idle(new), 'Production has active tasks, files, environments, previews or extractions'
    assert switch.http('/api/v1/readiness', host='124.222.55.140')[0] == 200
    free = shutil.disk_usage('/').free
    assert free >= 5 * GIB, 'Root disk reserve is below 5 GiB'
    fs_uuid = test_disk(verify_disk_hash)
    daemon = json.loads(Path('/etc/docker/daemon.json').read_text())
    runtime = daemon['runtimes']['runsc']
    assert runtime['path'] == RUNSC_PATH and runtime.get('runtimeArgs') == ['--platform=systrap']
    assert hashlib.sha256(Path(runtime['path']).read_bytes()).hexdigest() == RUNSC_SHA
    runner = next(row for row in new if row['project'] == 'agentplatform-r5-app' and row['service'] == 'runner')
    api = next(row for row in new if row['project'] == 'agentplatform-r5-app' and row['service'] == 'api')
    runner_config = json.loads(call('docker', 'inspect', runner['id']))[0]['Config']
    env = dict(item.split('=', 1) for item in runner_config.get('Env', []) if '=' in item)
    helper_image = env.get('PLATFORM_WORKSPACE_RUNNER_HELPER_IMAGE')
    workspace_mount = env.get('PLATFORM_WORKSPACE_RUNNER_MOUNT_UUID')
    assert helper_image and helper_image.startswith('sha256:')
    helper = json.loads(call('docker', 'image', 'inspect', helper_image))[0]
    assert helper['Id'] == helper_image and helper['Architecture'] == 'amd64'
    image_id = env.get('PLATFORM_WORKSPACE_ENVIRONMENT_IMAGE_ID')
    assert image_id == R4_SANDBOX_ID, 'R5 runner sandbox binding differs from the verified R4 image'
    sandbox = json.loads(call('docker', 'image', 'inspect', image_id))[0]
    assert sandbox['Id'] == image_id and sandbox['Architecture'] == 'amd64' and sandbox['Os'] == 'linux'
    assert (sandbox.get('Config', {}).get('Labels') or {}).get('org.opencontainers.image.revision') == R4_SANDBOX_REVISION
    assert workspace_mount
    jar_path = artifact(jar_path, jar_sha)
    return switch, new, legacy, sandbox['Id'], helper_image, api['image'], workspace_mount, fs_uuid, jar_path, free


def process_start(pid):
    return Path('/proc/' + str(pid) + '/stat').read_text().split()[21]


def safe_remove(path, allowed_parent):
    path = Path(path).absolute()
    assert path.parent == Path(allowed_parent).absolute() or Path(allowed_parent).absolute() in path.parents
    if not path.exists() and not path.is_symlink():
        return
    if path.is_symlink():
        raise RuntimeError('Unexpected symlink at fixture cleanup boundary')
    if path.is_dir():
        for child in path.iterdir():
            safe_remove(child, path)
        path.rmdir()
    else:
        path.unlink()


def cleanup(root, snapshot):
    assert root.parent == BASE and re.fullmatch(r'r62-java-material-[0-9a-f]{32}', root.name)
    token = root.name.removeprefix('r62-java-material-')
    outer_name = 'ap-r62-java-' + token
    outer = subprocess.run(['docker', 'inspect', outer_name], capture_output=True, text=True, timeout=30)
    if outer.returncode == 0:
        record = json.loads(outer.stdout)[0]
        assert record['Name'] == '/' + outer_name and record['Config']['Labels'].get('ap.r62.java-canary') == token
        logs = subprocess.run(['docker', 'logs', record['Id']], capture_output=True, timeout=30)
        (root / 'runner.private.log').write_bytes(logs.stdout + logs.stderr)
        state = record['State']
        if state.get('Running'):
            call('docker', 'stop', '-t', '30', record['Id'], timeout=45)
            record = json.loads(call('docker', 'inspect', record['Id']))[0]
            assert not record['State']['Running'] and record['State'].get('FinishedAt')
        call('docker', 'rm', record['Id'], timeout=30)
    elif 'No such object' not in outer.stderr and 'No such container' not in outer.stderr:
        raise RuntimeError('Cannot determine whether Java canary container exists')
    runtime = root / 'runtime'
    if runtime.exists(): safe_remove(runtime, root)
    operation = root / 'operation.json'
    if operation.exists():
        operation_id = json.loads(operation.read_text())['operationId']
        child_name = 'ap-capability-material-' + operation_id
        child = subprocess.run(['docker', 'inspect', child_name], capture_output=True, text=True, timeout=30)
        if child.returncode == 0:
            record = json.loads(child.stdout)[0]
            labels = record['Config'].get('Labels') or {}
            assert record['Name'] == '/' + child_name and labels.get('ap.role') == 'capability-materialization'
            assert labels.get('ap.operation') == operation_id and labels.get('ap.workspace')
            if record['State'].get('Running'):
                call('docker', 'stop', '-t', '30', record['Id'], timeout=45)
                record = json.loads(call('docker', 'inspect', record['Id']))[0]
                assert not record['State']['Running'] and record['State'].get('FinishedAt')
            call('docker', 'rm', record['Id'], timeout=30)
        elif 'No such object' not in child.stderr and 'No such container' not in child.stderr:
            raise RuntimeError('Cannot determine whether exact capability container exists')
    work = root / 'workspaces'
    if work.exists() and subprocess.run(['findmnt', '--mountpoint', str(work)], capture_output=True).returncode == 0:
        marker = work / '.platform-capability-materials'
        if marker.exists():
            operation_id = json.loads(operation.read_text())['operationId']
            safe_remove(marker / operation_id, work)
            if marker.exists() and not any(marker.iterdir()): marker.rmdir()
        if operation.exists():
            operation_id = json.loads(operation.read_text())['operationId']
            ids = operation_id_parts(operation_id)
            space = work / ids['space']
            if space.exists(): safe_remove(space, work)
    if subprocess.run(['findmnt', '--mountpoint', str(work)], capture_output=True).returncode == 0:
        expected = json.loads((root / 'mount.json').read_text())['uuid']
        assert call('findmnt', '-n', '-o', 'UUID', '--mountpoint', work) == expected
        assert 'prjquota' in call('findmnt', '-n', '-o', 'OPTIONS', '--mountpoint', work)
        call('umount', work)
    test_disk = TEST_DISK
    for line in call('losetup', '-j', test_disk).splitlines():
        loop = line.split(':', 1)[0]
        assert re.fullmatch(r'/dev/loop[0-9]+', loop)
        backing = Path('/sys/block/' + Path(loop).name + '/loop/backing_file')
        recorded = Path('/' + backing.read_text().strip().lstrip('/')).resolve()
        assert recorded == test_disk.resolve()
        call('losetup', '-d', loop)
    assert run_blkid(TEST_DISK) == json.loads((TEST_ROOT / 'test-disk.json').read_text())['uuid']
    assert shutil.disk_usage('/').free >= 5 * GIB
    save(TEST_DISK_STATE, {'uuid': run_blkid(TEST_DISK), 'sha256': hash_file(TEST_DISK),
                           'updatedAt': int(time.time()), 'canaryRoot': str(root)})


def operation_id_parts(value):
    import hashlib as _hashlib
    result = {}
    for part in ['space', 'workspace']:
        raw = bytearray(_hashlib.md5((value + '|' + part).encode()).digest())
        raw[6] = (raw[6] & 0x0f) | 0x30
        raw[8] = (raw[8] & 0x3f) | 0x80
        result[part] = str(uuid.UUID(bytes=bytes(raw)))
    return result


def restore(switch, snapshot):
    switch.start_new(snapshot['new'])
    assert all(switch.exact(row)['State']['Running'] for row in snapshot['new'])
    assert all(switch.exact(row)['HostConfig']['RestartPolicy'] == row['restart'] for row in snapshot['new'])
    assert all(not switch.exact(row)['State']['Running'] for row in snapshot['legacy'])
    assert switch.http('/api/v1/readiness', host='124.222.55.140')[0] == 200


def watchdog(root, pid, start):
    deadline = time.monotonic() + 900
    while time.monotonic() < deadline:
        if (root / 'restored.json').exists(): return
        try:
            if process_start(pid) != start: break
        except FileNotFoundError: break
        time.sleep(3)
    else:
        cmdline = Path('/proc/' + str(pid) + '/cmdline').read_bytes()
        assert str(Path(__file__).resolve()).encode() in cmdline and process_start(pid) == start
        os.kill(pid, signal.SIGKILL)
    switch = switch_helper()
    with (OPS / 'switch.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            cleanup(root, json.loads((root / 'production-before.json').read_text()))
        except Exception:
            (root / 'watchdog-cleanup.private.log').write_text(traceback.format_exc())
        restore(switch, json.loads((root / 'production-before.json').read_text()))
        save(root / 'watchdog-restored.json', {'status': 'PASS', 'release': 'R5'})


def execute(jar_path, jar_sha):
    values = preflight(jar_path, jar_sha)
    switch, new, legacy, sandbox_id, helper_id, api_image_id, configured_mount, fs_uuid, jar_path, free = values
    token = uuid.uuid4().hex
    root = BASE / ('r62-java-material-' + token)
    assert root.parent == BASE and not root.exists()
    os.umask(0o077)
    root.mkdir(mode=0o700)
    runtime_files = prepare_runtime(jar_path, root / 'runtime')
    assert shutil.disk_usage('/').free >= 5 * GIB, 'Runtime bundle would exceed root disk reserve'
    workspaces = root / 'workspaces'; workspaces.mkdir(mode=0o700)
    operation_id = str(uuid.uuid4())
    save(root / 'operation.json', {'operationId': operation_id})
    save(root / 'mount.json', {'uuid': fs_uuid})
    snapshot = {'new': new, 'legacy': legacy, 'sandboxImage': sandbox_id, 'helperImage': helper_id,
                'apiRuntimeImage': api_image_id, 'testDisk': str(TEST_DISK), 'testUuid': fs_uuid,
                'configuredProductionMountUuid': configured_mount, 'jarSha256': jar_sha,
                'initialFreeBytes': free, 'sourceJar': jar_path.name}
    save(root / 'production-before.json', snapshot)
    loop = None; paused = False
    with (OPS / 'switch.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            # Recheck all release and disk identities after taking the shared maintenance lock.
            values = preflight(jar_path, jar_sha, verify_disk_hash=True)
            assert values[3:8] == (sandbox_id, helper_id, api_image_id, configured_mount, fs_uuid)
            loop = call('losetup', '--find', '--show', TEST_DISK)
            assert re.fullmatch(r'/dev/loop[0-9]+', loop)
            call('mount', '-o', 'prjquota,noatime', loop, workspaces)
            assert call('findmnt', '-n', '-o', 'UUID', '--mountpoint', workspaces) == fs_uuid
            assert 'prjquota' in call('findmnt', '-n', '-o', 'OPTIONS', '--mountpoint', workspaces)
            ids = operation_id_parts(operation_id)
            space = workspaces / ids['space']
            workspace = space / ids['workspace']
            work = workspace / 'work'
            space.mkdir(mode=0o755)
            workspace.mkdir(mode=0o755)
            work.mkdir(mode=0o700)
            os.chmod(space, 0o755)
            os.chmod(workspace, 0o755)
            os.chown(work, 10001, 10001)
            os.chmod(work, 0o700)
            assert shutil.disk_usage('/').free >= 5 * GIB
            daemon = json.loads(Path('/etc/docker/daemon.json').read_text())
            runtime = daemon['runtimes']['runsc']
            attestation = {'path': runtime['path'], 'sha256': RUNSC_SHA, 'runtimeArgs': runtime['runtimeArgs'],
                           'verifiedAt': int(time.time())}
            attest = root / 'runtime-attestation.json'
            attest.write_text(json.dumps(attestation, separators=(',', ':')) + '\n')
            attest.chmod(0o444)
            with (root / 'watchdog.log').open('ab', buffering=0) as log:
                subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--watch', str(root),
                                  '--pid', str(os.getpid()), '--start', process_start(os.getpid())],
                                 stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
            paused = True
            switch.stop_new(new)
            available = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines()
                                 if line.startswith('MemAvailable:'))) * 1024
            assert available >= 768 * 1024 ** 2 and shutil.disk_usage('/').free >= 5 * GIB
            outer = 'ap-r62-java-' + token
            policy = ('--memory', '384m', '--memory-swap', '384m', '--cpus', '1', '--pids-limit', '128')
            cmd = ['docker', 'create', '--name', outer, '--label', 'ap.r62.java-canary=' + token,
                   '--label', 'ap.r62.operation=' + operation_id, '--network', 'none', '--user', '0:0',
                   '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', *policy,
                   '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m,mode=700',
                   '--mount', 'type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock',
                   '--mount', 'type=bind,src=' + str(workspaces) + ',dst=' + str(workspaces),
                   '--mount', 'type=bind,src=' + str(runtime_files) + ',dst=/app/runtime,readonly',
                   '--mount', 'type=bind,src=' + str(attest) + ',dst=' + str(attest) + ',readonly',
                   '--entrypoint', 'java', api_image_id, '-Xms32m', '-Xmx192m',
                   '-cp', '/app/runtime/BOOT-INF/classes:/app/runtime/BOOT-INF/lib/*',
                   'com.agentplatform.app.workspace.R62CapabilityMountCanary', str(workspaces), sandbox_id,
                   helper_id, fs_uuid, loop, str(attest), operation_id]
            outer_id = call(*cmd)
            inspected = json.loads(call('docker', 'inspect', outer_id))[0]
            assert inspected['Image'] == api_image_id and inspected['HostConfig']['NetworkMode'] == 'none'
            assert inspected['HostConfig']['ReadonlyRootfs'] is True and inspected['HostConfig']['Memory'] == 384 * 1024 ** 2
            started_process = subprocess.run(['docker', 'start', '-a', outer_id], capture_output=True,
                                             text=True, timeout=150)
            # Persist the attached process output before checking the exit status so a
            # failed canary retains its allow-listed diagnostic line for the next run.
            (root / 'runner.private.log').write_text(started_process.stdout + started_process.stderr)
            if started_process.returncode != 0:
                raise RuntimeError('Java canary process failed with exit ' + str(started_process.returncode))
            started = started_process.stdout
            finished = json.loads(call('docker', 'inspect', outer_id))[0]
            assert finished['State']['ExitCode'] == 0 and not finished['State'].get('OOMKilled')
            assert 'R62_JAVA_CAPABILITY_MOUNT_PASS' in started
            child = subprocess.run(['docker', 'inspect', 'ap-capability-material-' + operation_id],
                                   capture_output=True, text=True, timeout=30)
            assert child.returncode != 0 and ('No such object' in child.stderr or 'No such container' in child.stderr)
            call('docker', 'rm', outer_id)
            assert run_blkid(TEST_DISK) == fs_uuid
            save(root / 'probe.json', {'status': 'PASS', 'scope': 'production DockerSandboxRuntime via docker-java; actual runsc/systrap; XFS test image reused; signed runner API/database not part of this probe',
                                       'operationId': operation_id, 'sandboxImage': sandbox_id, 'helperIdentity': helper_id,
                                       'apiRuntimeImage': api_image_id, 'testUuid': fs_uuid, 'runnerExit': 0,
                                       'containerRemoved': True, 'supplierCalls': 0})
        except BaseException:
            (root / 'failure.private.log').write_text(traceback.format_exc())
            raise
        finally:
            try:
                cleanup(root, snapshot)
            except Exception:
                (root / 'cleanup.private.log').write_text(traceback.format_exc())
                if paused:
                    restore(switch, snapshot)
                    save(root / 'restored.json', {'status': 'PASS', 'release': 'R5', 'cleanupRequiresReview': True})
                raise RuntimeError('Isolated cleanup failed; R5 restoration was attempted')
            if paused:
                restore(switch, snapshot)
                save(root / 'restored.json', {'status': 'PASS', 'release': 'R5'})
    print(json.dumps({'root': str(root), 'probe': 'PASS', 'restored': 'PASS'}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--jar-sha256')
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--watch', type=Path)
    parser.add_argument('--pid', type=int)
    parser.add_argument('--start')
    args = parser.parse_args()
    if args.watch:
        assert args.pid and args.start and not args.run and args.jar is None and args.jar_sha256 is None
        watchdog(args.watch, args.pid, args.start)
    elif args.run:
        assert args.jar and args.jar_sha256
        execute(args.jar, args.jar_sha256)
    else:
        assert args.jar and args.jar_sha256
        values = preflight(args.jar, args.jar_sha256, verify_disk_hash=True)
        print(json.dumps({'status': 'PREFLIGHT_PASS', 'r5Services': len(values[1]), 'legacyStopped': len(values[2]),
                          'sandboxImage': values[3], 'helperImage': values[4], 'testUuid': values[7],
                          'freeBytes': values[9], 'canaryJarSha256': args.jar_sha256}))


if __name__ == '__main__':
    main()
