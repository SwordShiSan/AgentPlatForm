"""Real R1.1 HTTP/restart/dependency acceptance. Output contains no credentials/tokens."""
import http.cookiejar
import importlib.util
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

spec = importlib.util.spec_from_file_location('fixture', Path(__file__).with_name('identity-local.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
BASE = 'http://127.0.0.1:18086'
results = []


class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None

    def request(self, path, body=None, headers=None, expected=200):
        values = {'Origin': BASE}
        if self.csrf:
            values['X-CSRF-Token'] = self.csrf
        values.update(headers or {})
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            values['Content-Type'] = 'application/json'
        try:
            response = self.http.open(urllib.request.Request(BASE + path, data=data, headers=values), timeout=12)
        except urllib.error.HTTPError as error:
            response = error
        assert response.status == expected, (path, response.status, expected)
        raw = response.read()
        result = json.loads(raw) if raw else None
        if expected >= 400:
            assert response.headers['Content-Type'].startswith('application/problem+json')
            assert result['code'] and result['traceId']
            assert not any(s in raw.decode().lower() for s in ['password_hash', 'jdbc:', '/srv/', 'select '])
        return result, response.headers

    def login(self, name):
        result, headers = self.request('/api/v1/auth/csrf')
        self.csrf = result['csrfToken']
        assert 'HttpOnly' in headers['Set-Cookie'] and 'SameSite=Lax' in headers['Set-Cookie']
        before = [c.value for c in self.cookies]
        result, _ = self.request('/api/v1/auth/login', {'username': name, 'password': (fixture.DATA / (name + '-password')).read_text().strip()})
        self.csrf = result['csrfToken']
        assert before != [c.value for c in self.cookies]


def ready():
    for _ in range(90):
        try:
            if urllib.request.urlopen(BASE + '/api/v1/readiness', timeout=3).status == 200:
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise RuntimeError('Fixture readiness did not recover')


def main():
    fixture.sql('TRUNCATE pf.auth_rate;')
    a, b = Client(), Client()
    a.login('alice'); b.login('bob')
    me, _ = a.request('/api/v1/me')
    other, _ = b.request('/api/v1/me')
    assert me['id'] != other['id'] and me['platformAdmin'] and not other['platformAdmin']
    a.request('/api/v1/me', headers={'X-Space-Id': other['spaces'][0]['id']}, expected=404)
    b.request('/api/v1/me', headers={'X-Space-Id': me['spaces'][0]['id']}, expected=404)
    a.request('/api/v1/auth/logout', {}, {'Origin': 'http://foreign.example'}, 403)
    a.request('/api/v1/auth/logout', {}, {'X-CSRF-Token': 'invalid'}, 403)
    a.request('/api/v1/auth/login', {'username': 'alice', 'password': 'not-a-real-password', 'userId': other['id']}, expected=400)
    assert len(a.request('/api/v1/me')[0]['spaces']) == 1
    results.append({'case': 'real-http-contract-csrf-rotation-admin-isolation', 'status': 'PASS'})
    fixture.run(fixture.COMPOSE + ['restart', 'api'], capture_output=True)
    ready()
    assert a.request('/api/v1/me')[0]['id'] == me['id']
    results.append({'case': 'container-restart-session-persistence', 'status': 'PASS'})
    try:
        fixture.run(fixture.COMPOSE + ['stop', 'postgres'], capture_output=True)
        a.request('/api/v1/me', expected=503)
        a.request('/api/v1/health')
        # readiness intentionally uses its existing JSON contract.
        try:
            urllib.request.urlopen(BASE + '/api/v1/readiness', timeout=10)
            raise AssertionError('Readiness unexpectedly succeeded')
        except urllib.error.HTTPError as failure:
            assert failure.code == 503
    finally:
        fixture.run(fixture.COMPOSE + ['up', '-d', '--wait', 'postgres'], capture_output=True)
        ready()
    assert a.request('/api/v1/me')[0]['id'] == me['id']
    results.append({'case': 'real-postgres-outage-fail-closed-and-recovery', 'status': 'PASS'})
    fixture.operator('reset-password', 'alice', password=(fixture.DATA / 'alice-password').read_text().strip())
    a.request('/api/v1/me', expected=401)
    a.login('alice')
    a.request('/api/v1/auth/logout', {}, expected=204)
    a.request('/api/v1/me', expected=401)
    results.append({'case': 'operator-reset-and-logout-revocation', 'status': 'PASS'})
    # The operator reset is serial with the API. Sampled container memory is not an absolute peak.
    stats = fixture.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', 'agentplatform-r11-local-api-1'], capture_output=True, text=True).stdout
    (fixture.DATA / 'http-results.json').write_text(json.dumps({'results': results, 'sampled_stats': json.loads(stats)}, indent=2), encoding='utf-8')
    fixture.sql('TRUNCATE pf.auth_rate;')
    print(json.dumps(results))


if __name__ == '__main__':
    main()
