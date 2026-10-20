"""Deterministic provider for local acceptance. Never use a real credential here."""
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time

COUNTS = Counter()
LOCK = threading.Lock()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON field')
        result[key] = value
    return result


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path != '/status':
            self.send_error(404)
            return
        with LOCK:
            body = json.dumps({'physical_calls': sum(COUNTS.values()), 'scenarios': dict(COUNTS)}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get('Content-Length', '0'))
        if self.path != '/chat/completions' or not 0 < length <= 98304:
            self.send_error(400)
            return
        try:
            body = json.loads(self.rfile.read(length), object_pairs_hook=unique_object)
        except (ValueError, UnicodeError):
            self.send_error(422)
            return
        if body.get('model') not in ['deepseek-flash', 'deepseek-v4-pro'] or body.get('thinking') != {'type': 'disabled'} or not body.get('stream'):
            self.send_error(422)
            return
        if body.get('max_tokens') != 1024 or not body.get('stream_options', {}).get('include_usage'):
            self.send_error(422)
            return
        messages = body.get('messages', [])
        prompt = messages[-1].get('content', '') if messages else ''
        if not isinstance(prompt, str):
            prompt = json.dumps(prompt)
        with LOCK:
            COUNTS[prompt] += 1
            ordinal = COUNTS[prompt]
        if prompt.startswith('fixture:balance'):
            self.send_response(402)
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if prompt.startswith(('fixture:429', 'fixture:5xx')) and ordinal <= 2:
            self.send_response(429 if prompt.startswith('fixture:429') else 503)
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if prompt.startswith('fixture:timeout'):
            time.sleep(125)
            self.close_connection = True
            return
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()

        def chunk(delta=None, finish=None, usage=None):
            value = {'id': 'fixture-' + str(ordinal), 'choices': [] if delta is None else [{'index': 0, 'delta': delta, 'finish_reason': finish}]}
            if usage is not None:
                value['usage'] = usage
            self.wfile.write(('data: ' + json.dumps(value, ensure_ascii=False) + '\n\n').encode())
            self.wfile.flush()

        try:
            if prompt.startswith('fixture:recovery-tool') and not any(message.get('role') == 'tool' for message in messages):
                chunk({'tool_calls': [{'index': 0, 'id': 'recovery-operation-1', 'type': 'function',
                       'function': {'name': 'recovery_probe', 'arguments': '{"text":"one"}'}}]}, 'tool_calls')
            elif prompt.startswith('fixture:empty'):
                chunk({}, 'stop')
            else:
                chunks = ['你好', '😀', '，这是流式回复。']
                if prompt.startswith('fixture:slow'):
                    chunks = ['慢速'] * 50
                if prompt.startswith('fixture:history'):
                    chunks = ['history-messages=' + str(len(messages))]
                for index, text in enumerate(chunks):
                    chunk({'content': text, 'reasoning_content': 'FIXTURE_HIDDEN_REASONING'})
                    if prompt.startswith('fixture:partial') and index == 0:
                        self.close_connection = True
                        return
                    time.sleep(0.5 if prompt.startswith('fixture:slow') else 0.15)
                chunk({}, 'length' if prompt.startswith('fixture:truncated') else 'stop')
            if not prompt.startswith('fixture:missing-usage'):
                chunk(usage={'prompt_tokens': 100, 'completion_tokens': 20, 'total_tokens': 120})
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == '__main__':
    ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
