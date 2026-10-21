"""Real local PostgreSQL regression from broad R0 defaults + R1 permissions.

Temporary schema-only database/roles only; refuse preexisting role names and
any non-r24 container. Never load business rows or touch cloud application data.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('container')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert re.fullmatch('[a-f0-9]{64}', args.container)
    state = json.loads(subprocess.check_output(['docker', 'inspect', args.container]))[0]
    labels = state['Config']['Labels']
    assert labels['com.docker.compose.project'] == 'agentplatform-r24-local' and labels['com.docker.compose.service'] == 'postgres'
    assert state['State']['Running']
    assert not args.output.exists()
    root = Path(__file__).resolve().parents[2]
    command = ['docker', 'exec', '-i', args.container]
    def invoke(arguments, content=None):
        result = subprocess.run(command + arguments, input=content, capture_output=True)
        assert result.returncode == 0, 'Local grant regression command failed; no database contents emitted'
        return result.stdout
    def sql(database, query):
        return invoke(['psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', database], query.encode()).decode().strip()
    assert sql('postgres', "SELECT count(*) FROM pg_roles WHERE rolname IN ('ap_api','ap_worker','ap_migration');") == '0', 'Never replace existing roles'
    assert sql('chat', 'SELECT max(version::int) FROM pf.flyway_schema_history WHERE success;') == '9'
    name = 'r24_grants_' + uuid.uuid4().hex
    assert re.fullmatch('r24_grants_[a-f0-9]{32}', name)
    created = False
    roles = False
    grants = (root / 'deploy/config/r2-runtime-grants.sql').read_bytes()
    try:
        sql('postgres', 'CREATE ROLE ap_api NOLOGIN; CREATE ROLE ap_worker NOLOGIN; CREATE ROLE ap_migration NOLOGIN;')
        roles = True
        invoke(['createdb', '-U', 'postgres', name]); created = True
        sql(name, 'CREATE EXTENSION IF NOT EXISTS vector;')
        schema = invoke(['pg_dump', '-U', 'postgres', '-d', 'chat', '--schema-only', '--no-owner', '--no-privileges', '-n', 'pf', '-n', 'pf_runtime'])
        invoke(['psql', '-Xq', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', name], schema)
        sql(name, '''
ALTER SCHEMA pf OWNER TO ap_migration;
ALTER SCHEMA pf_runtime OWNER TO ap_migration;
ALTER DEFAULT PRIVILEGES FOR ROLE ap_migration IN SCHEMA pf GRANT SELECT ON TABLES TO ap_api,ap_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE ap_migration IN SCHEMA pf_runtime GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO ap_api,ap_worker;
GRANT SELECT ON ALL TABLES IN SCHEMA pf TO ap_api,ap_worker;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA pf_runtime TO ap_api,ap_worker;
''')
        invoke(['psql', '-Xq', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', name], (root / 'deploy/config/r1-runtime-grants.sql').read_bytes())
        assert sql(name, "SELECT has_table_privilege('ap_api','pf_runtime.state_slot','SELECT') AND has_column_privilege('ap_api','pf.checkpoint','manifest_json','SELECT');") == 't'
        for _ in range(2):
            invoke(['psql', '-Xq', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', name], grants)
        queries = {
            'apiSdkCrudDenied': "SELECT NOT (has_table_privilege('ap_api','pf_runtime.state_slot','SELECT') OR has_table_privilege('ap_api','pf_runtime.state_slot','INSERT') OR has_table_privilege('ap_api','pf_runtime.state_slot','UPDATE') OR has_table_privilege('ap_api','pf_runtime.state_slot','DELETE'));",
            'apiCheckpointPrivateDenied': "SELECT NOT (has_column_privilege('ap_api','pf.checkpoint','manifest_json','SELECT') OR has_column_privilege('ap_api','pf.checkpoint','state_json','SELECT') OR has_column_privilege('ap_api','pf.checkpoint','canonical_json','SELECT'));",
            'apiCheckpointMetadataAllowed': "SELECT has_column_privilege('ap_api','pf.checkpoint','task_id','SELECT') AND has_column_privilege('ap_api','pf.checkpoint','phase','SELECT') AND has_column_privilege('ap_api','pf.checkpoint','manifest_version','SELECT');",
            'apiCiphertextDenied': "SELECT NOT has_column_privilege('ap_api','pf.credential','ciphertext','SELECT');",
            'apiProofDenied': "SELECT NOT (has_table_privilege('ap_api','pf.stopped_owner_proof','SELECT') OR has_table_privilege('ap_api','pf.stopped_owner_proof','INSERT'));",
            'workerStateAndCheckpointAllowed': "SELECT has_table_privilege('ap_worker','pf_runtime.state_slot','UPDATE') AND has_table_privilege('ap_worker','pf.checkpoint','INSERT') AND has_column_privilege('ap_worker','pf.checkpoint','state_json','SELECT');",
            'workerBindingAllowed': "SELECT has_table_privilege('ap_worker','pf.turn_execution_binding','INSERT');",
        }
        checks = {key: sql(name, query) == 't' for key, query in queries.items()}
        assert all(checks.values()), 'Inherited ACL regression failed: ' + str(checks)
        sql(name, "SET ROLE ap_api; SELECT id,attempt_id,bundle_hash,phase FROM pf.checkpoint WHERE task_id='00000000-0000-0000-0000-000000000001' AND manifest_version=2 ORDER BY created_at DESC,id DESC LIMIT 1; SELECT count(*) FROM pf.tool_operation WHERE task_id='00000000-0000-0000-0000-000000000001' AND status='UNKNOWN'; RESET ROLE;")
        checks['actualMetadataQueryAllowed'] = True
        sql(name, 'SET ROLE ap_migration; CREATE TABLE pf_runtime.future_private_probe(id integer); CREATE TABLE pf.future_private_probe(id integer); RESET ROLE;')
        checks['futurePrivateDefaultsDenied'] = sql(name, "SELECT NOT (has_table_privilege('ap_api','pf_runtime.future_private_probe','SELECT') OR has_table_privilege('ap_api','pf.future_private_probe','SELECT'));") == 't'
        assert checks['futurePrivateDefaultsDenied']
        args.output.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(dict(status='PASS', scope='REAL_LOCAL_POSTGRES_INHERITED_R0_R1_ACLS', sourceContainer=args.container,
                           schemaOnly=True, sourceRowsModified=False, broadLegacyAclReproduced=True,
                           idempotentApplications=2, grantSha256=hashlib.sha256(grants).hexdigest(), checks=checks), stream, indent=2)
        print('PASS real local inherited R0/R1 grants, private API denial, worker access, future defaults and idempotence')
    finally:
        if created:
            invoke(['dropdb', '-U', 'postgres', name])
        if roles:
            sql('postgres', 'DROP ROLE ap_api; DROP ROLE ap_worker; DROP ROLE ap_migration;')


if __name__ == '__main__':
    main()
