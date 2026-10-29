"""Bounded R5.1 local acceptance entry. Never operates on production or other projects.

An independently prepared private compose.json is required for runtime actions.
Absence is NOT_PREPARED, never a simulated successful startup.
"""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r51-local'
PROJECT = 'agentplatform-r51-local'
REQUIRED = {'api', 'worker', 'runner', 'gateway', 'postgres', 'rabbitmq', 'master', 'volume', 'filer', 's3'}

def run(command, cwd=ROOT, env=None):
    result = subprocess.run([str(x) for x in command], cwd=cwd, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        DATA.mkdir(parents=True, exist_ok=True)
        (DATA / 'first-error.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        raise RuntimeError('Local action failed; inspect protected first-error.log before retrying')
    return result.stdout

def configuration():
    path = DATA / 'compose.json'
    if not path.is_file():
        raise RuntimeError('NOT_PREPARED: independent R51 runtime compose and fixture initialization are required')
    config = json.loads(path.read_text(encoding='utf-8'))
    assert config['name'] == PROJECT and REQUIRED <= config['services'].keys()
    assert all(not str(s.get('container_name', '')).startswith(('agentplatform-r4', 'agentplatform-env')) for s in config['services'].values())
    for section in ['secrets', 'configs']:
        for item in config.get(section, {}).values():
            if 'file' in item: assert Path(item['file']).resolve().is_relative_to(DATA.resolve()), 'Fixture files must be isolated'
    limit = sum(int(s.get('mem_limit', 0)) for s in config['services'].values())
    assert 0 < limit <= 2887 * 1024**2
    return path, hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'build', 'start', 'status', 'stop'])
    args = parser.parse_args()
    if args.action == 'build':
        node = ROOT / '.tools/node-v22.23.3-win-x64/node.exe'
        env = dict(os.environ, VITE_CHAT_ENABLED='true', VITE_WORKSPACE_ENABLED='true', VITE_WORKSPACE_FILES_ENABLED='true', VITE_PLAN_ENABLED='true', VITE_WORKSPACE_TOOLS_ENABLED='true')
        run([node, ROOT / 'frontend/node_modules/vite/bin/vite.js', 'build'], ROOT / 'frontend', env)
        print('FRONTEND_BUILD_PASS; runtime candidate identities must be frozen before start')
        return
    DATA.mkdir(parents=True, exist_ok=True)
    if os.name == 'nt':
        actor = subprocess.check_output(['whoami'], text=True).strip()
        run(['icacls', DATA, '/inheritance:r', '/grant:r', actor + ':(OI)(CI)F', '*S-1-5-18:(OI)(CI)F'])
    if args.action == 'status' and not (DATA / 'compose.json').exists():
        print(json.dumps({'status': 'NOT_PREPARED', 'project': PROJECT, 'runtimeVerified': False})); return
    path, digest = configuration()
    compose = ['docker', '--context', 'desktop-linux', 'compose', '-p', PROJECT, '-f', path, '--profile', 'application', '--profile', 'fixture']
    run(compose + ['config', '--quiet'])
    if args.action == 'prepare':
        print(json.dumps({'status': 'CONFIG_VALIDATED', 'sha256': digest, 'runtimeVerified': False})); return
    if args.action == 'stop':
        run(compose + ['stop', '--timeout', '30']); print('R51_STOPPED_VOLUMES_RETAINED'); return
    if args.action == 'start':
        # The private fixture must already contain initialized database grants,
        # admin bindings, workspace, keys and exact image/runtime identities.
        receipt = json.loads((DATA / 'fixture-ready.json').read_text())
        assert receipt['project'] == PROJECT and receipt['composeSha256'] == digest and receipt['status'] == 'PASS'
        run(compose + ['up', '-d', '--no-build', '--wait', '--wait-timeout', '150'])
    output = run(compose + ['ps', '-a', '--format', 'json']).strip()
    rows = json.loads(output) if output.startswith('[') else [json.loads(line) for line in output.splitlines() if line.strip()]
    by = {row['Service']: row for row in rows}
    ready = REQUIRED <= by.keys() and all(by[name].get('State') == 'running' and by[name].get('Health', '') not in ['unhealthy', 'starting'] for name in REQUIRED)
    record = {'project': PROJECT, 'status': 'RUNNING_NOT_APPLICATION_VERIFIED' if ready else 'NOT_READY', 'composeSha256': digest, 'services': {k: {'state': v.get('State'), 'health': v.get('Health')} for k, v in by.items()}, 'timestamp': time.time()}
    (DATA / 'status.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
    print(json.dumps(record))
    if args.action == 'start' and not ready: raise RuntimeError('Required services not ready; do not repeat startup blindly')

if __name__ == '__main__':
    main()
