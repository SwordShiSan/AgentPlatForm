"""Isolated Squid DNS-change test. It creates no persistent services or production data."""
from pathlib import Path
import importlib.util
import ipaddress
import json
import secrets
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r62-local/dns-change' / ('run-' + str(time.time_ns()))
DOCKER = ['docker', '--context', 'desktop-linux']
SQUID = 'ubuntu/squid@sha256:031d51a433928cf7476611f6138af85681ce4eec8568d1d9a4dcd28d16289c78'
OPENSSL = Path('C:/Program Files/Git/mingw64/bin/openssl.exe')

def call(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, capture_output=True, **kwargs)

def main():
    DATA.mkdir(parents=True)
    spec = importlib.util.spec_from_file_location('r62_egress', ROOT / 'deploy/scripts/r62-egress-probe.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    network = 'agentplatform-r62-dns-' + secrets.token_hex(6); containers = []; created = False
    try:
        ids = call(DOCKER + ['network', 'ls', '-q']).stdout.decode().split()
        rows = json.loads(call(DOCKER + ['network', 'inspect', *ids]).stdout) if ids else []
        used = [ipaddress.ip_network(item['Subnet']) for row in rows for item in (row.get('IPAM', {}).get('Config') or []) if item.get('Subnet')]
        subnet = next((s for i in range(1, 255) for s in [ipaddress.ip_network('11.245.' + str(i) + '.0/24')]
                       if not any(s.version == old.version and s.overlaps(old) for old in used)), None)
        if subnet is None: raise RuntimeError('No isolated network available')
        call(DOCKER + ['network', 'create', '--subnet', str(subnet), network]); created = True
        fixture_ip, dns_ip = str(subnet.network_address + 10), str(subnet.network_address + 11)
        image = json.loads(call(DOCKER + ['image', 'inspect', 'agentplatform-sandbox:r32-local']).stdout)[0]['Id']
        with tempfile.TemporaryDirectory(dir=DATA) as temporary:
            root = Path(temporary); key = root / 'fixture.key'; cert = root / 'fixture.crt'
            call([OPENSSL, 'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', '1', '-nodes', '-keyout', key,
                  '-out', cert, '-subj', '/CN=fixture.invalid', '-addext', 'subjectAltName=DNS:fixture.invalid'], timeout=30)
            config = root / 'squid.conf'
            config.write_text('\n'.join(['visible_hostname r62-dns-fixture', 'http_port 3128', 'max_filedescriptors 1024',
                'dns_nameservers ' + dns_ip, 'positive_dns_ttl 1 seconds', 'acl clients src ' + str(subnet),
                'acl connect_method method CONNECT', 'acl allowed_port port 8443', 'acl allowed_host dstdomain fixture.invalid',
                'acl forbidden_dest dst ' + ' '.join(module.CIDRS), 'http_access deny !connect_method',
                'http_access deny !allowed_port', 'http_access deny !allowed_host', 'http_access deny forbidden_dest',
                'http_access allow clients', 'http_access deny all', 'cache deny all', 'cache_mem 8 MB', 'memory_pools off',
                'access_log stdio:/dev/stderr squid', 'cache_log /dev/stderr', 'pid_filename /tmp/squid.pid', 'coredump_dir /tmp', '']), encoding='utf-8', newline='\n')
            common = ['--network', network, '--memory', '128m', '--memory-swap', '128m', '--cap-drop', 'ALL',
                      '--security-opt', 'no-new-privileges:true', '--read-only', '--mount', 'type=bind,src=' + str(root) + ',dst=/fixture,readonly']
            def start(name, options):
                identity = call(DOCKER + ['run', '-d', '--rm', '--name', network + '-' + name, '--label', 'ap.stage=R62-dns-fixture'] + common + options, timeout=60).stdout.decode().strip()
                containers.append(identity); return identity
            start('server', ['--ip', fixture_ip, '--mount', 'type=bind,src=' + str(ROOT / 'deploy/probes/r62-mcp-tls-fixture.py') + ',dst=/fixture-server.py,readonly',
                             '--entrypoint', 'python3', image, '-u', '/fixture-server.py'])
            dns = start('dns', ['--ip', dns_ip, '--user', '0:0', '--env', 'R62_PUBLIC_FIXTURE_IP=' + fixture_ip,
                          '--mount', 'type=bind,src=' + str(ROOT / 'deploy/probes/r62-dns-fixture.py') + ',dst=/fixture-dns.py,readonly',
                          '--entrypoint', 'python3', image, '-u', '/fixture-dns.py'])
            proxy = start('proxy', ['--publish', '127.0.0.1::3128', '--ulimit', 'nofile=1024:1024',
                         '--tmpfs', '/tmp:rw,noexec,nosuid,size=8m,mode=1777', '--tmpfs', '/var/log/squid:rw,nosuid,size=8m',
                         '--tmpfs', '/var/spool/squid:rw,nosuid,size=8m', '--mount', 'type=bind,src=' + str(config) + ',dst=/etc/squid/squid.conf,readonly',
                         '--entrypoint', '/usr/sbin/squid-gnutls', SQUID, '-N', '-f', '/etc/squid/squid.conf'])
            row = json.loads(call(DOCKER + ['inspect', proxy]).stdout)[0]
            bindings = row['NetworkSettings']['Ports']['3128/tcp']; assert len(bindings) == 1 and bindings[0]['HostIp'] == '127.0.0.1'
            proxy_url = 'http://127.0.0.1:' + bindings[0]['HostPort']
            def request():
                return subprocess.run(['curl.exe', '--silent', '--show-error', '--max-time', '8', '--noproxy', '', '--proxy', proxy_url,
                    '--cacert', str(cert), '--output', 'NUL', '--write-out', '%{http_connect}:%{http_code}', 'https://fixture.invalid:8443/'],
                    capture_output=True, text=True, timeout=12)
            deadline = time.monotonic() + 25
            while True:
                first = request()
                if first.returncode == 0 and first.stdout == '200:405': break
                if time.monotonic() >= deadline: raise RuntimeError('Public fixture TLS did not become ready: ' + first.stdout)
                time.sleep(1)
            (root / 'rebind').write_text('switch', encoding='utf-8')
            time.sleep(3)
            changed = request()
            if not changed.stdout.startswith('403:'): raise RuntimeError('Changed private destination was not rejected: ' + changed.stdout)
            dns_log = call(DOCKER + ['logs', dns], timeout=20).stdout.decode(errors='replace')
            if 'DNS_A_PUBLIC_FIXTURE' not in dns_log or 'DNS_A_LOOPBACK' not in dns_log:
                raise RuntimeError('DNS fixture did not confirm both destination answers')
            receipt = {'status': 'PASS', 'scope': 'fixture DNS changes from public-test subnet to loopback; Squid rechecks destination',
                       'first': first.stdout, 'afterChange': changed.stdout, 'squidImage': SQUID,
                       'notRun': ['production configuration', 'all special addresses', 'SDK business call', 'external DNS']}
            (DATA / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8'); print(json.dumps(receipt))
    except Exception as error:
        (DATA / 'failure.txt').write_text(str(error), encoding='utf-8')
        for index, identity in enumerate(containers):
            logs = subprocess.run(DOCKER + ['logs', identity], capture_output=True, timeout=20)
            (DATA / ('container-' + str(index) + '.private.log')).write_bytes(logs.stdout + logs.stderr)
        raise
    finally:
        for identity in reversed(containers): subprocess.run(DOCKER + ['stop', '-t', '5', identity], capture_output=True, timeout=30)
        if created: subprocess.run(DOCKER + ['network', 'rm', network], capture_output=True, timeout=30)

if __name__ == '__main__': main()
