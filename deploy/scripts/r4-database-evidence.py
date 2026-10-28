"""Bounded, read-only fingerprints for quiescent R2 backup/serial-restore comparison.

Only row SHA256 values cross psql stdout. No SQL dump, ciphertext or session token
is emitted, and all tables in both authoritative schemas are included dynamically.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def capture(container, expected_migration=9):
    assert expected_migration in (9, 14, 18)
    assert re.fullmatch('[a-f0-9]{64}', container), 'Use the exact PostgreSQL container ID'
    state = json.loads(subprocess.check_output(['docker', 'inspect', container]))
    assert len(state) == 1
    state = state[0]
    labels = state['Config']['Labels']
    project = labels['com.docker.compose.project']
    assert labels['com.docker.compose.service'] == 'postgres' and state['State']['Running']
    assert project in {'agentplatform-env', 'agentplatform-r2-restore-env', 'agentplatform-r3-restore-env', 'agentplatform-r4-restore-env', 'agentplatform-r24-local'}
    local = project == 'agentplatform-r24-local'
    app = project if local else project.removesuffix('-env') + '-app'
    if expected_migration in (14,18) and project == 'agentplatform-env':
        assert not subprocess.check_output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=agentplatform-r3-app'], text=True).strip(), 'Stop R3 applications before fingerprinting'
    if project == 'agentplatform-env':
        assert not subprocess.check_output(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-r4-app'],text=True).strip(), 'Stop R4 applications before fingerprinting'
    running = subprocess.check_output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=' + app], text=True).splitlines()
    if running:
        services = json.loads(subprocess.check_output(['docker', 'inspect', *running]))
        assert all(item['Config']['Labels']['com.docker.compose.service'] in {'postgres', 'rabbitmq', 'redis', 'master', 'volume', 'filer', 's3'} for item in services), 'Stop the application before taking a quiescent fingerprint'
    database, role = ('chat', 'postgres') if local else ('agentplatform', 'ap_admin')
    command = ['docker', 'exec', '-i', state['Id'], 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', role, '-d', database]

    def query(sql):
        value = subprocess.run(command, input=sql, text=True, capture_output=True)
        if value.returncode:
            raise RuntimeError('Database evidence query failed; no raw database output disclosed')
        return value.stdout.strip()

    assert query('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL;') == '0', 'Unresolved owner prevents a quiescent backup claim'
    tables = query("SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN ('pf','pf_runtime') ORDER BY schemaname,tablename;").splitlines()
    assert tables and all(re.fullmatch(r'(pf|pf_runtime)\.[a-z][a-z0-9_]*', name) for name in tables)
    metadata = json.loads(query("SELECT json_build_object('migration',max(version::int),'runtimeBaseline',(SELECT schema_version FROM pf.baseline_version WHERE id=1),'postgres',current_setting('server_version'),'vector',(SELECT extversion FROM pg_extension WHERE extname='vector')) FROM pf.flyway_schema_history WHERE success;"))
    assert metadata['migration'] == expected_migration and metadata['runtimeBaseline'] == 1
    statements = ['BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;', "SET LOCAL work_mem='4MB';", "SET LOCAL statement_timeout='120s';"]
    for name in tables:
        statements += ["SELECT 'TABLE:" + name + "';", "COPY (SELECT encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') AS h FROM " + name + " t ORDER BY h) TO STDOUT;"]
    statements += ["SELECT 'COMPLETE';", 'COMMIT;']
    script = tempfile.TemporaryFile(mode='w+t', encoding='utf-8')
    script.write('\n'.join(statements)); script.seek(0)
    process = subprocess.Popen(command, stdin=script, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8')
    result = {}
    active = None
    complete = False
    try:
        for raw in process.stdout:
            line = raw.strip()
            if line.startswith('TABLE:'):
                active = line[6:]
                assert active in tables and active not in result
                result[active] = {'rows': 0, 'hash': hashlib.sha256()}
            elif line == 'COMPLETE':
                complete = True
            else:
                assert active and not complete and re.fullmatch('[a-f0-9]{64}', line), 'Unexpected database output; evidence refused'
                result[active]['rows'] += 1
                result[active]['hash'].update((line + '\n').encode('ascii'))
        process.wait(timeout=130)
        assert process.returncode == 0 and complete and set(result) == set(tables), 'Incomplete database evidence'
    finally:
        if process.poll() is None:
            process.kill(); process.wait()
        process.stdout.close(); process.stderr.close()
        script.close()
    return {'status': 'PASS', 'scope': 'QUIESCENT_DATABASE_FINGERPRINT_ONLY', 'metadata': metadata,
            'captureIdentity': {'project': project, 'containerId': state['Id'], 'imageId': state['Image'], 'startedAt': state['State']['StartedAt']},
            'tables': {name: {'rows': value['rows'], 'sha256OfSortedRowSha256': value['hash'].hexdigest()} for name, value in result.items()},
            'plaintextRowsEmitted': False, 'supplierRequests': 0}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('container', help='Exact running PostgreSQL container ID')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--compare', type=Path)
    parser.add_argument('--expected-migration', type=int, choices=[9, 14, 18], default=9)
    args = parser.parse_args()
    result = capture(args.container, args.expected_migration)
    if args.compare:
        before = json.loads(args.compare.read_text(encoding='utf-8'))
        assert before['status'] == 'PASS' and before['metadata'] == result['metadata'] and before['tables'] == result['tables'], 'Restored database facts differ'
        result['comparison'] = 'PASS'
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as stream:
        json.dump(result, stream, indent=2); stream.write('\n')
    print('PASS quiescent schema'+str(args.expected_migration)+'/baseline1 fingerprints; no plaintext rows, worker or supplier request.')


if __name__ == '__main__':
    main()
