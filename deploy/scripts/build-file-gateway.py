"""Build the R3.3 file UI from a clean source snapshot and smoke its read-only gateway.

This is a local build, not a rollout. No credentials, production access or Docker
socket mounts are used. Existing successful backend/helper images are independent.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / '.cache') or output.exists():
        parser.error('Use a new .cache evidence directory')
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT):
        parser.error('Commit reviewed source before the immutable build')
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    output.mkdir(parents=True)
    archive = output / 'source.tar'
    with archive.open('xb') as stream:
        subprocess.run(['git', 'archive', '--format=tar', source], cwd=ROOT, stdout=stream, check=True)
    checkout = output / 'source'
    checkout.mkdir()
    with tarfile.open(archive) as contents:
        contents.extractall(checkout, filter='data')
    flags = {'VITE_CHAT_ENABLED': 'false', 'VITE_WORKSPACE_ENABLED': 'true',
             'VITE_WORKSPACE_ENVIRONMENT_ENABLED': 'true', 'VITE_WORKSPACE_FILES_ENABLED': 'true'}
    # Validate the flag name against application source before spending a build/window.
    page = (checkout / 'frontend/src/features/workspace/WorkspacePage.vue').read_text(encoding='utf-8')
    assert 'import.meta.env.VITE_WORKSPACE_FILES_ENABLED' in page
    node = ROOT / '.tools/node-v22.23.3-win-x64/node.exe'
    pnpm = ROOT / '.tools/node_modules/pnpm/bin/pnpm.cjs'
    env = {**os.environ, **flags, 'OPENSPEC_TELEMETRY': '0'}
    env['PATH'] = str(node.parent) + os.pathsep + env['PATH']
    records = []

    def run(name, command, cwd=checkout):
        path = output / (name + '.log')
        with path.open('xb') as log:
            result = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        row = {'name': name, 'exitCode': result.returncode,
               'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        records.append(row)
        (output / 'progress.json').write_text(json.dumps({'source': source, 'flags': flags, 'commands': records}, indent=2), encoding='utf-8')
        print(json.dumps(row), flush=True)
        assert result.returncode == 0, 'Inspect preserved ' + name + ' log before retry'

    run('frozen-install', [str(node), str(pnpm), 'install', '--frozen-lockfile', '--offline'], checkout / 'frontend')
    for action in ['typecheck', 'test', 'contracts:check', 'contracts:test', 'links:check', 'build']:
        run(action.replace(':', '-'), [str(node), str(pnpm), action], checkout / 'frontend')
    built = '\n'.join(p.read_text(encoding='utf-8') for p in (checkout / 'frontend/dist/assets').glob('*.js'))
    assert '存储就绪后，可浏览、上传、显式替换和下载确认版本。' in built, 'File UI flag did not reach the bundle'
    tag = 'agentplatform-gateway:r33-files-' + source[:12]
    run('image-build', ['docker', 'build', '--platform', 'linux/amd64', '--build-arg', 'SOURCE_REVISION=' + source,
                       '--build-arg', 'NGINX_CONFIG=deploy/nginx.files-local.conf', '-f', 'deploy/gateway.Dockerfile', '-t', tag, '.'])
    image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', tag]))[0]
    assert image['Architecture'] == 'amd64' and image['Os'] == 'linux'
    assert image['Config']['Labels']['org.opencontainers.image.revision'] == source
    nonce = str(uuid.uuid4())
    name = 'ap-file-gateway-smoke-' + nonce
    container = None
    try:
        container = subprocess.check_output(['docker', 'run', '-d', '--name', name, '--network', 'none',
                '--label', 'ap.fixture.run=' + nonce, '--read-only', '--user', '101:101', '--cap-drop', 'ALL',
                '--security-opt', 'no-new-privileges:true', '--memory', '32m', '--memory-swap', '32m', '--pids-limit', '32',
                '--tmpfs', '/tmp:rw,nosuid,noexec,size=16m,uid=101,gid=101,mode=700', image['Id']], text=True).strip()
        time.sleep(1)
        actual = json.loads(subprocess.check_output(['docker', 'inspect', container]))[0]
        (output / 'gateway-state.json').write_text(json.dumps(actual['State'], indent=2), encoding='utf-8')
        logs = subprocess.run(['docker', 'logs', '--tail', '20', container], capture_output=True)
        (output / 'gateway.log').write_bytes(logs.stdout + logs.stderr)
        assert actual['State']['Running'] and not actual['State']['OOMKilled'], 'Gateway failed its real read-only start'
    finally:
        if container:
            inspected = subprocess.run(['docker', 'inspect', container], capture_output=True, text=True)
            if inspected.returncode == 0:
                row = json.loads(inspected.stdout)[0]
                assert row['Config']['Labels']['ap.fixture.run'] == nonce
                subprocess.run(['docker', 'stop', '--time', '5', container], check=True, capture_output=True)
                stopped = json.loads(subprocess.check_output(['docker', 'inspect', container]))[0]
                assert not stopped['State']['Running'] and stopped['State']['Pid'] == 0
                subprocess.run(['docker', 'rm', container], check=True, capture_output=True)
    result = {'status': 'BUILT_LOCAL_SMOKE_PASS_NOT_DEPLOYED', 'source': source, 'tag': tag, 'localId': image['Id'],
              'flags': flags, 'commands': records, 'readonlyGatewayStarted': True, 'fixtureStopped': True,
              'sourceArchiveSha256': hashlib.sha256(archive.read_bytes()).hexdigest()}
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({k: result[k] for k in ['status', 'source', 'tag', 'localId', 'fixtureStopped']}))


if __name__ == '__main__':
    main()
