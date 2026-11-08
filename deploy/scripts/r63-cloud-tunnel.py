"""Manage a minimal SSH forwarder for the cloud-hosted R6.3 sandbox runner.

The renderer and gVisor sandbox run only on the cloud host. This local helper
is a constrained transport client joining the isolated acceptance network.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r63-local'
PRIVATE = DATA / 'fixture'
NAME = 'ap-r63-cloud-tunnel-probe'
NETWORK = 'agentplatform-r63-local_r63'
HOST = 'root@124.222.55.140'
SSH_KEY = Path('D:/java/Dev/腾讯云服务器/lailihao_shanghai.pem')
KNOWN_HOSTS = Path('C:/Users/laili/.ssh/known_hosts')
REMOTE_PROBE = '/srv/agentplatform/acceptance/r63-signed-20261109-85875ba2/r63-cloud-tunnel-check.py'
RUNNER_NAME = '7d2f6b70138a48228a7dc5bf6807b8ce2aa7c1d2c6f00f4d9b8a7f145b3e901a'
LEGACY_CONTAINER_ID = '22d8ad15072f65267f5d7a80c251185a5a12d7077b19956ea4de73c696c4fb6c'
FAILED_CONTAINER_ID = '7f6d17bf415a8101d9cec95b8fe6b5059ce2ac3e07789388c06c648b2b7b3ee6'
IMAGE_TAG = 'agentplatform-r63-tunnel-client:20261109'
BASE_IMAGE = 'python:3.12.15-slim-trixie@sha256:2b4f19dae3a777dfc3b76730bda1e82e1f66ab2a2686fa93ca78edbfb4f04ffe'
IMAGE_REVISION = 'abc8dd11f11cfd7fd7195cb9a2012d69975b1d77'
RUNNER_ID = '7d2f6b70138a48228a7dc5bf6807b8ce2aa7c1d2c6f00f4d9b8a7f145b3e901a'
IMAGE_ID = 'sha256:7469b1ebd598dcda62bf1797165c4220614bb7e84ab798dae3363ac46bd1b2b8'
ENV = dict(os.environ, DOCKER_CONTEXT='desktop-linux')


def run(args, *, timeout=45, check=True):
    result = subprocess.run(args, cwd=ROOT, env=ENV, capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=timeout)
    if check and result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError('R63 tunnel operation failed: ' + (detail[-500:] if detail else 'no diagnostic output'))
    return result


def docker(args, **kwargs):
    return run(['docker'] + args, **kwargs)


def inspect(name):
    result = docker(['inspect', name], check=False)
    return None if result.returncode else json.loads(result.stdout)[0]


def probe_remote():
    result = run(['ssh.exe', '-i', str(SSH_KEY), '-o', 'BatchMode=yes',
        '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=' + str(KNOWN_HOSTS),
        '-o', 'ConnectTimeout=8', HOST, 'python3', REMOTE_PROBE], timeout=20)
    try:
        receipt = json.loads(result.stdout.strip())
    except json.JSONDecodeError as exc:
        raise RuntimeError('R63 remote tunnel probe returned an invalid receipt') from exc
    if receipt.get('status') != 'PASS' or not receipt.get('postgresTcp'):
        raise RuntimeError('R63 cloud-side test database/S3 forwards failed their read-only probe')
    return receipt


def ensure_image():
    dockerfile = ROOT / 'deploy/r63-tunnel-client.Dockerfile'
    dockerfile_hash = hashlib.sha256(dockerfile.read_bytes()).hexdigest()
    image = docker(['image', 'inspect', IMAGE_TAG], check=False)
    rebuild = image.returncode != 0
    if not rebuild:
        current = json.loads(image.stdout)[0]
        rebuild = (current.get('Config', {}).get('Labels', {}) or {}).get('io.agentplatform.r63.tunnel-source') != dockerfile_hash
    if rebuild:
        result = docker(['build', '--platform', 'linux/amd64', '--build-arg',
            'SOURCE_REVISION=' + IMAGE_REVISION, '--build-arg', 'SOURCE_HASH=' + dockerfile_hash,
            '--pull=false', '-f', 'deploy/r63-tunnel-client.Dockerfile',
            '-t', IMAGE_TAG, '.'], timeout=600)
        if result.returncode:
            raise RuntimeError('Could not build the isolated R63 SSH transport image: ' + (result.stderr or '')[-500:])
        image = docker(['image', 'inspect', IMAGE_TAG])
    row = json.loads(image.stdout)[0]
    labels = row.get('Config', {}).get('Labels', {}) or {}
    if (row.get('Architecture') != 'amd64' or row.get('Os') != 'linux'
            or labels.get('org.opencontainers.image.revision') != IMAGE_REVISION
            or labels.get('io.agentplatform.r63.tunnel-source') != dockerfile_hash):
        raise RuntimeError('R63 SSH transport image identity does not match the pinned build input')
    return row['Id']


def remove_old_exact_containers():
    row = inspect(NAME)
    if row is None:
        return
    labels = row.get('Config', {}).get('Labels', {}) or {}
    if row.get('Id') == LEGACY_CONTAINER_ID:
        allowed = (row.get('State', {}).get('Status') == 'exited'
                   and labels.get('ap.r63.acceptance') == 'true'
                   and row.get('Config', {}).get('Cmd') == ['-lc', 'sleep 3600'])
    elif row.get('Id') == FAILED_CONTAINER_ID:
        allowed = (row.get('State', {}).get('Status') == 'created'
                   and labels.get('ap.r63.acceptance') == 'true'
                   and labels.get('ap.r63.cloud-tunnel') == 'true')
    else:
        allowed = (labels.get('ap.r63.acceptance') == 'true'
                   and labels.get('ap.r63.cloud-tunnel') == 'true'
                   and row.get('Config', {}).get('Image') == IMAGE_TAG)
        if allowed and row.get('State', {}).get('Status') == 'running':
            return
        if allowed:
            allowed = row.get('State', {}).get('Status') in {'created', 'exited'}
    if not allowed:
        raise RuntimeError('Refusing to replace an unrecognized container named ' + NAME)
    docker(['rm', row['Id']])


def start():
    manifest = json.loads((PRIVATE / 'manifest.json').read_text(encoding='utf-8'))
    if not SSH_KEY.is_file() or not KNOWN_HOSTS.is_file():
        raise RuntimeError('Required SSH key or known-hosts file is unavailable')
    if docker(['network', 'inspect', NETWORK], check=False).returncode:
        raise RuntimeError('Start the isolated R63 fixture stack before the tunnel')
    image_id = ensure_image()
    remove_old_exact_containers()
    existing = inspect(NAME)
    if existing and existing.get('State', {}).get('Status') == 'running':
        return json.dumps({'status': 'RUNNING', 'name': NAME, 'containerId': existing['Id'],
                           'remoteProbe': probe_remote()}, sort_keys=True)

    probe = ROOT / 'deploy/probes/r63-cloud-tunnel-check.py'
    command = ['docker', 'run', '-d', '--name', NAME,
        '--label', 'ap.r63.acceptance=true', '--label', 'ap.r63.cloud-tunnel=true',
        '--label', 'ap.role=cloud-tunnel', '--restart', 'no', '--network', 'bridge',
        '--user', '10001:10001', '--memory', '128m', '--memory-swap', '128m',
        '--cpus', '0.5', '--pids-limit', '32', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges:true', '--read-only', '--tmpfs',
        '/tmp:rw,noexec,nosuid,nodev,size=8m,uid=10001,gid=10001,mode=700',
        '--mount', 'type=bind,src=' + str(SSH_KEY.resolve()) + ',dst=/run/ssh-root-key,readonly',
        '--mount', 'type=bind,src=' + str(KNOWN_HOSTS.resolve()) + ',dst=/run/known_hosts,readonly',
        IMAGE_TAG, '-N', '-T', '-i', '/run/ssh-root-key', '-o', 'BatchMode=yes',
        '-o', 'IdentitiesOnly=yes', '-o', 'IdentityAgent=none',
        '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=/run/known_hosts',
        '-o', 'ConnectTimeout=10', '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
        '-L', '0.0.0.0:18163:127.0.0.1:18163',
        '-R', '127.0.0.1:25463:postgres:5432',
        '-R', '127.0.0.1:28333:seaweedfs:8333', HOST]
    created = run(command, timeout=90)
    container_id = created.stdout.strip()
    try:
        docker(['network', 'connect', '--alias', 'runner', '--alias', 'r63-cloud-tunnel', NETWORK, container_id])
        deadline = time.monotonic() + 35
        last = None
        while time.monotonic() < deadline:
            state = inspect(container_id)
            if state['State']['Status'] != 'running':
                raise RuntimeError('R63 SSH tunnel exited before the read-only forwarding probe')
            try:
                receipt = probe_remote()
                return json.dumps({'status': 'RUNNING', 'name': NAME, 'containerId': container_id,
                                   'imageId': image_id, 'restartPolicy': 'no', 'remoteProbe': receipt}, sort_keys=True)
            except (RuntimeError, subprocess.TimeoutExpired) as exc:
                last = exc
                time.sleep(2)
        raise RuntimeError('R63 cloud-side forwards did not pass the bounded probe within 35 seconds') from last
    except Exception as exc:
        state = inspect(container_id)
        if state:
            diagnostic = docker(['logs', '--tail', '40', container_id], check=False)
            log_path = DATA / ('cloud-tunnel-failure-' + str(time.time_ns()) + '.private.log')
            log_path.parent.mkdir(parents=True, exist_ok=True)
            log_path.write_text('state=' + state.get('State', {}).get('Status', 'unknown') + '\n'
                                + (diagnostic.stdout or '') + (diagnostic.stderr or ''), encoding='utf-8')
        docker(['rm', '-f', container_id], check=False)
        raise RuntimeError('R63 SSH transport failed; inspect protected local diagnostics') from exc


def status():
    row = inspect(NAME)
    if row is None:
        print(json.dumps({'status': 'STOPPED', 'name': NAME}, sort_keys=True))
        return
    aliases = row.get('NetworkSettings', {}).get('Networks', {}).get(NETWORK, {}).get('Aliases', [])
    print(json.dumps({'status': row['State']['Status'], 'name': NAME,
        'containerId': row['Id'], 'aliases': aliases,
        'restartPolicy': row['HostConfig']['RestartPolicy']['Name']}, sort_keys=True))


def stop():
    row = inspect(NAME)
    if row is None:
        print(json.dumps({'status': 'STOPPED', 'name': NAME}, sort_keys=True))
        return
    labels = row.get('Config', {}).get('Labels', {}) or {}
    if (labels.get('ap.r63.acceptance') != 'true' or labels.get('ap.r63.cloud-tunnel') != 'true'
            or row.get('Config', {}).get('Image') != IMAGE_TAG):
        raise RuntimeError('Refusing to stop an unrecognized tunnel container')
    docker(['rm', '-f', row['Id']])
    print(json.dumps({'status': 'STOPPED', 'name': NAME}, sort_keys=True))


if __name__ == '__main__':
    actions = {'start': start, 'status': status, 'stop': stop}
    action = sys.argv[1] if len(sys.argv) == 2 else None
    if action not in actions:
        raise SystemExit('Usage: r63-cloud-tunnel.py {start|status|stop}')
    print(actions[action]()) if action == 'start' else actions[action]()
