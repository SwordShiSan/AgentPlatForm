"""Deterministic DNS fixture for an isolated proxy test, never a platform service."""
import os
import socket
import struct

ADDRESS = socket.inet_aton(os.environ['R62_PUBLIC_FIXTURE_IP'])
server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
server.bind(('0.0.0.0', 53))
while True:
    data, peer = server.recvfrom(4096)
    try:
        offset = 12
        while data[offset]:
            offset += data[offset] + 1
        offset += 1
        qtype, qclass = struct.unpack('!HH', data[offset:offset + 4])
        question = data[12:offset + 4]
        answer = b''
        if qtype == 1 and qclass == 1:
            changed = os.path.exists('/fixture/rebind')
            value = socket.inet_aton('127.0.0.1') if changed else ADDRESS
            print('DNS_A_LOOPBACK' if changed else 'DNS_A_PUBLIC_FIXTURE', flush=True)
            answer = b'\xc0\x0c' + struct.pack('!HHIH', 1, 1, 1, 4) + value
        header = data[:2] + struct.pack('!HHHHH', 0x8180, 1, int(bool(answer)), 0, 0)
        server.sendto(header + question + answer, peer)
    except (IndexError, struct.error):
        continue
