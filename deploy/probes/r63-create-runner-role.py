"""Create/update only the isolated R63 preview-runner database identity."""
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / '.cache/r63-local/fixture'
PASSWORD = PRIVATE / 'runner-db-password'
CONTAINER = 'agentplatform-r63-local-postgres-1'
DATABASE = 'r63_2fa963f96f7f'

if not PASSWORD.is_file():
    PASSWORD.write_text(secrets.token_urlsafe(32), encoding='ascii')
password = PASSWORD.read_text(encoding='ascii').strip()
if not password or any(char not in 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-' for char in password):
    raise SystemExit('Runner fixture password format rejected')

base = ['docker', '--context', 'desktop-linux', 'exec', '-i', CONTAINER,
        'psql', '-v', 'ON_ERROR_STOP=1', '-X', '-q', '-U', 'postgres', '-d', DATABASE]
check = subprocess.run(base + ['-A', '-t', '-c',
    "SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ap_r63_runner')"],
    capture_output=True, text=True, timeout=20)
if check.returncode or check.stdout.strip() not in {'t', 'f'}:
    raise SystemExit('Isolated PostgreSQL role preflight failed')

if check.stdout.strip() == 't':
    statement = f"ALTER ROLE ap_r63_runner LOGIN PASSWORD '{password}';\n"
else:
    statement = f"CREATE ROLE ap_r63_runner LOGIN PASSWORD '{password}';\n"
statement += 'GRANT ap_runner TO ap_r63_runner;\n'
created = subprocess.run(base, input=statement, capture_output=True, text=True, timeout=20)
if created.returncode:
    raise SystemExit('Isolated PostgreSQL runner role setup failed')

verify = subprocess.run(base + ['-A', '-t', '-c',
    "SELECT pg_has_role('ap_r63_runner','ap_runner','MEMBER')"],
    capture_output=True, text=True, timeout=20)
if verify.returncode or verify.stdout.strip() != 't':
    raise SystemExit('Isolated PostgreSQL runner role membership check failed')
print('R63_ISOLATED_RUNNER_ROLE_READY')
