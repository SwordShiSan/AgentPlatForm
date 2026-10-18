"""R2.1 isolated local environment; never builds or changes the cloud R1 stack."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
from execution_images import pin_exported

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r21-local'
spec = importlib.util.spec_from_file_location('chat_fixture', Path(__file__).with_name('chat-local.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
fixture.DATA = DATA
fixture.MQ_IDENTITY = DATA / 'mq-hostname'
fixture.ENV = dict(os.environ, R21_ROOT=DATA.as_posix(), R12_ROOT=DATA.as_posix(), R21_MQ_HOSTNAME='agentplatform-r21-mq',
                   R21_PROVIDER_BASE='http://fixture:8080', R21_FIXTURE_PROVIDER='true')
fixture.COMPOSE = ['docker', 'compose', '-p', 'agentplatform-r21-local', '-f', 'deploy/compose.execution-local.yaml']


def main():
    mode = sys.argv[1] if len(sys.argv) == 2 else ''
    assert mode in {'prepare', 'migrate', 'grant', 'seed', 'start', 'stop', 'status', 'reconcile-stopped'}, 'Explicit local mode required'
    pin_exported(fixture.ENV, DATA, required=mode == 'start')
    if mode == 'prepare':
        # No second project's ports/data are borrowed, even when that project is stopped.
        own = subprocess.check_output(fixture.COMPOSE + ['ps', '-a', '-q'], cwd=ROOT, env=fixture.ENV, text=True).strip()
        if not own:
            for port in (15443, 15683, 15693, 18088):
                with socket.socket() as check:
                    check.bind(('127.0.0.1', port))
        DATA.mkdir(parents=True, exist_ok=True)
        if not fixture.MQ_IDENTITY.exists():
            fixture.MQ_IDENTITY.write_text('agentplatform-r21-mq', encoding='utf-8')
    if mode == 'start':
        config = json.loads(subprocess.check_output(fixture.COMPOSE + ['--profile', 'fixture', 'config', '--format', 'json'], cwd=ROOT, env=fixture.ENV))
        services = ['postgres', 'rabbitmq', 'redis', 'api', 'worker', 'worker-2', 'gateway', 'fixture']
        total = sum(int(config['services'][s]['mem_limit']) for s in services)
        assert total == 2528*1024*1024 and total <= 2887*1024*1024
        fixture.run(fixture.COMPOSE + ['--profile', 'fixture', 'up', '-d', '--wait', '--wait-timeout', '180', *services])
    else:
        fixture.main()
    if mode == 'grant':
        fixture.sql('GRANT SELECT,INSERT,UPDATE,DELETE ON pf.conversation_lease TO ap_chat_worker; '
                    'GRANT SELECT,INSERT,UPDATE ON pf.model_turn,pf.tool_operation TO ap_chat_worker; '
                    'GRANT SELECT,INSERT ON pf.checkpoint TO ap_chat_worker; '
                    'REVOKE ALL ON pf.stopped_owner_proof FROM ap_chat_api,ap_chat_worker;')


if __name__ == '__main__':
    main()
