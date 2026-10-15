"""R1.1 isolated local Docker fixture. Never contacts the cloud or legacy projects."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r11-local'
DATA.mkdir(parents=True, exist_ok=True)
ENV = dict(os.environ, R11_ROOT=DATA.as_posix(),
           R11_API_IMAGE='agentplatform-api:r1.1-local',
           R11_GATEWAY_IMAGE='agentplatform-gateway:r1.1-local')
COMPOSE = ['docker', 'compose', '-p', 'agentplatform-r11-local', '-f', 'deploy/compose.identity-local.yaml']


def run(command, **kwargs):
    return subprocess.run(command, cwd=ROOT, env=ENV, check=True, **kwargs)


def sql(text):
    return run(COMPOSE + ['exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'identity'], input=text, text=True, capture_output=True).stdout


def operator(action, name, request=None, password=None, expect_success=True):
    # stdin -> private tmpfs file, never process arguments or persisted Docker environment.
    args = ['--identity-action=' + action, '--username=' + name]
    if request:
        args.append('--request-id=' + request)
    if password is not None:
        args.append('--password-file=/tmp/operator-password')
    shell = 'umask 077; cat > /tmp/operator-password; java -Xms16m -Xmx160m -jar /app/platform.jar "$@"; result=$?; rm -f /tmp/operator-password; exit "$result"'
    result = subprocess.run(COMPOSE + ['--profile', 'operator', 'run', '--rm', '-T', '--entrypoint', 'sh', 'operator', '-c', shell, 'operator'] + args,
                            cwd=ROOT, env=ENV, input=password or '', text=True, capture_output=True)
    if (result.returncode == 0) != expect_success:
        (DATA / 'operator-failure.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        raise RuntimeError('Operator result unexpected; inspect protected local log')
    return result.returncode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['prepare', 'migrate', 'build', 'grant', 'seed', 'start', 'stop', 'restart', 'disable-alice', 'reset-alice'])
    mode = parser.parse_args().mode
    if mode == 'prepare':
        if os.name == 'nt':
            actor = subprocess.check_output(['whoami'], text=True).strip()
            run(['icacls', str(DATA), '/inheritance:r', '/grant:r', actor + ':(OI)(CI)F', 'SYSTEM:(OI)(CI)F'], capture_output=True)
        for name in ['postgres-password', 'api-password', 'alice-password', 'bob-password']:
            path = DATA / name
            if not path.exists():
                path.write_text(secrets.token_urlsafe(32), encoding='utf-8')
                path.chmod(0o600)
        run(COMPOSE + ['config', '--quiet'])
        run(COMPOSE + ['up', '-d', '--wait', '--wait-timeout', '90', 'postgres'])
        exists = sql("SELECT 1 FROM pg_database WHERE datname='identity_test';")
        if '(1 row)' not in exists:
            sql('CREATE DATABASE identity_test;')
    elif mode == 'build':
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        for component in ['api', 'gateway']:
            run(['docker', 'build', '--platform', 'linux/amd64', '--build-arg', 'SOURCE_REVISION=' + revision, '-f', f'deploy/{component}.Dockerfile', '-t', ENV['R11_' + component.upper() + '_IMAGE'], '.'])
        identities = {}
        for component in ['API', 'GATEWAY']:
            info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', ENV['R11_' + component + '_IMAGE']], cwd=ROOT))[0]
            identities[component] = {'id': info['Id'], 'architecture': info['Architecture'], 'os': info['Os'], 'source_revision': revision}
        (DATA / 'images.json').write_text(json.dumps(identities, indent=2), encoding='utf-8')
    elif mode == 'migrate':
        run(COMPOSE + ['--profile', 'operator', 'run', '--rm', '-T', '-e', 'SPRING_PROFILES_ACTIVE=cloud-migrate', 'operator'])
    elif mode == 'grant':
        password = (DATA / 'api-password').read_text().strip()
        assert all(c.isalnum() or c in '-_' for c in password)
        sql("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ap_identity') THEN CREATE ROLE ap_identity LOGIN; END IF; END $$;\n"
            + "ALTER ROLE ap_identity PASSWORD '" + password + "';\n"
            + "GRANT CONNECT ON DATABASE identity TO ap_identity; GRANT USAGE ON SCHEMA pf TO ap_identity;\n"
            + "GRANT SELECT ON pf.app_user,pf.space,pf.membership,pf.space_storage,pf.baseline_version TO ap_identity;\n"
            + "GRANT SELECT,INSERT,UPDATE,DELETE ON pf.auth_session,pf.auth_rate TO ap_identity;\n")
        print('Least-privilege runtime grants installed; no password output.')
    elif mode == 'seed':
        import uuid
        requests = DATA / 'provision-requests.json'
        ids = json.loads(requests.read_text()) if requests.exists() else {n: str(uuid.uuid4()) for n in ['alice', 'bob']}
        requests.write_text(json.dumps(ids), encoding='utf-8')
        for name in ['alice', 'bob']:
            password = (DATA / (name + '-password')).read_text().strip()
            operator('bootstrap' if name == 'alice' else 'create', name, ids[name], password)
            operator('bootstrap' if name == 'alice' else 'create', name, ids[name], password)
        operator('bootstrap', 'another-admin', str(uuid.uuid4()), (DATA / 'bob-password').read_text().strip(), False)
        operator('create', 'alice', ids['alice'], 'different-fixture-password', False)
        print('Operator bootstrap/create/retry/conflict boundaries passed.')
    elif mode == 'start':
        run(COMPOSE + ['up', '-d', '--wait', '--wait-timeout', '180', 'api', 'gateway'])
    elif mode == 'stop':
        run(COMPOSE + ['stop'])  # Preserve this fixture's volumes and secrets.
    elif mode == 'restart':
        run(COMPOSE + ['restart', 'api'])
    elif mode == 'disable-alice':
        operator('disable', 'alice')
    elif mode == 'reset-alice':
        operator('reset-password', 'alice', password=(DATA / 'alice-password').read_text().strip())


if __name__ == '__main__':
    main()
