"""Actual Linux negative startup tests while all new/legacy service containers are stopped."""
import json,pathlib,subprocess
root=pathlib.Path('/srv/agentplatform');manifest=root/'deploy/production-inputs.sha256';uuid=root/'quota/filesystem.uuid'
def stopped():
    for project in ['agentplatform-app','agentplatform-env','agentplatform-r04-restore-app','agentplatform-r04-restore-env']:
        assert not subprocess.check_output(['docker','ps','-q','--filter','label=com.docker.compose.project='+project]).strip()
def denied():
    result=subprocess.run(['bash',str(root/'deploy/scripts/production-stack.sh'),'start'],capture_output=True,text=True)
    assert result.returncode!=0,'Invalid production input admitted';stopped()
stopped();saved=manifest.read_bytes();original=uuid.read_bytes();rows=[]
try:
    manifest.write_bytes(saved+b'0'*64+b'  nginx.production.conf\n');denied();rows.append('bad-config-hash-denied')
finally:manifest.write_bytes(saved)
try:
    uuid.write_text('00000000-0000-0000-0000-000000000000\n');denied();rows.append('wrong-XFS-identity-denied')
finally:uuid.write_bytes(original)
subprocess.run(['umount',str(root/'workspaces')],check=True)
try:
    denied();assert not list((root/'workspaces').iterdir());rows.append('missing-mount-denied-underlay-empty')
finally:subprocess.run(['mount','-o','loop,prjquota,noatime',str(root/'quota/workspaces.xfs'),str(root/'workspaces')],check=True)
assert uuid.read_bytes()==original
record={'status':'PASS','cases':rows,'noServicesStarted':True,'originalUuidUnchanged':True}
(root/'reports/production-guards.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record))
