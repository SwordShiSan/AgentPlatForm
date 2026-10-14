"""Export only CI's accepted images, retaining digest/config/archive provenance."""
import gzip
import hashlib
import json
import os
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[2]
if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('CI immutable export only')
images = [
    ('PLATFORM_API_IMAGE', 'agentplatform-api:r0.1-local'),
    ('PLATFORM_GATEWAY_IMAGE', 'agentplatform-gateway:r0.1-local'),
    ('FRAMEWORK_PROBE_IMAGE', 'agentplatform-framework-probe:r0.2-local'),
    ('STORAGE_PROBE_IMAGE', 'agentplatform-storage-probe:r0.3-ci'),
    ('SANDBOX_IMAGE', 'agentplatform-sandbox:r0.4-ci'),
    ('POSTGRES_IMAGE', 'pgvector/pgvector:0.8.1-pg17-trixie'),
    ('REDIS_IMAGE', 'redis:7.4-alpine'),
    ('RABBITMQ_IMAGE', 'rabbitmq:4.3.6-management'),
    ('SEAWEEDFS_IMAGE', 'chrislusf/seaweedfs:4.48'),
]
output = root / 'evidence/r0.4/images'
output.mkdir(parents=True, exist_ok=True)
records = []
for variable, image in images:
    identity = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image]))[0]
    assert identity['Architecture'] == 'amd64' and identity['Os'] == 'linux'
    records.append({'variable': variable, 'tag': image, 'configId': identity['Id'],
                    'repoDigests': identity['RepoDigests'], 'architecture': 'linux/amd64'})
archive = output / 'candidate-images.tar.gz'
process = subprocess.Popen(['docker', 'save'] + [image for _, image in images], stdout=subprocess.PIPE)
digest = hashlib.sha256()
with gzip.open(archive, 'wb', compresslevel=1) as stream:
    for chunk in iter(lambda: process.stdout.read(1024 * 1024), b''):
        stream.write(chunk)
if process.wait():
    raise SystemExit('Docker export failed')
with archive.open('rb') as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
(output / 'source-images.json').write_text(json.dumps(records, indent=2) + '\n', encoding='utf-8')
(output / 'archive.sha256').write_text(digest.hexdigest() + '  candidate-images.tar.gz\n', encoding='utf-8')
(output / 'source-revision.txt').write_text(subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True), encoding='utf-8')
print('Exported same audited images with exact source/config/archive identities')
