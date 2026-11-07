"""Run the R6.2 binding mapper and migration test against an isolated temporary PostgreSQL."""
from pathlib import Path
import json
import hashlib
import os
import secrets
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache' / 'r62-local' / 'postgres' / ('run-' + str(time.time_ns()))
DOCKER = ['docker', '--context', 'desktop-linux']

def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], capture_output=True, check=True, **kwargs)

def main():
    DATA.mkdir(parents=True, exist_ok=True)
    password = secrets.token_urlsafe(32)
    env_file = DATA / 'postgres.env'
    password_file = DATA / 'password'
    env_file.write_text('POSTGRES_PASSWORD=' + password + '\nPOSTGRES_DB=r62_test\n', encoding='utf-8')
    password_file.write_text(password, encoding='utf-8')
    identity = None
    try:
        identity = run(DOCKER + ['run', '-d', '--name', 'agentplatform-r62-pg-' + secrets.token_hex(5),
            '--label', 'ap.stage=R62-persistence', '--memory', '320m', '--memory-swap', '320m', '--cpus', '1',
            '--publish', '127.0.0.1::5432', '--env-file', env_file, '--tmpfs', '/var/lib/postgresql/data:rw,size=256m',
            'pgvector/pgvector:0.8.1-pg17-trixie'], timeout=90).stdout.decode().strip()
        row = json.loads(run(DOCKER + ['inspect', identity]).stdout)[0]
        binding = row['NetworkSettings']['Ports']['5432/tcp']
        assert len(binding) == 1 and binding[0]['HostIp'] == '127.0.0.1'
        port = int(binding[0]['HostPort'])
        for _ in range(60):
            if subprocess.run(DOCKER + ['exec', identity, 'pg_isready', '-U', 'postgres', '-d', 'r62_test'], capture_output=True).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Isolated PostgreSQL not ready')
        env = dict(os.environ, R62_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:' + str(port) + '/r62_test?sslmode=disable',
                   R62_TEST_PORT=str(port), R62_TEST_PASSWORD_FILE=str(password_file), R62_ACCEPTANCE='1')
        if os.environ.get('R62_MCP_APPROVAL_ACCEPTANCE') == '1':
            env.update(R43_TEST_JDBC_URL=env['R62_TEST_JDBC_URL'], R43_TEST_PASSWORD_FILE=str(password_file))
        tracked = run(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z', '--', 'backend'], cwd=ROOT).stdout.decode().split('\0')
        def inputs():
            return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in sorted(set(tracked) - {''}) if (ROOT / name).is_file()}
        before = inputs()
        selection = ('ApprovalPostgresTest#r62ProtectedMcpFixtureRunsOnlyAfterOriginalApproval'
                     if os.environ.get('R62_MCP_APPROVAL_ACCEPTANCE') == '1' else
                     'CapabilityBindingPostgresTest#realBrokerAndObjectStoreCreateOneSkillVersion,TaskSkillMaterialServiceTest'
                     if os.environ.get('R62_INTEGRATED_ACCEPTANCE') == '1' else
                     'CapabilityBindingPostgresTest,SkillImportControllerTest,SkillImportServiceTest,SkillValidationServiceTest,SkillArchiveValidatorTest,ConnectorCredentialCipherTest,ConnectorCredentialConfigurationTest,ConnectorCredentialControllerTest')
        result = subprocess.run([str(ROOT / 'backend' / 'mvnw.cmd'), '-Dtest=' + selection,
                                 '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], cwd=ROOT / 'backend',
                                env=env, capture_output=True, timeout=300)
        (DATA / 'maven.log').write_bytes(result.stdout + result.stderr)
        unchanged = before == inputs()
        receipt = {'status': 'PASS' if result.returncode == 0 and unchanged else 'FAIL', 'exitCode': result.returncode,
                   'scope': ('isolated PostgreSQL, approval ledger and loopback SDK MCP fixture; no actual MQ or controlled proxy'
                             if os.environ.get('R62_MCP_APPROVAL_ACCEPTANCE') == '1' else
                             'isolated PG/MQ/S3 Skill import pipeline' if os.environ.get('R62_INTEGRATED_ACCEPTANCE') == '1' else
                             'V22-to-V23 migration; binding, import, connector credential and probe transactions; failed local proxy through SDK; targeted unit tests; no real MQ or S3'),
                   'source': run(['git', 'rev-parse', 'HEAD'], cwd=ROOT).stdout.decode().strip(),
                   'workingTreeClean': not bool(run(['git', 'status', '--porcelain'], cwd=ROOT).stdout.strip()), 'inputUnchanged': unchanged}
        (DATA / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
        (DATA / 'inputs.json').write_text(json.dumps(before, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(receipt))
        if receipt['status'] != 'PASS':
            raise RuntimeError('R62 PostgreSQL test failed; inspect ' + str(DATA / 'maven.log'))
    finally:
        if identity:
            row = json.loads(run(DOCKER + ['inspect', identity]).stdout)[0]
            assert row['Config']['Labels'].get('ap.stage') == 'R62-persistence'
            run(DOCKER + ['rm', '-f', identity])
        env_file.unlink(missing_ok=True)
        password_file.unlink(missing_ok=True)

if __name__ == '__main__':
    main()
