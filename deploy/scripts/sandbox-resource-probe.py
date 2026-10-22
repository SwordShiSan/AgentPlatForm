"""Fixed isolated acceptance programs. No user code, path, endpoint or limit arguments."""
import errno
import importlib.util
import json
import os
from pathlib import Path
import signal
import socket
import sys
import threading
import time
import uuid


def cpu():
    started = time.monotonic()
    children = []
    for _ in range(2):
        child = os.fork()
        if child == 0:
            until = time.monotonic() + 8
            value = 1
            while time.monotonic() < until:
                value = (value * 1664525 + 1013904223) % 4294967296
            os._exit(0)
        children.append(child)
    consumed = 0
    for child in children:
        _, status, usage = os.wait4(child, 0)
        assert status == 0
        consumed += usage.ru_utime + usage.ru_stime
    elapsed = time.monotonic() - started
    # wait4 is the sandbox's accounting view. The exact host cgroup quota,
    # usage and throttling deltas are the authority for the CPU1 boundary.
    assert consumed > 1 and elapsed >= 8, (consumed, elapsed)
    return {"guestSampleCpuSeconds": consumed, "sampleWallSeconds": elapsed,
            "enforcementEvidence": "REQUIRES_HOST_CGROUP_ASSERTION"}


def pids():
    children = []
    denied = False
    try:
        for _ in range(140):
            try:
                child = os.fork()
            except OSError as failure:
                assert failure.errno in (errno.EAGAIN, errno.ENOMEM)
                denied = True
                break
            if child == 0:
                time.sleep(30)
                os._exit(0)
            children.append(child)
        assert denied and 8 <= len(children) < 128, len(children)
        return {"childrenBeforeRefusal": len(children)}
    finally:
        for child in children:
            try:
                os.kill(child, signal.SIGKILL)
            except ProcessLookupError:
                pass
        for child in children:
            os.waitpid(child, 0)


def memory():
    # This process is expected to be killed by the exact512MiB container boundary.
    # The host/runner verifies OOM/exit/termination independently; no PASS print here.
    blocks = []
    while True:
        blocks.append(bytearray(16 * 1024 * 1024))


def network():
    assert os.getuid() == 10001 and os.getgid() == 10001
    assert not Path('/var/run/docker.sock').exists() and not Path('/run/secrets').exists()
    assert b'sandbox-entrypoint.py' in Path('/proc/1/cmdline').read_bytes()
    assert all(name == 'lo' for _, name in socket.if_nameindex())
    try:
        Path('/r32-root-write-must-fail').write_bytes(b'x')
        raise AssertionError('read-only root was writable')
    except OSError as failure:
        assert failure.errno in (errno.EROFS, errno.EACCES)
    with socket.socket() as client:
        client.settimeout(1)
        assert client.connect_ex(('1.1.1.1', 53)) != 0
    return {"networkUnavailable": True, "rootReadOnly": True, "socketAndSecretsAbsent": True}


def temporary():
    file = Path('/tmp/r32-tmp-limit')
    try:
        with file.open('xb') as stream:
            for _ in range(80):
                stream.write(b'x' * 1048576)
            stream.flush()
        raise AssertionError('tmpfs limit not enforced')
    except OSError as failure:
        assert failure.errno in (errno.ENOSPC, errno.EDQUOT)
        return {"refusalErrno": failure.errno}
    finally:
        file.unlink(missing_ok=True)


def byte_quota():
    file = Path('/workspace/r32-byte-quota')
    try:
        with file.open('xb') as stream:
            # Preallocation tests the real filesystem quota without generating2GiB
            # in Python memory. Additional allocation must exceed the2GiB project.
            os.posix_fallocate(stream.fileno(), 0, 2146435072)
            try:
                os.posix_fallocate(stream.fileno(), 2146435072, 2097152)
                raise AssertionError('byte quota not enforced')
            except OSError as failure:
                assert failure.errno in (errno.EDQUOT, errno.ENOSPC)
                return {"refusalErrno": failure.errno, "preallocatedBytes": 2146435072}
    finally:
        file.unlink(missing_ok=True)


def inode_quota():
    directory = Path('/workspace/r32-inode-quota')
    directory.mkdir(mode=0o700)
    count = 0
    try:
        for index in range(100005):
            fd = os.open(directory / str(index), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(fd)
            count += 1
        raise AssertionError('inode quota not enforced')
    except OSError as failure:
        assert failure.errno in (errno.EDQUOT, errno.ENOSPC)
        assert 99900 <= count < 100000, count
        return {"refusalErrno": failure.errno, "createdBeforeRefusal": count}
    finally:
        for index in range(count):
            (directory / str(index)).unlink()
        directory.rmdir()


def path_race():
    spec = importlib.util.spec_from_file_location('fixed_bridge', '/opt/platform/sandbox-file-bridge.py')
    bridge = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bridge)
    root = Path('/workspace')
    outside = Path('/tmp/r32-outside')
    outside.mkdir(mode=0o700)
    marker = outside / 'marker'
    marker.write_bytes(b'outside-unchanged')
    link, hard, fifo = (root / name for name in ('r32-link', 'r32-hard', 'r32-fifo'))
    original = root / 'r32-link-source'
    original.write_bytes(b'source')
    try:
        link.symlink_to(marker)
        os.link(original, hard)
        os.mkfifo(fifo, 0o600)
        for file in (link, hard, fifo):
            try:
                bridge.execute({'action': 'READ', 'path': file.name})
                raise AssertionError('unsafe kind accepted')
            except (bridge.Rejected, OSError):
                pass
    finally:
        for file in (link, hard, fifo, original):
            file.unlink(missing_ok=True)
    target, held = root / 'r32-race', root / 'r32-race-held'
    target.mkdir(mode=0o700)
    stop = threading.Event()
    def attack():
        while not stop.is_set():
            target.rename(held)
            target.symlink_to(outside, target_is_directory=True)
            target.unlink()
            held.rename(target)
    attacker = threading.Thread(target=attack)
    attacker.start()
    accepted = rejected = 0
    try:
        for _ in range(1000):
            try:
                result = bridge.execute({'action': 'WRITE', 'path': 'r32-race/marker', 'contentBase64': 'c2FmZQ=='})
                assert result['ok']
                accepted += 1
            except (bridge.Rejected, OSError):
                rejected += 1
        assert marker.read_bytes() == b'outside-unchanged'
    finally:
        stop.set()
        attacker.join(timeout=3)
        assert not attacker.is_alive()
        (target / 'marker').unlink(missing_ok=True)
        target.rmdir()
        marker.unlink()
        outside.rmdir()
    return {"raceAccepted": accepted, "raceRejected": rejected, "outsideUnchanged": True}


PROGRAMS = {'CPU_BOUND': cpu, 'PID_BOUND': pids, 'MEMORY_BOUND': memory, 'NETWORK_BOUND': network,
            'TMP_BOUND': temporary, 'BYTE_QUOTA': byte_quota, 'INODE_QUOTA': inode_quota, 'PATH_RACE': path_race}

if __name__ == '__main__':
    assert len(sys.argv) == 2 and sys.argv[1] in PROGRAMS
    assert os.getuid() == 10001 and Path('/workspace').is_dir()
    uuid.UUID(os.environ['PLATFORM_USE_ID'])
    result = PROGRAMS[sys.argv[1]]()
    print(json.dumps({'ok': True, 'probe': sys.argv[1], **result}, separators=(',', ':')), flush=True)
