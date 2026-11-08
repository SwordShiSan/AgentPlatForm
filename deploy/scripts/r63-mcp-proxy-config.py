"""Render a candidate Squid policy for one operator-selected internal worker address.

The generated file is not deployed automatically. The isolated acceptance profile
must also enforce that only the worker and proxy share the control network.
"""
import argparse
import ipaddress
import os
from pathlib import Path

PRIVATE = tuple(ipaddress.ip_network(value) for value in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
FORBIDDEN = ('0.0.0.0/8', '10.0.0.0/8', '100.64.0.0/10', '127.0.0.0/8', '169.254.0.0/16',
             '172.16.0.0/12', '192.0.0.0/24', '192.0.2.0/24', '192.88.99.0/24', '192.168.0.0/16',
             '198.18.0.0/15', '198.51.100.0/24', '203.0.113.0/24', '224.0.0.0/4', '240.0.0.0/4',
             '::/96', '64:ff9b::/96', '64:ff9b:1::/48', '100::/64', '100:0:0:1::/64', '2001::/23',
             '2001:db8::/32', '2002::/16', '3fff::/20', 'fc00::/7', 'fe80::/10', 'ff00::/8')

def render(worker_ip):
    address = ipaddress.ip_address(worker_ip)
    if address.version != 4 or not any(address in network for network in PRIVATE):
        raise ValueError('A single private IPv4 worker address is required')
    return '\n'.join(['visible_hostname agentplatform-mcp-egress', 'http_port 3128', 'max_filedescriptors 1024',
        'acl worker src ' + str(address) + '/32', 'acl connect_method method CONNECT', 'acl tls_port port 1-65535',
        'acl forbidden_dest dst ' + ' '.join(FORBIDDEN), 'http_access deny !connect_method',
        'http_access deny !tls_port', 'http_access deny forbidden_dest', 'http_access allow worker', 'http_access deny all',
        'cache deny all', 'cache_mem 8 MB', 'memory_pools off', 'access_log none', 'cache_log /dev/stderr',
        'pid_filename /tmp/squid.pid', 'coredump_dir /tmp', ''])

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--worker-ip', required=True); parser.add_argument('--output', required=True)
    args = parser.parse_args(); content = render(args.worker_ip); output = Path(args.output)
    if output.exists(): raise FileExistsError('Refusing to replace an existing proxy configuration')
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream: stream.write(content)

if __name__ == '__main__': main()
