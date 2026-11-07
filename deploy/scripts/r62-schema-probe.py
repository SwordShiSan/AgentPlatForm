"""Exercise V23 on a fresh, isolated PostgreSQL container; no production data."""
from pathlib import Path
import hashlib
import json
import secrets
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / '.cache/r62-local/schema-probe'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    name = 'agentplatform-r62-schema-' + secrets.token_hex(6)
    env = OUT / (name + '.env')
    env.write_text('POSTGRES_PASSWORD=' + secrets.token_urlsafe(36) + '\n', encoding='utf-8')
    docker = ['docker', '--context', 'desktop-linux']
    container = None

    def run(args, **kwargs):
        return subprocess.run(docker + args, capture_output=True, timeout=90, **kwargs)

    try:
        created = run(['run', '-d', '--name', name, '--label', 'ap.stage=R62-schema',
                       '--network', 'none', '--memory', '320m', '--memory-swap', '320m',
                       '--cpus', '1', '--env-file', str(env), '--tmpfs',
                       '/var/lib/postgresql/data:rw,size=192m',
                       'pgvector/pgvector:0.8.1-pg17-trixie'])
        if created.returncode:
            raise RuntimeError('isolated PostgreSQL container failed to start')
        container = created.stdout.decode().strip()
        for _ in range(60):
            if run(['exec', container, 'pg_isready', '-U', 'postgres']).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('isolated PostgreSQL not ready')
        migrations = sorted((ROOT / 'backend/platform-app/src/main/resources/db/migration').glob('V*__*.sql'),
                            key=lambda p: int(p.name.split('__')[0][1:]))
        for file in migrations:
            result = run(['exec', '-i', container, 'psql', '-Xq', '-v', 'ON_ERROR_STOP=1',
                          '-U', 'postgres', '-d', 'postgres'],
                         input=b'BEGIN;\n' + file.read_bytes() + b'\nCOMMIT;\n')
            if result.returncode:
                (OUT / 'failure.private.log').write_bytes(result.stdout + result.stderr)
                raise RuntimeError('migration failed: ' + file.name)
        # Verify database constraints using independent owners; rollback all fixtures.
        checks = r"""
BEGIN;
INSERT INTO pf.app_user(id,username_norm,password_hash,status) VALUES
 ('00000000-0000-0000-0000-000000000001','r62-a','fixture','ACTIVE'),
 ('00000000-0000-0000-0000-000000000002','r62-b','fixture','ACTIVE');
INSERT INTO pf.space(id,type,owner_user_id) VALUES
 ('10000000-0000-0000-0000-000000000001','PERSONAL','00000000-0000-0000-0000-000000000001'),
 ('10000000-0000-0000-0000-000000000002','PERSONAL','00000000-0000-0000-0000-000000000002');
INSERT INTO pf.membership(space_id,user_id,role,status) VALUES
 ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','OWNER','ACTIVE'),
 ('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000002','OWNER','ACTIVE');
INSERT INTO pf.capability(id,space_id,owner_user_id,kind) VALUES
 ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','SKILL');
INSERT INTO pf.capability_version(id,space_id,owner_user_id,capability_id,version_number,content_hash) VALUES
 ('30000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',1,repeat('a',64));
INSERT INTO pf.capability_binding_set(id,space_id,owner_user_id) VALUES
 ('40000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000002');
DO $$ BEGIN
 BEGIN
  INSERT INTO pf.capability_binding_selection VALUES
   ('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000002',
    '40000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000001');
  RAISE EXCEPTION 'cross-owner binding accepted';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN
  UPDATE pf.capability_version SET content_hash=repeat('b',64)
   WHERE id='30000000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'immutable version updated';
 EXCEPTION WHEN SQLSTATE '55000' THEN NULL; END;
END $$;
ROLLBACK;
"""
        checked = run(['exec', '-i', container, 'psql', '-Xq', '-v', 'ON_ERROR_STOP=1',
                       '-U', 'postgres', '-d', 'postgres'], input=checks.encode('utf-8'))
        if checked.returncode:
            (OUT / 'constraints-failure.private.log').write_bytes(checked.stdout + checked.stderr)
            raise RuntimeError('capability constraint verification failed')
        result = {'status': 'PASS', 'scope': 'fresh PostgreSQL V1-V23 DDL and isolated owner/version constraints; not application acceptance',
                  'migrations': {f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in migrations},
                  'supplierCalls': 0}
        (OUT / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'status': 'PASS', 'migrations': len(migrations)}))
    finally:
        if container:
            run(['stop', '--time', '10', container])
            run(['rm', container])
        env.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
