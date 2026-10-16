"""R1.2 isolated local fixture. No cloud operations or automatic Docker Desktop startup."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
from docker_preflight import require_linux

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r12-local'
ENV = dict(os.environ, R12_ROOT=DATA.as_posix())
COMPOSE = ['docker', 'compose', '-p', 'agentplatform-r12-local', '-f', 'deploy/compose.chat-local.yaml']


def run(command, **kwargs):
    return subprocess.run(command, cwd=ROOT, env=ENV, check=True, **kwargs)


def sql(text):
    return run(COMPOSE + ['exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'chat'],
               input=text, text=True, capture_output=True).stdout


def operator(arguments, private_input=''):
    shell = 'umask 077; cat > /tmp/operator-input; java -Xms16m -Xmx160m -jar /app/platform.jar "$@"; result=$?; rm -f /tmp/operator-input; exit "$result"'
    result = subprocess.run(COMPOSE + ['--profile', 'operator', 'run', '--rm', '-T', '--entrypoint', 'sh', 'operator', '-c', shell, 'operator'] + arguments,
                            cwd=ROOT, env=ENV, input=private_input, text=True, capture_output=True)
    if result.returncode:
        (DATA / 'operator-failure.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        raise RuntimeError('Operator failed; inspect protected local log')
    return result.stdout


def definitions():
    users = []
    for name in ['api', 'worker', 'operator']:
        secret = (DATA / ('mq-' + name + '-password')).read_text().strip()
        salt = secrets.token_bytes(4)
        digest = base64.b64encode(salt + hashlib.sha256(salt + secret.encode()).digest()).decode()
        users.append({'name': 'ap_' + name, 'password_hash': digest, 'hashing_algorithm': 'rabbit_password_hashing_sha256', 'tags': ['administrator'] if name == 'operator' else []})
    permission = lambda name, configure, write, read: {'user': 'ap_' + name, 'vhost': '/agentplatform', 'configure': configure, 'write': write, 'read': read}
    value = {'users': users, 'vhosts': [{'name': '/agentplatform'}],
             'permissions': [permission('api', '^$', '^ap.commands.v1$', '^$'), permission('worker', '^$', '^$', '^ap.execution.v1$'), permission('operator', '.*', '.*', '.*')],
             'exchanges': [{'name': name, 'vhost': '/agentplatform', 'type': 'direct', 'durable': True, 'auto_delete': False, 'internal': False, 'arguments': {}} for name in ['ap.commands.v1', 'ap.dead.v1']],
             'queues': [{'name': name, 'vhost': '/agentplatform', 'durable': True, 'auto_delete': False, 'arguments': arguments} for name, arguments in [('ap.execution.v1', {'x-queue-type': 'quorum', 'x-dead-letter-exchange': 'ap.dead.v1', 'x-dead-letter-routing-key': 'execution.dead'}), ('ap.execution.dead.v1', {'x-queue-type': 'quorum'})]],
             'bindings': [{'source': source, 'vhost': '/agentplatform', 'destination': destination, 'destination_type': 'queue', 'routing_key': key, 'arguments': {}} for source, destination, key in [('ap.commands.v1', 'ap.execution.v1', 'execution.ready'), ('ap.dead.v1', 'ap.execution.dead.v1', 'execution.dead')]]}
    (DATA / 'rabbitmq-definitions.json').write_text(json.dumps(value), encoding='utf-8')
    (DATA / 'rabbitmq.conf').write_text('definitions.import_backend = local_filesystem\ndefinitions.local.path = /etc/rabbitmq/definitions.json\nvm_memory_high_watermark.absolute = 128MiB\ndisk_free_limit.absolute = 256MiB\nlog.console.level = warning\nconsumer_timeout = 180000\n', encoding='utf-8')


def grant():
    for role, file in [('ap_chat_api', 'api-password'), ('ap_chat_worker', 'worker-password')]:
        password = (DATA / file).read_text().strip()
        assert all(c.isalnum() or c in '-_' for c in password)
        sql(f"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='{role}') THEN CREATE ROLE {role} LOGIN; END IF; END $$;\n"
            f"ALTER ROLE {role} PASSWORD '{password}'; GRANT CONNECT ON DATABASE chat TO {role}; GRANT USAGE ON SCHEMA pf TO {role};")
    common = 'pf.app_user,pf.space,pf.membership,pf.space_storage,pf.baseline_version,pf.identity_operation,pf.model_definition'
    sql(f'GRANT SELECT ON {common} TO ap_chat_api,ap_chat_worker;')
    sql('GRANT EXECUTE ON FUNCTION pf.authorize_chat_actor(uuid,uuid) TO ap_chat_api,ap_chat_worker; '
        'GRANT EXECUTE ON FUNCTION pf.authorize_chat_credential(uuid,bigint,uuid,uuid) TO ap_chat_worker;')
    sql('GRANT SELECT,INSERT,UPDATE,DELETE ON pf.auth_session,pf.auth_rate TO ap_chat_api;\n'
        'GRANT SELECT(id,scope_type,space_id,owner_user_id,status,revision) ON pf.credential TO ap_chat_api;\n'
        'GRANT SELECT ON pf.credential,pf.model_binding TO ap_chat_worker; GRANT SELECT ON pf.model_binding TO ap_chat_api;\n'
        'GRANT SELECT,INSERT,UPDATE ON pf.runtime_bundle,pf.chat_config_snapshot TO ap_chat_api; GRANT SELECT ON pf.runtime_bundle,pf.chat_config_snapshot TO ap_chat_worker;\n'
        'GRANT SELECT,INSERT,UPDATE ON pf.conversation,pf.task,pf.message,pf.platform_event,pf.execution_dispatch,pf.execution_slot,pf.budget_account TO ap_chat_api,ap_chat_worker;\n'
        'GRANT SELECT,INSERT,UPDATE ON pf.event_outbox,pf.idempotency_record TO ap_chat_api;\n'
        'GRANT SELECT ON pf.model_call TO ap_chat_api;\n'
        'GRANT SELECT,INSERT,UPDATE ON pf.attempt,pf.consumer_inbox,pf.model_call,pf.budget_reservation,pf.credential_access_audit,pf.acceptance_model_meter,pf.dispatch_quarantine TO ap_chat_worker;\n'
        'GRANT USAGE ON SCHEMA pf_runtime TO ap_chat_worker; GRANT SELECT,INSERT,UPDATE,DELETE ON pf_runtime.state_slot TO ap_chat_worker;')
    print('Runtime role grants applied; API has no credential ciphertext/master-key access.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['prepare', 'build', 'migrate', 'grant', 'seed', 'start', 'reconcile-stopped', 'stop', 'status'])
    mode = parser.parse_args().mode
    require_linux()
    if mode == 'prepare':
        DATA.mkdir(parents=True, exist_ok=True)
        if os.name == 'nt':
            actor = subprocess.check_output(['whoami'], text=True).strip()
            run(['icacls', str(DATA), '/inheritance:r', '/grant:r', actor + ':(OI)(CI)F', 'SYSTEM:(OI)(CI)F'], capture_output=True)
        for name in ['postgres-password', 'api-password', 'worker-password', 'alice-password', 'bob-password', 'mq-api-password', 'mq-worker-password', 'mq-operator-password']:
            path = DATA / name
            if not path.exists():
                path.write_text(secrets.token_urlsafe(32), encoding='utf-8')
                path.chmod(0o600)
        for name in ['credential-master-key', 'cursor-key']:
            path = DATA / name
            if not path.exists():
                path.write_bytes(secrets.token_bytes(32))
                path.chmod(0o600)
        definitions()
        run(COMPOSE + ['--profile', 'fixture', 'config', '--quiet'])
        run(COMPOSE + ['up', '-d', '--wait', '--wait-timeout', '90', 'postgres'])
        if '(1 row)' not in sql("SELECT 1 FROM pg_database WHERE datname='chat_test';"):
            sql('CREATE DATABASE chat_test;')
        print('R1.2 isolated database ready; no provider call or key import performed.')
    elif mode == 'build':
        # Reproducible Maven artifacts have a fixed timestamp, so mtime cannot
        # prove freshness. Package current sources explicitly before Docker COPY.
        wrapper = ROOT / 'backend' / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
        run([str(wrapper), '-f', str(ROOT / 'backend/pom.xml'), '-DskipTests', 'package', '-q'])
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        for component, image in [('api', 'agentplatform-api:r1.2-local'), ('gateway', 'agentplatform-gateway:r1.2-local'), ('provider-fixture', 'agentplatform-provider-fixture:r1.2-local')]:
            run(['docker', 'build', '--platform', 'linux/amd64', '--build-arg', 'SOURCE_REVISION=' + revision, '-f', f'deploy/{component}.Dockerfile', '-t', image, '.'])
        print('Development images built; dirty-source candidate is not final acceptance evidence.')
    elif mode == 'migrate':
        run(COMPOSE + ['--profile', 'operator', 'run', '--rm', '-T', '-e', 'SPRING_PROFILES_ACTIVE=cloud-migrate', 'operator'])
    elif mode == 'grant':
        grant()
    elif mode == 'seed':
        import re
        import uuid
        requests = DATA / 'provision-requests.json'
        ids = json.loads(requests.read_text()) if requests.exists() else {name: str(uuid.uuid4()) for name in ['alice', 'bob']}
        requests.write_text(json.dumps(ids), encoding='utf-8')
        for name in ['alice', 'bob']:
            action = 'bootstrap' if name == 'alice' else 'create'
            run_args = ['--identity-action=' + action, '--username=' + name, '--request-id=' + ids[name], '--password-file=/tmp/operator-input']
            # Identity operator uses the same isolated database, via an explicit profile override.
            previous = ENV.get('SPRING_PROFILES_ACTIVE')
            shell = 'umask 077; cat > /tmp/operator-input; java -Xms16m -Xmx160m -jar /app/platform.jar "$@"; result=$?; rm -f /tmp/operator-input; exit "$result"'
            result = subprocess.run(COMPOSE + ['--profile', 'operator', 'run', '--rm', '-T', '-e', 'SPRING_PROFILES_ACTIVE=identity-ops', '--entrypoint', 'sh', 'operator', '-c', shell, 'operator'] + run_args,
                                    cwd=ROOT, env=ENV, input=(DATA / (name + '-password')).read_text(), text=True, capture_output=True)
            if result.returncode:
                (DATA / 'operator-failure.log').write_text(result.stdout + result.stderr, encoding='utf-8')
                raise RuntimeError('Identity seed failed; inspect protected local log')
        secret_record = DATA / 'fixture-credential-id'
        if not secret_record.exists():
            output = operator(['--chat-action=import-credential', '--scope=PLATFORM', '--key-file=/tmp/operator-input'], 'R12_FIXTURE_CREDENTIAL_SENTINEL')
            credential = re.search(r'Credential imported: ([0-9a-f-]{36})', output).group(1)
            secret_record.write_text(credential, encoding='utf-8')
        for name in ['alice', 'bob']:
            output = sql(f"SELECT u.id::text||','||s.id::text FROM pf.app_user u JOIN pf.space s ON s.owner_user_id=u.id WHERE u.username_norm='{name}' AND s.type='PERSONAL';")
            user, space = re.search(r'([0-9a-f-]{36}),([0-9a-f-]{36})', output).groups()
            operator(['--chat-action=bind-model', '--source-policy=PLATFORM', '--credential-id=' + secret_record.read_text().strip(), '--model-id=0199e000-0000-7000-8000-000000000002', '--user-id=' + user, '--space-id=' + space])
        print('Two isolated accounts and encrypted fixture credential bound; no real provider key used.')
    elif mode == 'start':
        config = json.loads(subprocess.check_output(COMPOSE + ['--profile', 'fixture', 'config', '--format', 'json'], cwd=ROOT, env=ENV))
        services = ['postgres', 'rabbitmq', 'redis', 'api', 'worker', 'gateway', 'fixture']
        assert sum(int(config['services'][name]['mem_limit']) for name in services) <= 2887 * 1024 * 1024
        run(COMPOSE + ['--profile', 'fixture', 'up', '-d', '--wait', '--wait-timeout', '180', *services])
    elif mode == 'reconcile-stopped':
        import uuid
        container = subprocess.check_output(COMPOSE + ['ps', '-a', '-q', 'worker'], cwd=ROOT, env=ENV, text=True).strip()
        if not container or '\n' in container:
            raise RuntimeError('Expected exactly one R1.2 worker container')
        owner = subprocess.check_output(COMPOSE + ['exec', '-T', 'worker', 'cat', '/tmp/chat-worker-ready'], cwd=ROOT, env=ENV, text=True).strip()
        owner = str(uuid.UUID(owner))
        before = json.loads(subprocess.check_output(['docker', 'inspect', container], cwd=ROOT))[0]
        assert before['Config']['Labels']['com.docker.compose.project'] == 'agentplatform-r12-local'
        run(COMPOSE + ['stop', '-t', '15', 'worker'])
        after = json.loads(subprocess.check_output(['docker', 'inspect', container], cwd=ROOT))[0]
        assert after['State']['Running'] is False and after['Image'] == before['Image']
        (DATA / 'stopped-owner.json').write_text(json.dumps({'owner': owner, 'container': container, 'image': after['Image'], 'running': False}), encoding='utf-8')
        deadline = time.monotonic() + 65
        while '(1 row)' in sql(f"SELECT 1 FROM pf.execution_slot WHERE owner_id='{owner}' AND lease_until>now();"):
            if time.monotonic() >= deadline:
                raise RuntimeError('Owner lease has not expired; worker remains stopped, no slot released')
            time.sleep(2)
        operator(['--chat-action=reconcile-stopped-owner', '--owner-id=' + owner, '--stopped-owner-confirmed'])
        print('Exact R1.2 worker stopped and expired owner reconciled; no automatic restart or call replay.')
    elif mode == 'stop':
        run(COMPOSE + ['--profile', 'fixture', 'stop'])
    elif mode == 'status':
        run(COMPOSE + ['ps'])


if __name__ == '__main__':
    main()
