"""Transfer only isolated R63 test inputs to a protected cloud acceptance directory."""
from pathlib import Path
import hashlib
import json
import os
import secrets
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r63-local'
PRIVATE = DATA / 'fixture'
CLOUD = 'root@124.222.55.140'
KEY = 'D:/java/Dev/腾讯云服务器/lailihao_shanghai.pem'
KNOWN = 'C:/Users/laili/.ssh/known_hosts'
# Docker Desktop reports the local OCI index digest (2e3f...), while docker load
# on the amd64 cloud host selects the platform config digest (7469...). The latter
# is the executable image identity passed to docker run and verified after load.
LOCAL_INDEX_ID = 'sha256:2e3f9b1aeb9fc1588ce400810a154f1d25bad921b25c9e43eb66e4173e563cd3'
IMAGE = 'sha256:7469b1ebd598dcda62bf1797165c4220614bb7e84ab798dae3363ac46bd1b2b8'
ARCHIVE = DATA / 'r63-api-runner-candidate-2e3f9b1a.tar'

ssh = ['ssh', '-i', KEY, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
       '-o', 'UserKnownHostsFile=' + KNOWN, '-o', 'ConnectTimeout=10']
scp = ['scp', '-i', KEY, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
       '-o', 'UserKnownHostsFile=' + KNOWN, '-o', 'ConnectTimeout=10']

def run(args, timeout=300):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        failure = DATA / ('cloud-transfer-failure-' + str(len(list(DATA.glob('cloud-transfer-failure-*.private.log')))+1) + '.private.log')
        failure.write_text(result.stdout + result.stderr, encoding='utf-8')
        raise SystemExit('R63 protected cloud transfer step failed; inspect local private transfer log')
    return result.stdout.strip()

def local_docker(args):
    env = os.environ.copy()
    env['DOCKER_CONTEXT'] = 'desktop-linux'
    result = subprocess.run(['docker'] + args, cwd=ROOT, capture_output=True, text=True, timeout=60, env=env)
    if result.returncode:
        raise SystemExit('R63 local Docker identity check failed')
    return result.stdout.strip()

def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()

assert ARCHIVE.is_file() and sha(ARCHIVE)
archive_sha = sha(ARCHIVE)
local_image = local_docker(['image','inspect','--format',
    '{{.Id}}|{{index .Config.Labels "org.opencontainers.image.revision"}}',
    'agentplatform-api:r63-331a48cbb6a3'])
assert local_image == LOCAL_INDEX_ID + '|abc8dd11f11cfd7fd7195cb9a2012d69975b1d77'
with tarfile.open(ARCHIVE) as image_tar:
    manifests = json.load(image_tar.extractfile('manifest.json'))
    assert len(manifests) == 1
    manifest = manifests[0]
    assert 'agentplatform-api:r63-331a48cbb6a3' in (manifest.get('RepoTags') or [])
    config_bytes = image_tar.extractfile(manifest['Config']).read()
    assert 'sha256:' + hashlib.sha256(config_bytes).hexdigest() == IMAGE
    image_config = json.loads(config_bytes)
    assert image_config.get('architecture') == 'amd64'
    assert image_config.get('os') == 'linux'
    assert image_config.get('config',{}).get('Labels',{}).get('org.opencontainers.image.revision') == 'abc8dd11f11cfd7fd7195cb9a2012d69975b1d77'
runner_id = '761e3c048ee0fb85591e8b63094466b6c85536ac23034ecd6e22d5881d80e10f'
worker_id = '10b6d7a35691d2eea0c3f3316b9d5d6baf27fc2b814d44b6a79260c54e3d7054'
api_id = '12723516b93121f83fe23ea331a5c60f6298b6810df4a45330cd4fe63ab103a1'
gateway_id = '2c13a3b30ea372c2694686ae00077515af62df33954ec2af39f3160ed0488c27'

