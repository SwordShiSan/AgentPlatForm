"""R2.1 isolated local environment; never builds or changes the cloud R1 stack."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
from execution_images import pin_exported

from execution_fixture import ROOT, DATA, ENV, COMPOSE, PORTS, STAGE

spec = importlib.util.spec_from_file_location('chat_fixture', Path(__file__).with_name('chat-local.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
fixture.DATA = DATA
fixture.MQ_IDENTITY = DATA / 'mq-hostname'
fixture.ENV = ENV
fixture.COMPOSE = COMPOSE


def main():
    mode = sys.argv[1] if len(sys.argv) == 2 else ''
    assert mode in {'prepare', 'migrate', 'grant', 'seed', 'start', 'stop', 'status', 'reconcile-stopped'}, 'Explicit local mode required'
    pin_exported(fixture.ENV, DATA, required=mode == 'start')
    if mode == 'prepare':
        # No second project's ports/data are borrowed, even when that project is stopped.
        own = subprocess.check_output(fixture.COMPOSE + ['ps', '-a', '-q'], cwd=ROOT, env=fixture.ENV, text=True).strip()
        if not own:
            for port in PORTS:
                with socket.socket() as check:
                    check.bind(('127.0.0.1', port))
        DATA.mkdir(parents=True, exist_ok=True)
        if not fixture.MQ_IDENTITY.exists():
            fixture.MQ_IDENTITY.write_text('agentplatform-' + STAGE + '-mq', encoding='utf-8')
    if mode == 'start':
        config = json.loads(subprocess.check_output(fixture.COMPOSE + ['--profile', 'fixture', 'config', '--format', 'json'], cwd=ROOT, env=fixture.ENV))
        services = ['postgres', 'rabbitmq', 'redis', 'api', 'worker', 'worker-2', 'gateway', 'fixture']
        total = sum(int(config['services'][s]['mem_limit']) for s in services)
        assert all(int(config['services'][s]['mem_limit']) > 0 for s in services)
        assert 0 < total <= 2887*1024*1024, 'Module limits may change; the combined maximum must remain bounded'
        fixture.run(fixture.COMPOSE + ['--profile', 'fixture', 'up', '-d', '--wait', '--wait-timeout', '180', *services])
    elif mode == 'stop':
        fixture.run(fixture.COMPOSE + ['--profile', 'fixture', 'stop'])
    else:
        fixture.main()
    if mode == 'grant':
        fixture.sql('GRANT SELECT,INSERT,UPDATE,DELETE ON pf.conversation_lease TO ap_chat_worker; '
                    'GRANT SELECT,INSERT,UPDATE ON pf.model_turn,pf.tool_operation TO ap_chat_worker; '
                    'GRANT SELECT,INSERT ON pf.checkpoint TO ap_chat_worker; '
                    'REVOKE ALL ON pf.stopped_owner_proof FROM ap_chat_api,ap_chat_worker;')
        if STAGE in {'r22','r23'}:
            fixture.sql('REVOKE DELETE ON pf.platform_event FROM ap_chat_api,ap_chat_worker; '
                        'REVOKE UPDATE ON pf.conversation FROM ap_chat_api,ap_chat_worker; '
                        'GRANT UPDATE(title,next_seq,version,updated_at) ON pf.conversation TO ap_chat_api,ap_chat_worker; '
                        'REVOKE ALL ON FUNCTION pf.collect_expired_replay(uuid) FROM ap_chat_worker; '
                        'GRANT EXECUTE ON FUNCTION pf.collect_expired_replay(uuid) TO ap_chat_api;')
        if STAGE == 'r23':
            fixture.sql('GRANT SELECT,INSERT ON pf.task_cancellation TO ap_chat_api; '
                        'GRANT SELECT ON pf.task_cancellation TO ap_chat_worker; '
                        'GRANT SELECT ON pf.execution_stop_receipt TO ap_chat_api; '
                        'GRANT SELECT,INSERT ON pf.execution_stop_receipt TO ap_chat_worker; '
                        'REVOKE ALL ON pf.model_call_reconciliation FROM ap_chat_api,ap_chat_worker; '
                        'REVOKE INSERT,UPDATE,DELETE ON pf.execution_stop_receipt FROM ap_chat_api;')


if __name__ == '__main__':
    main()
