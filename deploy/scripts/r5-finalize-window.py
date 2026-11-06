"""One explicitly approved bounded continuation after a completed R4 watchdog recovery."""
from pathlib import Path
import fcntl, hashlib, importlib.util, json, os, shutil, subprocess, sys, time, traceback

root=Path(sys.argv[1]).resolve(strict=True);main=sys.argv[2]
assert root.parent==Path('/srv/agentplatform/operations') and root.name.startswith('r5-release-')
assert len(main)==40 and all(c in '0123456789abcdef' for c in main)
os.umask(0o077)
assert not (root/'completion-extension.json').exists() and not (root/'writes-open-intent.json').exists()
assert json.loads((root/'watchdog-r4-restored.json').read_text())['status']=='PASS'
for gate in ['smoke','observation','restore','rollback-drill','redeploy','final-smoke','final-observation']:
    assert json.loads((root/(gate+'.json')).read_text())['status']=='PASS'
spec=importlib.util.spec_from_file_location('release',root/'r5-release-control.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);w=module.Window(root)
guard_passed=False
try:
    with (Path('/srv/agentplatform/operations/platform-switch')/'switch.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        # Current source data must be preserved if anything besides known
        # authentication/background scan facts changed during the fallback.
        w.stop_apps('r4')
        proof=root/'completion-r4-database.json'
        w.run(['python3',str(root/'r5-database-evidence.py'),w.by['postgres']['Id'],'--expected-migration','18','--output',str(proof)])
        before=json.loads((root/'backup-r4/database.json').read_text());after=json.loads(proof.read_text())
        changed={name for name in before['tables'] if before['tables'][name]!=after['tables'].get(name)}
        allowed={'pf.auth_rate','pf.auth_session','pf.file_inbox','pf.file_operation','pf.file_outbox','pf.file_runner_command','pf.file_runner_receipt','pf.file_scan_directory','pf.workspace_file_lease','pf.workspace_runner_instance'}
        if 'pf.file_entry' in changed:
            w.run(['python3',str(root/'r5-file-projection-check.py'),str(root)])
            projection=json.loads((root/'completion-file-projection.json').read_text())
            assert projection['sameIds'] and set(projection['changedColumns'] or [])<={'last_scan_epoch'},'File identity/version/content changed; retain current data'
            allowed.add('pf.file_entry')
        assert before['metadata']==after['metadata'] and set(before['tables'])==set(after['tables']) and changed<=allowed,'Unexpected business writes; retain current R4 data'
        stamp=json.loads((root/'backup-r4.json').read_text())['recordedAt']
        assert w.sql("SELECT count(*) FROM pf.file_operation WHERE created_at>to_timestamp("+str(stamp)+") AND kind<>'SCAN';")=='0'
        w.run(['python3',str(root/'workspace-manifest.py'),'verify','/srv/agentplatform/workspaces',str(root/'backup-r4/workspace-files.json')])
        guard_passed=True
        w.receipt('completion-business-guard',{'status':'PASS','noNewBusinessWrites':True,'changedTables':sorted(changed),'fingerprintSha256':hashlib.sha256(proof.read_bytes()).hexdigest(),'supplierCalls':0})
        log=(root/'completion-watchdog.private.log').open('xb')
        watchdog=subprocess.Popen(['python3',str(root/'r5-maintenance-watchdog.py'),str(root)],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
        w.receipt('completion-extension',{'status':'LAUNCHED','explicitUserApproval':'One bounded continuation','deadline':time.time()+2700,'watchdogPid':watchdog.pid,'supplierCalls':0})
        w.stop_dependencies();w.restore_snapshot('r5');w.activate('completion-redeploy')
    archive=root/'completion-history'/str(time.time_ns());archive.mkdir(parents=True,mode=0o700)
    for name in ['final-smoke.json','final-observation.json','final-observation-intent.json','final-observation-samples.jsonl','job-final-smoke.json','job-final-smoke.private.log','job-final-observe.json','job-final-observe.private.log']:
        if (root/name).exists():(root/name).rename(archive/name)
    for command in [
        ['python3',str(root/'r5-production-smoke.py'),str(root),'--final'],
        ['python3',str(root/'r5-production-observe.py'),str(root),'--final'],
        ['python3',str(root/'r5-open-writes.py'),str(root),'--main',main]]:
        subprocess.run(command,check=True,timeout=360)
    w.receipt('completion',{'status':'PASS','supplierCalls':0,'realWritesOpened':True})
except BaseException:
    (root/'completion-failure.private.log').write_text(traceback.format_exc())
    if not (root/'writes-open-intent.json').exists():
        if guard_passed:w.receipt('failure-completion-restoration',w.rollback())
        else:
            # Do not replace current data when the preservation guard refused.
            w.start_dependencies()
            for name in ['runner','api','worker','gateway']:w.run(['docker','start',w.by[name]['Id']])
            (root/'completion-data-preserved.json').write_text(json.dumps({'status':'CURRENT_R4_DATA_RETAINED','recordedAt':time.time()}))
    raise
