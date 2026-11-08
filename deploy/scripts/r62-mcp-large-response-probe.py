"""Bounded SDK large-response probe with a local TLS fixture and loopback proxy only."""
from pathlib import Path
import hashlib
import http.server
import json
import os
import secrets
import select
import socket
import socketserver
import ssl
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r62-local/mcp-large-response' / ('run-' + str(time.time_ns()))
JAR = ROOT / 'backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar'
JDK = ROOT / '.tools/jdk-21.0.12.1/jdk-21.0.12.1+1/bin'
OPENSSL = Path('C:/Program Files/Git/mingw64/bin/openssl.exe')

class Fixture(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    calls = []
    sent = 0
    def log_message(self, *_): pass
    def do_GET(self):
        self.send_response(405); self.send_header('Content-Length', '0'); self.end_headers()
    def do_DELETE(self):
        self.send_response(200); self.send_header('Content-Length', '0'); self.end_headers()
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        method = request.get('method'); Fixture.calls.append(method)
        if 'id' not in request:
            self.send_response(202); self.send_header('Content-Length', '0'); self.end_headers(); return
        if method == 'initialize':
            result = {'protocolVersion': request.get('params', {}).get('protocolVersion', '2025-06-18'),
                      'capabilities': {'tools': {'listChanged': False}}, 'serverInfo': {'name': 'fixture', 'version': '1'}}
            body = json.dumps({'jsonrpc': '2.0', 'id': request['id'], 'result': result}).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body); return
        if method == 'tools/list':
            prefix = json.dumps({'jsonrpc': '2.0', 'id': request['id']})[:-1].encode() + b',"result":{"tools":[{"name":"giant","description":"'
            suffix = b'","inputSchema":{"type":"object"}}]}}'
            count = 96 * 1024 * 1024
            self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(prefix) + count + len(suffix))); self.end_headers()
            try:
                self.wfile.write(prefix); chunk = b'a' * (1024 * 1024)
                for _ in range(96): self.wfile.write(chunk); Fixture.sent += len(chunk)
                self.wfile.write(suffix); self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError): pass
            return
        self.send_response(400); self.send_header('Content-Length', '0'); self.end_headers()

class Proxy(socketserver.BaseRequestHandler):
    target = 0
    connects = 0
    def handle(self):
        self.request.settimeout(10); header = b''
        while b'\r\n\r\n' not in header and len(header) < 8192:
            part = self.request.recv(4096)
            if not part: return
            header += part
        first, _, remaining = header.partition(b'\r\n\r\n')
        if not first.startswith(b'CONNECT fixture.invalid:443 '): return
        Proxy.connects += 1
        with socket.create_connection(('127.0.0.1', Proxy.target), timeout=5) as upstream:
            upstream.settimeout(None); self.request.settimeout(None)
            self.request.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
            if remaining: upstream.sendall(remaining)
            try:
                while True:
                    readable, _, _ = select.select([self.request, upstream], [], [], 15)
                    if not readable: return
                    for source in readable:
                        data = source.recv(65536)
                        if not data: return
                        (upstream if source is self.request else self.request).sendall(data)
            except (OSError, ValueError): return

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

class TlsServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    def handle_error(self, *_): pass

def main():
    DATA.mkdir(parents=True)
    assert JAR.is_file() and OPENSSL.is_file()
    with tempfile.TemporaryDirectory(dir=DATA) as directory:
        root = Path(directory); key = root / 'fixture.key'; cert = root / 'fixture.crt'; trust = root / 'trust.jks'
        subprocess.run([str(OPENSSL), 'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', '1', '-nodes',
                        '-keyout', str(key), '-out', str(cert), '-subj', '/CN=fixture.invalid', '-addext', 'subjectAltName=DNS:fixture.invalid'],
                       check=True, capture_output=True, timeout=30)
        environment = dict(os.environ, R62_FIXTURE_STORE_PASSWORD=secrets.token_urlsafe(20))
        subprocess.run([str(JDK / 'keytool.exe'), '-importcert', '-noprompt', '-alias', 'fixture', '-file', str(cert),
                        '-keystore', str(trust), '-storetype', 'JKS', '-storepass:env', 'R62_FIXTURE_STORE_PASSWORD'],
                       env=environment, check=True, capture_output=True, timeout=30)
        tls = TlsServer(('127.0.0.1', 0), Fixture)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(cert, key); tls.socket = context.wrap_socket(tls.socket, server_side=True)
        Proxy.target = tls.server_address[1]
        proxy = Server(('127.0.0.1', 0), Proxy)
        threads = [threading.Thread(target=service.serve_forever, daemon=True) for service in (tls, proxy)]
        for thread in threads: thread.start()
        request = {'endpoint': 'https://fixture.invalid/mcp', 'authKind': 'BEARER', 'header': None,
                   'secret': 'deterministic-fixture-only', 'proxyHost': '127.0.0.1', 'proxyPort': proxy.server_address[1]}
        command = [str(JDK / 'java.exe'), '-Xms16m', '-Xmx64m', '-XX:MaxMetaspaceSize=96m', '-XX:MaxDirectMemorySize=16m',
                   '-XX:ActiveProcessorCount=1', '-XX:+ExitOnOutOfMemoryError', '-Djavax.net.ssl.trustStore=' + str(trust),
                   '-Dloader.main=com.agentplatform.infrastructure.capability.McpDiscoveryChild', '-cp', str(JAR),
                   'org.springframework.boot.loader.launch.PropertiesLauncher']
        try:
            result = subprocess.run(command, input=json.dumps(request), text=True, capture_output=True, timeout=90)
            try: child_status = json.loads(result.stdout).get('status')
            except (ValueError, AttributeError): child_status = None
            (DATA / 'diagnostic.json').write_text(json.dumps({'childExit': result.returncode, 'childStatus': child_status,
                'methods': Fixture.calls, 'sentBytes': Fixture.sent, 'proxyConnections': Proxy.connects}, indent=2) + '\n', encoding='utf-8')
            assert 'initialize' in Fixture.calls and 'tools/list' in Fixture.calls, 'Fixture did not reach SDK tool discovery'
            assert Fixture.sent > 1024 * 1024, 'Fixture did not send beyond the configured schema limit'
            assert result.returncode != 0 or child_status == 503, 'Oversized response unexpectedly produced a successful discovery'
            assert 'deterministic-fixture-only' not in result.stdout and 'deterministic-fixture-only' not in result.stderr
            receipt = {'status': 'PASS', 'scope': 'actual SDK oversized TLS response fails within bounded test JVM',
                       'jarSha256': hashlib.sha256(JAR.read_bytes()).hexdigest(), 'childExit': result.returncode,
                       'childStatus': child_status, 'testHeapMiB': 64, 'offeredResponseMiB': 96,
                       'sentBytes': Fixture.sent, 'proxyConnections': Proxy.connects,
                       'notRun': ['production worker cgroup budget', 'business tool calls', 'actual Squid ACL']}
            (DATA / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
            print(json.dumps(receipt))
        finally:
            proxy.shutdown(); tls.shutdown(); proxy.server_close(); tls.server_close()
            for thread in threads: thread.join(timeout=3)

if __name__ == '__main__': main()
