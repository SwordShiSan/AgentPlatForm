"""Real HTTP regression against the explicitly isolated R2.C fixture only."""
import importlib.util
import json
import os
from pathlib import Path
import sys

assert os.environ.get('AGENTPLATFORM_ACCEPTANCE_STAGE') == 'r2c'
from execution_fixture import BASE, COMPOSE, DATA, ENV, PROJECT, ROOT
from execution_images import pin_exported

pin_exported(ENV, DATA, required=True)

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

identity = load('framework_identity_fixture', 'identity-local.py')
identity.DATA, identity.ENV, identity.COMPOSE = DATA, ENV, COMPOSE
http = load('framework_identity_http', 'test-identity-http.py')
http.fixture, http.BASE = identity, BASE

# Reuse the existing assertions; replace only the fixed stats target with the
# same verified Compose project, never another fixture's container or database.
original_run = identity.run
def run(command, **kwargs):
    if command[:2] == ['docker', 'stats']:
        assert command[-1] == 'agentplatform-r11-local-api-1'
        command = [*command[:-1], PROJECT + '-api-1']
    return original_run(command, **kwargs)
identity.run = run

def sql(query):
    return run(COMPOSE + ['exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'chat'],
               input=query, text=True, capture_output=True).stdout
identity.sql = sql

def operator(action, name, request=None, password=None, expect_success=True):
    args = ['--identity-action=' + action, '--username=' + name]
    if request:
        args.append('--request-id=' + request)
    assert password is not None, 'This regression only uses explicit password resets'
    args.append('--password-file=/tmp/operator-password')
    shell = 'umask 077; cat > /tmp/operator-password; java -Xms16m -Xmx160m -jar /app/platform.jar "$@"; result=$?; rm -f /tmp/operator-password; exit "$result"'
    result = run(COMPOSE + ['--profile', 'operator', 'run', '--rm', '-T', '-e', 'SPRING_PROFILES_ACTIVE=identity-ops',
                           '--entrypoint', 'sh', 'operator', '-c', shell, 'operator', *args],
                 input=password, text=True, capture_output=True)
    assert result.returncode == 0 and expect_success
    return result.returncode
identity.operator = operator

if __name__ == '__main__':
    http.main()
    client = http.Client()
    client.login('alice')
    cases = [
        ('/api/v1/conversations', {'title': 42}, 400, 'INVALID_TITLE'),
        ('/api/v1/conversations', {'title': 'validation', 'initialTask': None}, 400, 'INVALID_TASK_REQUEST'),
        ('/api/v1/conversations', {'title': 'validation', 'extra': 'rejected'}, 400, 'UNKNOWN_REQUEST_FIELD'),
        ('/api/v1/auth/login', {'username': True, 'password': 'fixture-invalid'}, 400, 'INVALID_LOGIN_REQUEST'),
    ]
    for path, body, status, code in cases:
        result, _ = client.request(path, body, expected=status)
        assert result['code'] == code, (path, result['code'], code)
    for path in ['/api/v1/actuator', '/api/v1/actuator/metrics', '/api/v1/internal/dependencies']:
        client.request(path, expected=404)
    # Browser routes may legitimately use the SPA fallback. This must be HTML,
    # never an actuator JSON response or the internal dependency projection.
    for path in ['/actuator', '/actuator/metrics']:
        import urllib.request, urllib.error
        try:
            with urllib.request.urlopen(BASE + path, timeout=10) as response:
                assert response.status == 200 and response.headers['Content-Type'].startswith('text/html')
                assert '<html' in response.read().decode().lower()
        except urllib.error.HTTPError as error:
            assert error.code == 404
    (DATA / 'acceptance/http.json').write_text(json.dumps({
        'status': 'PASS', 'identityCases': http.results,
        'strictDtoCases': len(cases), 'internalEndpointCases': 5,
        'realProviderCalls': 0,
    }, indent=2), encoding='utf-8')
    print('Real HTTP identity, restart, dependency, strict DTO and internal boundary PASS')
