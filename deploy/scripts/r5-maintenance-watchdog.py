"""Bound the closed R5 maintenance window; never roll back after reopening writes."""
from pathlib import Path
import fcntl,importlib.util,json,os,signal,sys,time

root=Path(sys.argv[1]).resolve(strict=True)
assert root.parent==Path('/srv/agentplatform/operations') and root.name.startswith('r5-release-')
deadline=time.time()+2700
while time.time()<deadline:
    if (root/'writes-open-intent.json').exists() or (root/'writes-open.json').exists():raise SystemExit(0)
    restored=[*root.glob('failure-*-restoration.json'),root/'restore-failure-r4-recovery.json']
    if any(path.exists() and json.loads(path.read_text()).get('status')=='PASS' for path in restored):raise SystemExit(0)
    time.sleep(5)
for path in root.glob('job-*.json'):
    row=json.loads(path.read_text());process=Path('/proc/'+str(row['pid']))
    if not process.exists():continue
    stat=(process/'stat').read_text().split()
    if stat[21]!=row['processStart'] or stat[2]=='Z':continue
    command=(process/'cmdline').read_bytes();assert str(root).encode() in command and b'r5-' in command
    os.kill(row['pid'],signal.SIGTERM)
spec=importlib.util.spec_from_file_location('release',root/'r5-release-control.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
with (Path('/srv/agentplatform/operations/platform-switch')/'switch.lock').open('a') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX)
    assert not (root/'writes-open-intent.json').exists()
    window=module.Window(root)
    result=window.rollback() if (root/'backup-r4.json').exists() else window.recover_preupgrade()
    (root/'watchdog-r4-restored.json').write_text(json.dumps(result,indent=2))
