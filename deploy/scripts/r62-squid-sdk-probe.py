"""SDK + TLS + Squid feasibility probe restricted to two local fixture domains."""
from pathlib import Path
import hashlib
import importlib.util
import ipaddress
import json
import os
import secrets
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r62-local/squid-sdk' / ('run-' + str(time.time_ns()))
JAR = ROOT / 'backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar'
JDK = ROOT / '.tools/jdk-21.0.12.1/jdk-21.0.12.1+1/bin'
OPENSSL = Path('C:/Program Files/Git/mingw64/bin/openssl.exe')
DOCKER = ['docker', '--context', 'desktop-linux']
SQUID = 'ubuntu/squid@sha256:031d51a433928cf7476611f6138af85681ce4eec8568d1d9a4dcd28d16289c78'

def call(args, **kwargs): return subprocess.run([str(item) for item in args], check=True, capture_output=True, **kwargs)

def main():
    DATA.mkdir(parents=True); assert JAR.is_file() and OPENSSL.is_file()
    spec = importlib.util.spec_from_file_location('egress_fixture', ROOT / 'deploy/scripts/r62-egress-probe.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    network = 'agentplatform-r62-sdk-' + secrets.token_hex(6); containers = []; created = False
    try:
        ids = call(DOCKER + ['network', 'ls', '-q']).stdout.decode().split()
        rows = json.loads(call(DOCKER + ['network', 'inspect', *ids]).stdout) if ids else []
        used = [ipaddress.ip_network(item['Subnet']) for row in rows for item in (row.get('IPAM', {}).get('Config') or []) if item.get('Subnet')]
        subnet = next((candidate for i in range(1, 255) for candidate in [ipaddress.ip_network('11.244.' + str(i) + '.0/24')]
                       if not any(candidate.version == old.version and candidate.overlaps(old) for old in used)), None)
        if subnet is None: raise RuntimeError('No independent fixture network available')
        call(DOCKER + ['network', 'create', '--subnet', str(subnet), network]); created = True
        fixture_ip = str(subnet.network_address + 10)
        image = json.loads(call(DOCKER + ['image', 'inspect', 'agentplatform-sandbox:r32-local']).stdout)[0]['Id']
        with tempfile.TemporaryDirectory(dir=DATA) as temporary:
            root = Path(temporary); key = root / 'fixture.key'; cert = root / 'fixture.crt'; trust = root / 'trust.jks'
            call([OPENSSL, 'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', '1', '-nodes', '-keyout', key,
                  '-out', cert, '-subj', '/CN=fixture.invalid', '-addext', 'subjectAltName=DNS:fixture.invalid'], timeout=30)
            env = dict(os.environ, R62_FIXTURE_STORE_PASSWORD=secrets.token_urlsafe(20))
            call([JDK / 'keytool.exe', '-importcert', '-noprompt', '-alias', 'fixture', '-file', cert, '-keystore', trust,
                  '-storetype', 'JKS', '-storepass:env', 'R62_FIXTURE_STORE_PASSWORD'], env=env, timeout=30)
            squid = root / 'squid.conf'
            squid.write_text('\n'.join(['visible_hostname r62-sdk-fixture', 'http_port 3128', 'max_filedescriptors 1024',
                'acl fixture_clients src ' + str(subnet), 'acl connect_method method CONNECT', 'acl https_port port 443 8443',
                'acl fixture_hosts dstdomain fixture.invalid private.invalid',
                'acl forbidden_dest dst ' + ' '.join(module.CIDRS), 'http_access deny !connect_method',
                'http_access deny !https_port', 'http_access deny !fixture_hosts', 'http_access deny forbidden_dest', 'http_access allow fixture_clients',
                'http_access deny all', 'cache deny all', 'cache_mem 8 MB', 'memory_pools off',
                'access_log stdio:/dev/stderr squid', 'cache_log /dev/stderr', 'pid_filename /tmp/squid.pid', 'coredump_dir /tmp', '']), encoding='utf-8', newline='\n')
            fixture = call(DOCKER + ['run', '-d', '--rm', '--name', network + '-server', '--label', 'ap.stage=R62-sdk-fixture',
                '--network', network, '--ip', fixture_ip, '--memory', '128m', '--memory-swap', '128m', '--cpus', '1',
                '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--workdir', '/tmp',
                '--tmpfs', '/tmp:rw,noexec,nosuid,size=8m,uid=10001,gid=10001',
                '--mount', 'type=bind,src=' + str(root) + ',dst=/fixture,readonly',
                '--mount', 'type=bind,src=' + str(ROOT / 'deploy/probes/r62-mcp-tls-fixture.py') + ',dst=/fixture-server.py,readonly',
                '--entrypoint', 'python3', image, '-u', '/fixture-server.py'], timeout=60).stdout.decode().strip()
            containers.append(fixture)
            proxy = call(DOCKER + ['run', '-d', '--rm', '--name', network + '-proxy', '--label', 'ap.stage=R62-sdk-fixture',
                '--network', network, '--publish', '127.0.0.1::3128', '--memory', '128m', '--memory-swap', '128m',
                '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--read-only', '--ulimit', 'nofile=1024:1024',
                '--add-host', 'fixture.invalid:' + fixture_ip, '--add-host', 'private.invalid:127.0.0.1',
                '--tmpfs', '/tmp:rw,noexec,nosuid,size=8m,mode=1777', '--tmpfs', '/var/log/squid:rw,nosuid,size=8m',
                '--tmpfs', '/var/spool/squid:rw,nosuid,size=8m', '--mount', 'type=bind,src=' + str(squid) + ',dst=/etc/squid/squid.conf,readonly',
                '--entrypoint', '/usr/sbin/squid-gnutls', SQUID, '-N', '-f', '/etc/squid/squid.conf'], timeout=60).stdout.decode().strip()
            containers.append(proxy)
            info = json.loads(call(DOCKER + ['inspect', proxy]).stdout)[0]
            ports = info['NetworkSettings']['Ports']['3128/tcp']; assert len(ports) == 1 and ports[0]['HostIp'] == '127.0.0.1'
            port = int(ports[0]['HostPort']); deadline = time.monotonic() + 20
            while True:
                result = subprocess.run(['curl.exe', '--silent', '--show-error', '--max-time', '2', '--noproxy', '',
                    '--proxy', 'http://127.0.0.1:' + str(port), '--output', 'NUL', '--write-out', '%{http_connect}', 'https://private.invalid:8443/'], capture_output=True, text=True)
                if result.stdout == '403': break
                if time.monotonic() >= deadline: raise RuntimeError('Proxy did not reject private DNS result')
                time.sleep(1)
            classes = root / 'probe-classes'; classes.mkdir()
            classpath = os.pathsep.join([str(ROOT / 'backend/platform-infrastructure/target/classes'), str(ROOT / 'backend/platform-domain/target/classes')])
            call([JDK / 'javac.exe', '-cp', classpath, '-d', classes, ROOT / 'deploy/probes/R62McpProcessProbe.java'], timeout=45)
            command = [str(JDK / 'java.exe'), '-Xms16m', '-Xmx128m', '-Dloader.path=' + str(classes),
                       '-Dloader.main=R62McpProcessProbe', '-cp', str(JAR), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(port), str(trust)]
            result = subprocess.run(command, text=True, capture_output=True, timeout=90)
            assert result.returncode == 0 and 'R62_PACKAGED_PARENT_CHILD_PASS' in result.stdout
            assert 'deterministic-fixture-only' not in result.stdout and 'deterministic-fixture-only' not in result.stderr
            receipt = {'status': 'PASS', 'scope': 'packaged SDK parent/child + TLS fixture + Squid, private DNS target rejected',
                       'jarSha256': hashlib.sha256(JAR.read_bytes()).hexdigest(), 'fixtureImage': image, 'squidImage': SQUID,
                       'privateConnect': 403, 'tools': 1, 'notRun': ['DNS rebinding', 'all special addresses', 'production worker', 'protected business call']}
            (DATA / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8'); print(json.dumps(receipt))
    except Exception:
        for index, item in enumerate(containers):
            logs = subprocess.run(DOCKER + ['logs', item], capture_output=True, timeout=20)
            (DATA / ('container-' + str(index) + '.private.log')).write_bytes(logs.stdout + logs.stderr)
        raise
    finally:
        for item in reversed(containers): subprocess.run(DOCKER + ['stop', '-t', '5', item], capture_output=True, timeout=30)
        if created: subprocess.run(DOCKER + ['network', 'rm', network], capture_output=True, timeout=30)

if __name__ == '__main__': main()
