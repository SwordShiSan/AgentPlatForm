"""Recover matched R4 before opening writes when only the original unsent fixture is queued."""
from pathlib import Path
import fcntl, importlib.util, json, os, sys, time

root=Path(sys.argv[1]).resolve(strict=True)
assert root.parent==Path('/srv/agentplatform/operations') and root.name.startswith('r5-release-')
os.umask(0o077)
spec=importlib.util.spec_from_file_location('release',root/'r5-release-control.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
with (Path('/srv/agentplatform/operations/platform-switch')/'switch.lock').open('a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    assert not (root/'writes-open-intent.json').exists()
    window=module.Window(root)
    journal=json.loads((root/'smoke-journal.private.json').read_text())
    assert all(v['phase'] in ['FROZEN','ACKNOWLEDGED'] for v in journal.values())
    extraction=journal['extract']['response']['id']
    import uuid
    uuid.UUID(extraction)
    # No provider call, execution owner or side-effecting operation may be interrupted.
    for sql in ["SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL;",
                "SELECT count(*) FROM pf.heavy_runtime_slot WHERE kind IS NOT NULL;",
                "SELECT count(*) FROM pf.task WHERE status IN('QUEUED','RUNNING','WAITING_INPUT');",
                "SELECT count(*) FROM pf.preview_job WHERE state IN('QUEUED','RUNNING','BLOCKED');",
                "SELECT count(*) FROM pf.file_operation WHERE status NOT IN('COMMITTED','FAILED');",
                "SELECT count(*) FROM pf.workspace_file_lease WHERE status<>'FREE';",
                "SELECT count(*) FROM pf.workspace_environment_operation WHERE status NOT IN('COMMITTED','FAILED');"]:
        assert window.sql(sql)=='0', 'Active or unknown facts forbid this narrow recovery'
    assert window.sql("SELECT string_agg(id::text,',') FROM pf.document_extraction WHERE status IN('QUEUED','RUNNING','BLOCKED');")==extraction
    assert window.sql("SELECT status||':'||attempts FROM pf.document_extraction WHERE id='"+extraction+"';")=='QUEUED:0'
    window.receipt('topology-recovery-intent',{'originalExtraction':extraction,'attempts':0,'realWritesOpened':False,'matchedSnapshot':'backup-r4','reason':'Original bounded window retained; pending approval of production topology change'})
    # All inputs are retained; no row is rewritten to force an idle result.
    for name in ['gateway','api','worker','runner']:
        cid=window.cid(name);assert cid
        window.run(['docker','stop','-t','60',cid])
    assert not window.run(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-r5-app'])
    result=window.rollback()
    window.receipt('failure-topology-restoration',result)
    print(json.dumps(result))
