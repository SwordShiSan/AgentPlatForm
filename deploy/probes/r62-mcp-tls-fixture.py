"""Small deterministic MCP HTTP fixture; never used as a production connector."""
import http.server
import json
import ssl

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_): pass
    def do_GET(self):
        self.send_response(405); self.send_header('Content-Length', '0'); self.end_headers()
    def do_DELETE(self):
        self.send_response(200); self.send_header('Content-Length', '0'); self.end_headers()
    def do_POST(self):
        if self.headers.get('Authorization') != 'Bearer deterministic-fixture-only':
            self.send_response(401); self.send_header('Content-Length', '0'); self.end_headers(); return
        request = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        if 'id' not in request:
            self.send_response(202); self.send_header('Content-Length', '0'); self.end_headers(); return
        method = request.get('method')
        if method == 'initialize':
            value = {'protocolVersion': request.get('params', {}).get('protocolVersion', '2025-06-18'),
                     'capabilities': {'tools': {'listChanged': False}}, 'serverInfo': {'name': 'fixture', 'version': '1'}}
        elif method == 'tools/list':
            value = {'tools': [{'name': 'fixture_echo', 'description': 'deterministic fixture', 'inputSchema': {'type': 'object', 'properties': {}}}]}
        else:
            self.send_response(400); self.send_header('Content-Length', '0'); self.end_headers(); return
        body = json.dumps({'jsonrpc': '2.0', 'id': request['id'], 'result': value}).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body)))
        self.end_headers(); self.wfile.write(body)

server = http.server.ThreadingHTTPServer(('0.0.0.0', 8443), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain('/fixture/fixture.crt', '/fixture/fixture.key')
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
