"""Host-only R6.2 isolated read-only mount probe. Default action is read-only preflight.

The run action is a bounded maintenance window and keeps an independent watchdog.
It never edits a production volume, the existing XFS image, or Docker runtime settings.
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
import subprocess
import sys
import time
import traceback
import uuid

BASE = Path('/srv/agentplatform/acceptance')
OPS = Path('/srv/agentplatform/operations/platform-switch')
HELPER = OPS / 'platform-switch.py'
HELPER_SHA = '92aeb5fc0ec8f363aad6195e0166f320b6bce2f304d56f61bcea3319cefe56e4'
RUNTIME_SHA = '210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a'
RUNTIME_PATH = '/usr/local/lib/agentplatform-gvisor/20261005.0/runsc'
IMAGE = 'agentplatform-sandbox:r32-88395340b150'
GIB = 1024 ** 3


def run(*args, timeout=120):
    p = subprocess.run([str(x) for x in args], capture_output=True, text=True, timeout=timeout)
    if p.returncode:
        raise RuntimeError('Command failed: ' + str(args[0]) + ' (exit ' + str(p.returncode) + ')')
    return p.stdout.strip()


def save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.chmod(0o600)
    os.replace(temporary, path)


def switch_helper():
    assert hashlib.sha256(HELPER.read_bytes()).hexdigest() == HELPER_SHA, 'Switch helper identity changed'
    spec = importlib.util.spec_from_file_location('platform_switch', HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert module.release_profile()[0] == 'agentplatform-r5-app', 'Selected release changed'
    return module


def preflight():
    switch = switch_helper()
    assert BASE.is_dir() and BASE.resolve() == BASE, 'Acceptance root changed'
    new, legacy = switch.inventory()
    assert len(new) == 11 and all(row['running'] for row in new), 'R5 not fully running'
    assert all(not row['running'] for row in legacy), 'Legacy platform is running'
    running = set(run('docker', 'ps', '-q', '--no-trunc').split())
    assert running == {row['id'] for row in new}, 'Other running containers require separate resource review'
    assert switch.idle(new), 'Production has active work; do not pause it'
    assert shutil.disk_usage('/').free >= 9 * GIB, 'Insufficient disk for isolated XFS and reserve'
    daemon = json.loads(Path('/etc/docker/daemon.json').read_text())
    runtime = daemon['runtimes']['runsc']
    assert runtime['path'] == RUNTIME_PATH and runtime.get('runtimeArgs') == ['--platform=systrap']
    assert hashlib.sha256(Path(runtime['path']).read_bytes()).hexdigest() == RUNTIME_SHA
    image = json.loads(run('docker', 'image', 'inspect', IMAGE))[0]
    assert image['Architecture'] == 'amd64'
    return switch, new, legacy, image['Id']


def process_start(pid):
    return Path('/proc/' + str(pid) + '/stat').read_text().split()[21]


def restore(switch, snapshot):
    switch.start_new(snapshot['new'])
    assert all(switch.exact(row)['State']['Running'] for row in snapshot['new'])
    assert all(switch.exact(row)['HostConfig']['RestartPolicy'] == row['restart'] for row in snapshot['new'])
    assert all(not switch.exact(row)['State']['Running'] for row in snapshot['legacy'])
    assert switch.http('/api/v1/readiness', host='124.222.55.140')[0] == 200


def cleanup_fixture(root):
    assert root.parent == BASE and re.fullmatch(r'r62-mount-poc-[0-9a-f]{32}', root.name)
    token = root.name.removeprefix('r62-mount-poc-')
    name = 'ap-r62-mount-' + token
    result = subprocess.run(['docker', 'inspect', name], capture_output=True, text=True, timeout=30)
    if result.returncode == 0:
        container = json.loads(result.stdout)[0]
        assert container['Name'] == '/' + name and container['Config']['Labels'].get('ap.r62.probe') == token
        assert all(item['Source'].startswith(str(root) + '/') for item in container['Mounts'])
        logs = subprocess.run(['docker', 'logs', container['Id']], capture_output=True, timeout=30)
        (root / 'container.private.log').write_bytes(logs.stdout + logs.stderr)
        run('docker', 'rm', '-f', container['Id'], timeout=30)
    elif 'No such object' not in result.stderr and 'No such container' not in result.stderr:
        raise RuntimeError('Cannot determine whether fixture container exists')
    mount = root / 'workspaces'
    disk = root / 'test.xfs'
    if subprocess.run(['findmnt', '--mountpoint', str(mount)], capture_output=True).returncode == 0:
        expected = json.loads((root / 'test-disk.json').read_text())
        assert run('findmnt', '-n', '-o', 'UUID', '--mountpoint', mount) == expected['uuid']
        run('umount', mount)
    matches = run('losetup', '-j', disk).splitlines() if disk.exists() else []
    for line in matches:
        loop = line.split(':', 1)[0]
        assert re.fullmatch(r'/dev/loop[0-9]+', loop)
        backing = Path('/sys/block/' + Path(loop).name + '/loop/backing_file')
        assert Path('/' + backing.read_text().strip().lstrip('/')).resolve() == disk.resolve()
        run('losetup', '-d', loop)


def watchdog(root, pid, start):
    assert root.parent == BASE and re.fullmatch(r'r62-mount-poc-[0-9a-f]{32}', root.name)
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
            cleanup_fixture(root)
        except Exception:
            (root / 'watchdog-cleanup.private.log').write_text(traceback.format_exc())
        restore(switch, json.loads((root / 'production-before.json').read_text()))
        save(root / 'watchdog-restored.json', {'status': 'PASS', 'supplierCalls': 0})


def execute():
    switch, new, legacy, image_id = preflight()
    token = uuid.uuid4().hex
    root = BASE / ('r62-mount-poc-' + token)
    assert root.parent == BASE and not root.exists()
    os.umask(0o077)
    root.mkdir(mode=0o700)
    assert root.resolve() == root and not root.is_symlink()
    mount = root / 'workspaces'
    mount.mkdir()
    image = root / 'test.xfs'
    loop = None
    container_id = None
    paused = False
    with (OPS / 'switch.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            # All prerequisites are rechecked while holding the platform switch lock.
            switch, new, legacy, current_image = preflight()
            assert current_image == image_id
            image.touch(mode=0o600, exist_ok=False)
            run('fallocate', '-l', str(4 * GIB), image)
            fs_uuid = str(uuid.uuid4())
            run('mkfs.xfs', '-f', '-m', 'uuid=' + fs_uuid, image)
            save(root / 'test-disk.json', {'uuid': fs_uuid})
            assert image.stat().st_size == 4 * GIB and image.stat().st_blocks * 512 >= 4 * GIB
            assert shutil.disk_usage('/').free >= 5 * GIB, 'Disk reserve violated after allocation'
            loop = run('losetup', '--find', '--show', image)
            assert re.fullmatch(r'/dev/loop[0-9]+', loop)
            run('mount', '-o', 'prjquota,noatime', loop, mount)
            assert run('findmnt', '-n', '-o', 'UUID', '--target', mount) == fs_uuid
            assert 'prjquota' in run('findmnt', '-n', '-o', 'OPTIONS', '--target', mount)
            work = mount / 'work'; material = mount / 'material'
            work.mkdir(mode=0o700); material.mkdir(mode=0o700)
            os.chown(work, 10001, 10001); os.chown(material, 10001, 10001)
            fixture = material / 'SKILL.md'; fixture.write_text('r62-test-material\n'); os.chown(fixture, 10001, 10001)
            snapshot = {'new': new, 'legacy': legacy, 'testUuid': fs_uuid, 'image': image_id}
            save(root / 'production-before.json', snapshot)
            with (root / 'watchdog.log').open('ab', buffering=0) as log:
                subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--watch', str(root),
                                  '--pid', str(os.getpid()), '--start', process_start(os.getpid())],
                                 stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
            paused = True
            switch.stop_new(new)
            available = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines()
                                 if line.startswith('MemAvailable:'))) * 1024
            assert available >= 768 * 1024 ** 2 and shutil.disk_usage('/').free >= 5 * GIB
            target = '/capabilities/00000000-0000-4000-8000-000000000001/00000000-0000-4000-8000-000000000002'
            program = ('from pathlib import Path\nimport errno\n'
                       'p=Path("' + target + '/SKILL.md")\nassert p.read_text()=="r62-test-material\\n"\n'
                       'Path("/workspace/out").write_text("ok")\n'
                       'try:\n p.write_text("changed")\n raise AssertionError("readonly mount accepted write")\n'
                       'except OSError as e:\n assert e.errno==errno.EROFS\nprint("R62_READONLY_MOUNT_PASS")\n')
            container_id = run('docker', 'create', '--name', 'ap-r62-mount-' + token, '--runtime', 'runsc',
                               '--label', 'ap.r62.probe=' + token,
                               '--user', '10001:10001', '--network', 'none', '--read-only', '--cpus', '1',
                               '--memory', '512m', '--memory-swap', '512m', '--pids-limit', '128',
                               '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                               '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m,uid=10001,gid=10001,mode=700',
                               '--mount', 'type=bind,src=' + str(work) + ',dst=/workspace',
                               '--mount', 'type=bind,src=' + str(material) + ',dst=' + target + ',readonly',
                               '--entrypoint', 'python3', image_id, '-c', program)
            inspected = json.loads(run('docker', 'inspect', container_id))[0]
            assert inspected['Image'] == image_id and inspected['HostConfig']['Runtime'] == 'runsc'
            host = inspected['HostConfig']
            assert inspected['Config']['User'] == '10001:10001' and host['ReadonlyRootfs'] is True
            assert host['NetworkMode'] == 'none' and host['Memory'] == 512 * 1024 ** 2
            assert host['MemorySwap'] == 512 * 1024 ** 2 and host['NanoCpus'] == 1000000000 and host['PidsLimit'] == 128
            assert 'ALL' in host['CapDrop'] and 'no-new-privileges:true' in host['SecurityOpt']
            mounts = {item['Destination']: item for item in inspected['Mounts']}
            assert set(mounts) == {'/workspace', target} and mounts['/workspace']['RW'] and not mounts[target]['RW']
            assert mounts['/workspace']['Source'] == str(work) and mounts[target]['Source'] == str(material)
            output = run('docker', 'start', '-a', container_id, timeout=60)
            assert 'R62_READONLY_MOUNT_PASS' in output and (work / 'out').read_text() == 'ok'
            assert fixture.read_text() == 'r62-test-material\n'
            save(root / 'probe.json', {'status': 'PASS', 'scope': 'isolated gVisor read-only bind only',
                                       'image': image_id, 'runtime': 'runsc', 'testUuid': fs_uuid, 'supplierCalls': 0})
        except BaseException:
            (root / 'failure.private.log').write_text(traceback.format_exc())
            raise
        finally:
            cleanup_ok = True
            try:
                cleanup_fixture(root)
            except Exception:
                cleanup_ok = False
                (root / 'cleanup.private.log').write_text(traceback.format_exc())
            if paused:
                restore(switch, snapshot)
                save(root / 'restored.json', {'status': 'PASS', 'release': 'R5', 'fixtureCleanup': cleanup_ok, 'supplierCalls': 0})
            if not cleanup_ok:
                raise RuntimeError('R5 restored, but isolated fixture cleanup requires inspection')
    print(json.dumps({'root': str(root), 'probe': 'PASS', 'restored': 'PASS'}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--watch', type=Path)
    parser.add_argument('--pid', type=int)
    parser.add_argument('--start')
    args = parser.parse_args()
    if args.watch:
        assert args.pid and args.start and not args.run
        watchdog(args.watch, args.pid, args.start)
    elif args.run:
        execute()
    else:
        _, new, legacy, image_id = preflight()
        print(json.dumps({'status': 'PREFLIGHT_PASS', 'r5Services': len(new), 'legacyStopped': len(legacy),
                          'freeBytes': shutil.disk_usage('/').free, 'sandboxImage': image_id}))


if __name__ == '__main__':
    main()
