"""Explicit metered real-provider window. Never prints or persists plaintext keys."""
import argparse
import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('chat_local', ROOT / 'deploy/scripts/chat-local.py')
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['prepare', 'restore'])
    parser.add_argument('--private-key-file', type=Path)
    parser.add_argument('--username', default='alice')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z][a-z0-9_]{2,31}', args.username):
        raise ValueError('Invalid local acceptance username')
    assert '(0 rows)' in local.sql("SELECT 1 FROM pf.task WHERE status IN ('QUEUED','RUNNING');"), 'Finish pending work before switching the worker'
    local.run(local.COMPOSE + ['stop', '-t', '20', 'worker'], capture_output=True)
    actor = local.sql("SELECT u.id::text||','||s.id::text FROM pf.app_user u JOIN pf.space s ON s.owner_user_id=u.id WHERE u.username_norm='" + args.username + "' AND s.type='PERSONAL';")
    user, space = re.search(r'([0-9a-f-]{36}),([0-9a-f-]{36})', actor).groups()
    record = local.DATA / 'real-credential-id'
    if args.mode == 'prepare':
        if not record.exists():
            if args.private_key_file is None or not args.private_key_file.is_file():
                raise ValueError('Explicit private key file required for first import')
            if args.private_key_file.stat().st_size > 8192:
                raise ValueError('Unexpected private file size')
            value = args.private_key_file.read_text(encoding='utf-8-sig').strip()
            if not value or any(c.isspace() for c in value):
                raise ValueError('Invalid private credential format')
            output = local.operator(['--chat-action=import-credential', '--scope=PLATFORM', '--key-file=/tmp/operator-input'], value)
            value = None
            record.write_text(re.search(r'Credential imported: ([0-9a-f-]{36})', output).group(1), encoding='utf-8')
        credential = record.read_text().strip()
        local.ENV.update(R12_PROVIDER_BASE='https://api.deepseek.com', R12_FIXTURE_PROVIDER='false')
    else:
        credential = (local.DATA / 'fixture-credential-id').read_text().strip()
        local.ENV.update(R12_PROVIDER_BASE='http://fixture:8080', R12_FIXTURE_PROVIDER='true')
    for model in ['0199e000-0000-7000-8000-000000000002', '0199e000-0000-7000-8000-000000000003']:
        local.operator(['--chat-action=bind-model', '--source-policy=PLATFORM', '--credential-id=' + credential,
                        '--model-id=' + model, '--user-id=' + user, '--space-id=' + space])
    local.run(local.COMPOSE + ['up', '-d', '--wait', '--wait-timeout', '120', 'worker'], capture_output=True)
    print('Real provider prepared; historical meter retained, provider quota and business budget apply; no calls made by this command.' if args.mode == 'prepare'
          else 'Deterministic provider and fixture binding restored; encrypted real credential remains protected in database.')


if __name__ == '__main__':
    main()
