"""Fixed isolated CONNECT client for proxy policy acceptance."""
import os
import socket
import ssl

expected = os.environ['R62_EXPECT_CONNECT']
with socket.create_connection((os.environ['R62_PROXY_IP'], 3128), timeout=8) as connection:
    connection.settimeout(8)
    connection.sendall(b'CONNECT fixture.invalid:8443 HTTP/1.1\r\nHost: fixture.invalid:8443\r\n\r\n')
    header = b''
    while b'\r\n\r\n' not in header and len(header) < 16384:
        part = connection.recv(1)
        if not part: break
        header += part
    status = header.split(b'\r\n', 1)[0].split(b' ')[1].decode('ascii')
    if status != expected: raise RuntimeError('Unexpected CONNECT status ' + status)
    if status == '200':
        context = ssl.create_default_context(cafile='/fixture/fixture.crt')
        with context.wrap_socket(connection, server_hostname='fixture.invalid') as secured:
            secured.sendall(b'GET / HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n')
            if not secured.recv(1024).startswith(b'HTTP/1.1 405 '): raise RuntimeError('Unexpected fixture response')
print('CONNECT_' + status)
