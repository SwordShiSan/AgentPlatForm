#!/usr/bin/env bash
set -euo pipefail
root=/srv/agentplatform
test -f "$root/operations/legacy-paused"
bash "$root/deploy/scripts/recovery-database-evidence.sh" seed agentplatform-env-postgres-1
python3 - <<'PY'
import os,pathlib,subprocess
base=pathlib.Path('/srv/agentplatform/workspaces');fixture=base/'r04-recovery-fixture'
expected={'empty directory','中文 space.txt','line\nbreak.txt'}
if fixture.exists():
    assert fixture.is_dir() and not fixture.is_symlink()
    assert {p.name for p in fixture.iterdir()}==expected
    assert not list((fixture/'empty directory').iterdir())
    for name in expected-{'empty directory'}:
        assert (fixture/name).read_text()=='R0.4 persisted workspace fixture\n'
else:
    fixture.mkdir();(fixture/'empty directory').mkdir()
    for name in expected-{'empty directory'}:(fixture/name).write_text('R0.4 persisted workspace fixture\n')
for path in [fixture,*fixture.rglob('*')]:os.chown(path,10001,10001)
subprocess.run(['xfs_quota','-x','-c',f'project -s -p {fixture} 100001',str(base)],check=True)
subprocess.run(['xfs_quota','-x','-c','limit -p bhard=2g ihard=100000 100001',str(base)],check=True)
print('Trusted nonbusiness recovery fixture identity/quota:PASS')
PY