files = [
    (ARCHIVE, 'r63-api-runner.tar'),
    (PRIVATE / 'runner-3072-private.pem', 'runner-private.pem'),
    (PRIVATE / 'control-3072-public.pem', 'control-public.pem'),
    (PRIVATE / 'runner-db-password', 'runner-db-password'),
    (PRIVATE / 's3-access', 's3-access'),
    (PRIVATE / 's3-secret', 's3-secret'),
    (ROOT / 'deploy/probes/r63-cloud-runner-start.sh', 'r63-cloud-runner-start.sh'),
    (ROOT / 'deploy/probes/r63-cloud-runner-maintenance.sh', 'r63-cloud-runner-maintenance.sh'),
    (ROOT / 'deploy/probes/r63-cloud-r5-watchdog.sh', 'r63-cloud-r5-watchdog.sh'),
    (ROOT / 'deploy/probes/r63-cloud-runner-stage-verify.sh', 'r63-cloud-runner-stage-verify.sh'),
    (ROOT / 'deploy/probes/r63-cloud-runner-preflight.sh', 'r63-cloud-runner-preflight.sh'),
    (ROOT / 'deploy/probes/r63-cloud-tunnel-check.py', 'r63-cloud-tunnel-check.py'),
]
for source, name in files:
    assert source.is_file(), 'Missing R63 isolated transfer input: ' + name

local_record = DATA / 'cloud-runner-window.json'
root = ('/srv/agentplatform/acceptance/r63-signed-20261109-' + secrets.token_hex(4))
refresh_only = False
if local_record.exists():
    previous = json.loads(local_record.read_text(encoding='utf-8'))
    root = previous['root']
plan = {'root': root, 'candidateApiImageId': IMAGE, 'runnerArchiveSha256': archive_sha,
        'candidateRevision': 'abc8dd11f11cfd7fd7195cb9a2012d69975b1d77', 'database': 'r63_2fa963f96f7f',
        'rendererImageId': 'sha256:502ba213ee70a1ed10e6a01aec519fb64c757a0c8201c774553c49d58f646545',
        'bundle': 'asset-parser-tika3.3.3-v2', 'policyHash': '99b70afda5244e8c7eb591555a63ab43d6f4b9d786345276fe90e6b29f8e317a',
        'r5AppContainers': {'runner': runner_id, 'worker': worker_id, 'api': api_id, 'gateway': gateway_id},
        'testSupplierCalls': 0, 'maintenanceSecondsMax': 900}
if local_record.exists():
    previous = json.loads(local_record.read_text(encoding='utf-8'))
    for key in ['candidateApiImageId','runnerArchiveSha256','database','rendererImageId','policyHash','r5AppContainers']:
        if previous.get(key) != plan.get(key):
            raise SystemExit('Refusing to reuse the cloud window with changed candidate or R5 identity')
    plan = previous
    root = plan['root']
    remote = root + '/r63-cloud-runner-stage-verify.sh'
    staged = run(ssh + [CLOUD, 'bash ' + remote + ' ' + root + ' ' + archive_sha], timeout=60)
    if not staged.endswith('R5=UNCHANGED'):
        raise SystemExit('Previously staged cloud window changed; inspect it before any transfer')
    refresh_only = True
else:
    local_record.write_text(json.dumps(plan, indent=2) + '\n', encoding='utf-8')
    try:
        import os
        os.chmod(local_record, 0o600)
    except OSError:
        pass

if not refresh_only:
    run(ssh + [CLOUD, 'install -d -m 700 ' + root])
refresh_names = {'r63-cloud-runner-start.sh','r63-cloud-runner-maintenance.sh',
                 'r63-cloud-r5-watchdog.sh','r63-cloud-runner-stage-verify.sh',
                 'r63-cloud-runner-preflight.sh','r63-cloud-tunnel-check.py'}
for source, name in files:
    if refresh_only and name not in refresh_names:
        continue
    run(scp + [str(source), CLOUD + ':' + root + '/' + name], timeout=600)
remote = root + '/r63-cloud-runner-stage-verify.sh'
result = run(ssh + [CLOUD, 'bash ' + remote + ' ' + root + ' ' + archive_sha], timeout=60)
print(json.dumps({'status': 'STAGED_NOT_PAUSED', 'root': root, 'archiveSha256': archive_sha,
                  'candidateImageId': IMAGE, 'verified': result.endswith('R5=UNCHANGED')}, sort_keys=True))
