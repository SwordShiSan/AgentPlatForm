"""Fail closed before production Compose starts; no secret values printed."""
import hashlib,json,pathlib,shutil,subprocess

root=pathlib.Path('/srv/agentplatform')
assert (root/'operations/legacy-paused').is_file(),'Legacy pause marker required'
for name in ['nginx','ai-agent-app','big-market-app','mysql','redis','rabbitmq','ai-agent-pgvector','zookeeper','ai-agent_big-market-front-app','ai-agent-xxl-job-admin']:
    state=json.loads(subprocess.check_output(['docker','inspect',name]))[0]
    assert not state['State']['Running'],'Legacy container still running: '+name
assert shutil.disk_usage(root).free>=5*1024**3,'Root safety reserve below5GiB'
memory=int(next(line.split()[1] for line in pathlib.Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')))
assert memory>=1152*1024,'Insufficient available memory for baseline admission'
subprocess.run(['python3',str(root/'deploy/probes/sandbox_probe.py'),'verify-mount'],check=True)
manifest=root/'deploy/production-inputs.sha256'
assert manifest.is_file(),'Missing reviewed deployment input manifest'
subprocess.run(['sha256sum','-c',str(manifest)],cwd=root/'deploy',check=True)
records=json.loads((root/'source-images.json').read_text())
images=dict(line.split('=',1) for line in (root/'deploy/images.env').read_text().splitlines() if '=' in line)
assert len(records)==9
for record in records:
    identity=record['configId'];actual=json.loads(subprocess.check_output(['docker','image','inspect',identity]))[0]
    assert actual['Id']==identity and actual['Architecture']=='amd64' and actual['Os']=='linux'
    if record.get('variable'):assert images[record['variable']]==identity
print('Production capacity,mount,inputs and9 immutable images:PASS')
