"""Exercise the generated proxy ACL with isolated workers and deterministic DNS."""
from pathlib import Path
import importlib.util
import ipaddress
import json
import secrets
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r62-local/proxy-policy' / ('run-' + str(time.time_ns()))
DOCKER = ['docker', '--context', 'desktop-linux']
OPENSSL = Path('C:/Program Files/Git/mingw64/bin/openssl.exe')
SQUID = 'ubuntu/squid@sha256:031d51a433928cf7476611f6138af85681ce4eec8568d1d9a4dcd28d16289c78'

def call(args, **kwargs): return subprocess.run([str(x) for x in args], check=True, capture_output=True, **kwargs)
def module():
    spec = importlib.util.spec_from_file_location('r62_proxy_config', ROOT / 'deploy/scripts/r62-mcp-proxy-config.py')
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result); return result

def main():
    DATA.mkdir(parents=True); token = secrets.token_hex(6)
    control, external = 'agentplatform-r62-control-' + token, 'agentplatform-r62-public-' + token
    networks = []; containers = []
    try:
        ids = call(DOCKER + ['network', 'ls', '-q']).stdout.decode().split()
        rows = json.loads(call(DOCKER + ['network', 'inspect', *ids]).stdout) if ids else []
        used = [ipaddress.ip_network(item['Subnet']) for row in rows for item in (row.get('IPAM', {}).get('Config') or []) if item.get('Subnet')]
        def choose(prefix):
            return next(s for i in range(1, 255) for s in [ipaddress.ip_network(prefix + str(i) + '.0/24')]
                        if not any(s.version == old.version and s.overlaps(old) for old in used))
        private, public = choose('10.236.'), choose('11.246.')
        call(DOCKER + ['network', 'create', '--internal', '--subnet', str(private), control]); networks.append(control)
        call(DOCKER + ['network', 'create', '--subnet', str(public), external]); networks.append(external)
        worker_ip, other_ip, proxy_ip = (str(private.network_address + n) for n in (10, 11, 20))
        fixture_ip, dns_ip, public_proxy_ip = (str(public.network_address + n) for n in (10, 11, 20))
        image = json.loads(call(DOCKER + ['image', 'inspect', 'agentplatform-sandbox:r32-local']).stdout)[0]['Id']
        with tempfile.TemporaryDirectory(dir=DATA) as directory:
            root = Path(directory); cert = root / 'fixture.crt'; key = root / 'fixture.key'; config = root / 'squid.conf'
            call([OPENSSL, 'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', '1', '-nodes', '-keyout', key,
                  '-out', cert, '-subj', '/CN=fixture.invalid', '-addext', 'subjectAltName=DNS:fixture.invalid'], timeout=30)
            text = module().render(worker_ip).replace('max_filedescriptors 1024\n',
                    'max_filedescriptors 1024\ndns_nameservers ' + dns_ip + '\npositive_dns_ttl 1 seconds\n')
            config.write_text(text, encoding='utf-8', newline='\n')
            common = ['--memory', '128m', '--memory-swap', '128m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                      '--read-only', '--mount', 'type=bind,src=' + str(root) + ',dst=/fixture,readonly']
            def start(name, args):
                identity = call(DOCKER + ['run', '-d', '--rm', '--name', 'ap-r62-' + name + '-' + token,
                    '--label', 'ap.stage=R62-proxy-policy'] + common + args, timeout=60).stdout.decode().strip()
                containers.append(identity); return identity
            start('server', ['--network', external, '--ip', fixture_ip, '--mount',
                  'type=bind,src=' + str(ROOT / 'deploy/probes/r62-mcp-tls-fixture.py') + ',dst=/fixture-server.py,readonly',
                  '--entrypoint', 'python3', image, '-u', '/fixture-server.py'])
            dns = start('dns', ['--network', external, '--ip', dns_ip, '--user', '0:0', '--env', 'R62_PUBLIC_FIXTURE_IP=' + fixture_ip,
                  '--mount', 'type=bind,src=' + str(ROOT / 'deploy/probes/r62-dns-fixture.py') + ',dst=/fixture-dns.py,readonly',
                  '--entrypoint', 'python3', image, '-u', '/fixture-dns.py'])
            proxy = start('proxy', ['--network', control, '--ip', proxy_ip, '--ulimit', 'nofile=1024:1024',
                  '--tmpfs', '/tmp:rw,noexec,nosuid,size=8m,mode=1777', '--tmpfs', '/var/log/squid:rw,nosuid,size=8m',
                  '--tmpfs', '/var/spool/squid:rw,nosuid,size=8m', '--mount', 'type=bind,src=' + str(config) + ',dst=/etc/squid/squid.conf,readonly',
                  '--entrypoint', '/usr/sbin/squid-gnutls', SQUID, '-N', '-f', '/etc/squid/squid.conf'])
            call(DOCKER + ['network', 'connect', '--ip', public_proxy_ip, external, proxy], timeout=30)
            def client(ip, expected):
                return subprocess.run(DOCKER + ['run', '--rm', '--network', control, '--ip', ip, '--memory', '128m', '--memory-swap', '128m',
                    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--read-only', '--env', 'R62_PROXY_IP=' + proxy_ip,
                    '--env', 'R62_EXPECT_CONNECT=' + expected, '--mount', 'type=bind,src=' + str(root) + ',dst=/fixture,readonly',
                    '--mount', 'type=bind,src=' + str(ROOT / 'deploy/probes/r62-proxy-client.py') + ',dst=/client.py,readonly',
                    '--entrypoint', 'python3', image, '/client.py'], capture_output=True, timeout=30)
            deadline = time.monotonic() + 25
            while True:
                first = client(worker_ip, '200')
                if first.returncode == 0: break
                if time.monotonic() >= deadline: raise RuntimeError('Authorized client failed: ' + first.stderr.decode(errors='replace')[-1000:])
                time.sleep(1)
            other = client(other_ip, '403')
            if other.returncode: raise RuntimeError('Other worker was not denied')
            (root / 'rebind').write_text('switch', encoding='utf-8'); time.sleep(3)
            changed = client(worker_ip, '403')
            if changed.returncode: raise RuntimeError('Private DNS destination was not denied')
            dnslog = call(DOCKER + ['logs', dns], timeout=20).stdout.decode(errors='replace')
            assert 'DNS_A_PUBLIC_FIXTURE' in dnslog and 'DNS_A_LOOPBACK' in dnslog
            receipt = {'status': 'PASS', 'scope': 'generated ACL: authorized worker, other worker, DNS change and TLS',
                'fixtureImage': image, 'squidImage': SQUID, 'notRun': ['deployed production configuration', 'SDK business call', 'all destination ranges']}
            (DATA / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8'); print(json.dumps(receipt))
    except Exception as failure:
        (DATA / 'failure.txt').write_text(str(failure), encoding='utf-8')
        for index, identity in enumerate(containers):
            result = subprocess.run(DOCKER + ['logs', identity], capture_output=True, timeout=20)
            (DATA / ('container-' + str(index) + '.private.log')).write_bytes(result.stdout + result.stderr)
        raise
    finally:
        for identity in reversed(containers): subprocess.run(DOCKER + ['stop', '-t', '5', identity], capture_output=True, timeout=30)
        for network in reversed(networks): subprocess.run(DOCKER + ['network', 'rm', network], capture_output=True, timeout=30)

if __name__ == '__main__': main()
