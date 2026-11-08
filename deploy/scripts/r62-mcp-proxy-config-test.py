"""Check candidate generation and parse with the pinned local Squid binary."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
def module(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value

config = module('r62_proxy_config', 'deploy/scripts/r62-mcp-proxy-config.py')
probe = module('r62_egress_probe', 'deploy/scripts/r62-egress-probe.py')
assert set(config.FORBIDDEN) == set(probe.CIDRS), 'Candidate and tested destination lists must match'
text = config.render('172.30.1.7')
assert 'acl worker src 172.30.1.7/32' in text and 'access_log none' in text
for invalid in ('127.0.0.1', '8.8.8.8', '::1', '10.0.0.1\nhttp_access allow all', '10.0.0.0/8'):
    try: config.render(invalid)
    except ValueError: continue
    raise AssertionError('Invalid worker address was accepted')
with tempfile.TemporaryDirectory(dir=ROOT / '.cache') as directory:
    path = Path(directory) / 'squid.conf'; path.write_text(text, encoding='utf-8', newline='\n')
    result = subprocess.run(['docker', '--context', 'desktop-linux', 'run', '--rm', '--network', 'none',
        '--read-only', '--memory', '128m', '--memory-swap', '128m', '--cap-drop', 'ALL',
        '--tmpfs', '/tmp:rw,noexec,nosuid,size=8m,mode=1777', '--tmpfs', '/var/log/squid:rw,nosuid,size=8m',
        '--tmpfs', '/var/spool/squid:rw,nosuid,size=8m', '--mount', 'type=bind,src=' + str(path) + ',dst=/etc/squid/squid.conf,readonly',
        '--entrypoint', '/usr/sbin/squid-gnutls', probe.IMAGE, '-k', 'parse', '-f', '/etc/squid/squid.conf'], capture_output=True, text=True, timeout=45)
    if result.returncode != 0: raise RuntimeError('Squid rejected candidate configuration: ' + result.stderr[-3000:])
print('PASS: fixed worker address and destination ACL candidate; no production deployment')
