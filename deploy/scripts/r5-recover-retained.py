"""Complete a receipted pre-write R4 restoration after its loop auto-detached.

Does not copy or replace data. Requires the already restored image to match the
verified pre-upgrade SHA256 and the original loop device to be unassigned.
"""
from pathlib import Path
import fcntl,hashlib,importlib.util,json,re,subprocess,sys,time,traceback

root=Path(sys.argv[1]).resolve(strict=True)
assert root.parent==Path('/srv/agentplatform/operations') and root.name.startswith('r5-release-')
assert not (root/'writes-open-intent.json').exists()
spec=importlib.util.spec_from_file_location('release',root/'r5-release-control.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
try:
    with (m.ROOT/'operations/platform-switch/switch.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);window=m.Window(root)
        assert not window.run(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-r5-app'])
        proof=json.loads((root/'backup-r4.json').read_text());assert proof['status']=='PASS' and proof['migration']==18
        image=m.ROOT/'quota/workspaces.xfs';assert image.stat().st_size==proof['rawXfsBytes'] and window.hash(image)==proof['rawXfsSha256']
        device=window.stage['loop'];assert re.fullmatch('/dev/loop[0-9]+',device)
        assert not window.run(['losetup','-j',str(image)])
        assert not Path('/sys/block/'+Path(device).name+'/loop/backing_file').exists()
        assert subprocess.run(['findmnt','--mountpoint',str(m.ROOT/'workspaces')],capture_output=True).returncode!=0
        window.run(['losetup',device,str(image)]);window.run(['mount','-o','prjquota,noatime',device,str(m.ROOT/'workspaces')])
        assert window.run(['findmnt','-n','-o','UUID','--target',str(m.ROOT/'workspaces')])==proof['uuid']
        window.start_dependencies()
        window.run(['python3',str(root/'r5-database-evidence.py'),window.by['postgres']['Id'],'--expected-migration','18','--output',str(root/'recovered-r4-database.json'),'--compare',str(root/'backup-r4/database.json')])
        for name in ['runner','api','worker','gateway']:window.run(['docker','start',window.by[name]['Id']])
        for _ in range(100):
            try:window.health();break
            except Exception:time.sleep(2)
        else:raise RuntimeError('Original R4 did not become ready')
        for original in window.rows:
            current=json.loads(window.run(['docker','inspect',original['Id']]))[0]
            assert current['State']['Running'] and not current['State']['OOMKilled'] and current['Image']==original['Image'] and current['HostConfig']['RestartPolicy']==original['HostConfig']['RestartPolicy']
        result={'status':'PASS','sameR4ContainersImagesPolicies':True,'matchedPreUpgradeDatabaseFingerprints':'MATCH','workspaceImageSha256':proof['rawXfsSha256'],'originalUuidUnchanged':True,'reattachedOriginalLoop':device,'supplierCalls':0,'time':time.time()}
        (root/'failure-start-restoration.json').write_text(json.dumps(result,indent=2))
        if (root/'restoration-required').exists():(root/'restoration-required').rename(root/'resolved-restoration-required')
        print(json.dumps(result))
except BaseException:
    (root/'retained-recovery-failure.private.log').write_text(traceback.format_exc());raise
