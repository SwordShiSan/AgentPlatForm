"""Real local API/MQ/PG smoke with deterministic provider only; prints assertions, never credentials."""
import http.cookiejar
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r12-local'
BASE = 'http://127.0.0.1:18087'


class Client:
    def __init__(self, name):
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = self.request('GET', '/auth/csrf')[1]['csrfToken']
        result = self.request('POST', '/auth/login', {'username': name, 'password': (DATA / (name + '-password')).read_text().strip()})
        assert result[0] == 200, 'Login failed'
        self.csrf = result[1]['csrfToken']

    def request(self, method, path, body=None, key=None, extra=None):
        headers = {'Origin': BASE, 'Content-Type': 'application/json', 'X-CSRF-Token': self.csrf} if method != 'GET' else {}
        if key:
            headers['Idempotency-Key'] = key
        headers.update(extra or {})
        request = urllib.request.Request(BASE + '/api/v1' + path, method=method, headers=headers,
                                         data=json.dumps(body).encode() if body is not None else None)
        try:
            with self.http.open(request, timeout=35) as response:
                return response.status, json.load(response) if response.status != 204 else None
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def conversation(self):
        code, value = self.request('POST', '/conversations', {'title': 'Local smoke'}, str(uuid.uuid4()))
        assert code == 201, 'Conversation creation failed'
        return value['id']

    def submit(self, conversation, text, key=None):
        code, models = self.request('GET', '/models')
        assert code == 200 and any(m['name'] == 'deepseek-flash' for m in models['items']), 'Catalog missing bound model'
        value = {'input': {'text': text, 'attachments': []}, 'options': {'modelId': models['items'][0]['id'], 'planMode': False, 'permissionMode': 'RISK_BASED'}}
        code, result = self.request('POST', '/conversations/' + conversation + '/tasks', value, key or str(uuid.uuid4()))
        assert code == 202, 'Task submission failed: ' + result.get('code', 'unknown')
        return result

    def terminal(self, task, timeout=45):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            code, value = self.request('GET', '/tasks/' + task)
            assert code == 200
            if value['status'] not in ['QUEUED', 'RUNNING']:
                return value
            time.sleep(0.2)
        raise AssertionError('Task did not finish within fixture deadline')


def main():
    alice, bob = Client('alice'), Client('bob')
    conversation = alice.conversation()
    key = str(uuid.uuid4())
    first = alice.submit(conversation, 'fixture:normal', key)
    same = alice.submit(conversation, 'fixture:normal', key)
    assert first['taskId'] == same['taskId'], 'Idempotency created another task'
    terminal = alice.terminal(first['taskId'])
    assert terminal['status'] == 'SUCCEEDED', 'Fixture normal failed: ' + str(terminal.get('reasonCode'))
    code, snapshot = alice.request('GET', '/conversations/' + conversation + '/snapshot')
    assert code == 200
    text = ''.join(block['text'] for message in snapshot['messages'] if message['role'] == 'ASSISTANT' for block in message['blocks'])
    assert text == '你好😀，这是流式回复。' and 'HIDDEN' not in text
    code, usage = alice.request('GET', '/tasks/' + first['taskId'] + '/usage')
    assert code == 200 and len(usage['items']) == 1 and usage['items'][0]['inputTokens'] == 100
    for path in ['/tasks/' + first['taskId'], '/tasks/' + first['taskId'] + '/usage', '/conversations/' + conversation, '/conversations/' + conversation + '/snapshot']:
        assert bob.request('GET', path)[0] == 404, 'Foreign data exposed'
    assert bob.request('POST', '/tasks/' + first['taskId'] + '/cancel', {}, str(uuid.uuid4()))[0] == 404
    assert alice.request('POST', '/conversations', {'title': 'Rejected'}, str(uuid.uuid4()), {'X-CSRF-Token': 'invalid'})[0] == 403
    _, models = alice.request('GET', '/models')
    body = {'input': {'text': 'Must not execute', 'attachments': []}, 'options': {'modelId': models['items'][0]['id'], 'planMode': False, 'permissionMode': 'RISK_BASED'}}
    writes = [('/conversations', {'title': 'Rejected'}), ('/conversations/' + conversation + '/tasks', body), ('/tasks/' + first['taskId'] + '/cancel', {})]
    for path, payload in writes:
        assert alice.request('POST', path, payload, str(uuid.uuid4()), {'Origin': 'https://foreign.invalid'})[0] == 403
        assert alice.request('POST', path, payload, str(uuid.uuid4()), {'X-CSRF-Token': 'invalid'})[0] == 403
        assert alice.request('POST', path, payload)[0] == 428
    _, other = bob.request('GET', '/me')
    foreign_space = other['spaces'][0]['id']
    assert alice.request('GET', '/conversations', extra={'X-Space-Id': foreign_space})[0] in [403, 404]
    assert alice.request('POST', '/conversations', {'title': 'Rejected', 'userId': other['id']}, str(uuid.uuid4()))[0] == 400
    _, own_list = alice.request('GET', '/conversations', extra={'X-User-Id': other['id']})
    assert any(item['id'] == conversation for item in own_list['items']), 'Forged identity header changed owner'
    assert alice.request('GET', '/metrics')[0] == 404
    history = alice.submit(conversation, 'fixture:history')
    assert alice.terminal(history['taskId'])['status'] == 'SUCCEEDED'
    _, snapshot = alice.request('GET', '/conversations/' + conversation + '/snapshot')
    assert any('history-messages=4' in block['text'] for message in snapshot['messages'] for block in message['blocks']), 'Required history missing'
    print('PASS local real API/PG/MQ: queued streaming projection, idempotency, usage, multi-turn history, two-user reads/cancel, CSRF and metrics boundary. Deterministic provider only.')


if __name__ == '__main__':
    main()
