"""Bounded local Squid feasibility probe; not a production configuration or acceptance."""
import json
import ipaddress
import shutil
import subprocess
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IMAGE = 'ubuntu/squid@sha256:031d51a433928cf7476611f6138af85681ce4eec8568d1d9a4dcd28d16289c78'
CIDRS = ('0.0.0.0/8', '10.0.0.0/8', '100.64.0.0/10', '127.0.0.0/8', '169.254.0.0/16',
         '172.16.0.0/12', '192.0.0.0/24', '192.0.2.0/24', '192.168.0.0/16', '198.18.0.0/15',
         '198.51.100.0/24', '203.0.113.0/24', '224.0.0.0/4', '240.0.0.0/4',
         '::/128', '::1/128', 'fc00::/7', 'fe80::/10', 'ff00::/8', '2001:db8::/32')


def call(*args, check=True):
    return subprocess.run(args, check=check, text=True, capture_output=True, timeout=20)


def main():
    if not shutil.which('docker') or not shutil.which('curl.exe'):
        raise RuntimeError('Docker and curl.exe are required')
    base = ROOT / '.cache' / 'r62-egress'
    base.mkdir(parents=True, exist_ok=True)
    suffix = uuid.uuid4().hex[:12]
    network = 'agentplatform-r62-egress-' + suffix
    container = 'agentplatform-r62-egress-' + suffix
    evidence = base / suffix
    evidence.mkdir()
    created_network = False
    started_container = False
    try:
        ids = call('docker', 'network', 'ls', '-q').stdout.split()
        networks = json.loads(call('docker', 'network', 'inspect', *ids).stdout) if ids else []
        used = [ipaddress.ip_network(item['Subnet']) for entry in networks
                for item in (entry.get('IPAM', {}).get('Config') or []) if item.get('Subnet')]
        subnet = next((str(candidate) for i in range(1, 255)
                       for candidate in [ipaddress.ip_network('10.236.' + str(i) + '.0/24')]
                       if not any(candidate.version == current.version and candidate.overlaps(current) for current in used)), None)
        if subnet is None:
            raise RuntimeError('No independent fixture subnet available')
        call('docker', 'network', 'create', '--subnet', subnet, network)
        created_network = True
        details = json.loads(call('docker', 'network', 'inspect', network).stdout)[0]
        subnet = details['IPAM']['Config'][0]['Subnet']
        config = evidence / 'squid.conf'
        config.write_text('\n'.join([
            'visible_hostname r62-egress-fixture', 'http_port 3128', 'max_filedescriptors 1024',
            'acl fixture_clients src ' + subnet, 'acl connect_method method CONNECT',
            'acl https_port port 443', 'acl forbidden_dest dst ' + ' '.join(CIDRS),
            'http_access deny !connect_method', 'http_access deny !https_port',
            'http_access deny forbidden_dest', 'http_access allow fixture_clients', 'http_access deny all',
            'cache deny all', 'cache_mem 8 MB', 'memory_pools off', 'access_log none', 'cache_log /dev/stderr',
            'pid_filename /tmp/squid.pid', 'coredump_dir /tmp', ''
        ]), encoding='utf-8', newline='\n')
        result = call('docker', 'run', '-d', '--name', container, '--network', network,
                      '-p', '127.0.0.1::3128', '--memory', '128m', '--memory-swap', '128m',
                      '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--read-only',
                      '--ulimit', 'nofile=1024:1024',
                      '--tmpfs', '/tmp:rw,noexec,nosuid,size=8m,mode=1777', '--tmpfs', '/var/log/squid:rw,nosuid,size=8m',
                      '--tmpfs', '/var/spool/squid:rw,nosuid,size=8m',
                      '--mount', f'type=bind,src={config},dst=/etc/squid/squid.conf,readonly',
                      '--entrypoint', '/usr/sbin/squid-gnutls', IMAGE, '-N', '-f', '/etc/squid/squid.conf')
        started_container = True
        identity = result.stdout.strip()
        inspect = json.loads(call('docker', 'inspect', identity).stdout)[0]
        port = inspect['NetworkSettings']['Ports']['3128/tcp'][0]['HostPort']
        proxy = 'http://127.0.0.1:' + port
        # Readiness is bounded and the only test target is the proxy's own loopback.
        deadline = time.monotonic() + 10
        while True:
            result = call('curl.exe', '--silent', '--show-error', '--max-time', '2', '--noproxy', '',
                          '--proxy', proxy, '--output', 'NUL', '--write-out', '%{http_connect}',
                          'https://127.0.0.1/', check=False)
            if result.stdout == '403':
                break
            if time.monotonic() >= deadline:
                logs = call('docker', 'logs', identity, check=False)
                (evidence / 'failure.log').write_text(logs.stdout + logs.stderr, encoding='utf-8')
                raise RuntimeError('Proxy did not reject loopback; inspect protected failure evidence')
        record = {'status': 'PASS', 'scope': 'Loopback destination rejected by isolated proxy only',
                  'image': IMAGE, 'sourceNetwork': subnet, 'connectStatus': 403,
                  'notRun': ['public TLS', 'DNS rebinding', 'MCP SDK', 'private ranges', 'resource observation']}
        (evidence / 'result.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'status': 'PASS', 'evidence': str(evidence.relative_to(ROOT)), 'scope': record['scope']}))
    except Exception as error:
        detail = str(error)
        if isinstance(error, subprocess.CalledProcessError):
            detail += '\n' + (error.stdout or '') + (error.stderr or '')
        if started_container:
            logs = call('docker', 'logs', container, check=False)
            state = call('docker', 'inspect', '--format', '{{json .State}}', container, check=False)
            detail += '\n' + state.stdout + logs.stdout + logs.stderr
        (evidence / 'failure.log').write_text(detail, encoding='utf-8')
        raise RuntimeError('Local fixture failed; inspect protected failure evidence: ' + str(evidence)) from error
    finally:
        if started_container:
            call('docker', 'stop', '-t', '5', container, check=False)
            call('docker', 'rm', '-f', container, check=False)
        if created_network:
            call('docker', 'network', 'rm', network, check=False)


if __name__ == '__main__':
    main()
