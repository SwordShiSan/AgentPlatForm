"""Export local candidates, retaining source/config/manifest/archive provenance."""
import gzip
import hashlib
import json
import os
import pathlib
import subprocess
import tarfile

root = pathlib.Path(__file__).resolve().parents[2]
if os.environ.get('GITHUB_ACTIONS') == 'true':
    raise SystemExit('Use local Docker export; GitHub builds are retired')
official = json.loads((root / 'deploy/runtime-images.lock.json').read_text())['images']
images = [
    ('PLATFORM_API_IMAGE', 'agentplatform-api:r0.1-local'),
    ('PLATFORM_GATEWAY_IMAGE', 'agentplatform-gateway:r0.1-local'),
    ('FRAMEWORK_PROBE_IMAGE', 'agentplatform-framework-probe:r0.2-local'),
    ('STORAGE_PROBE_IMAGE', 'agentplatform-storage-probe:r0.3-ci'),
    ('SANDBOX_IMAGE', 'agentplatform-sandbox:r0.4-ci'),
    *official.items(),
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
config_members = {}
manifest = None
expected_ids = {record['configId'] for record in records}
with tarfile.open(archive, mode='r|gz') as exported:
    for member in exported:
        if not member.isfile():
            continue
        if member.name == 'manifest.json':
            manifest = json.load(exported.extractfile(member))
        elif any(member.name.endswith(identity.removeprefix('sha256:') + suffix)
                 for identity in expected_ids for suffix in ('', '.json')):
            if member.size > 10 * 1024 * 1024:
                raise SystemExit('Unexpectedly large exported config')
            content = exported.extractfile(member).read()
            identity = 'sha256:' + hashlib.sha256(content).hexdigest()
            if identity in expected_ids:
                config_members[member.name] = identity
if not isinstance(manifest, list):
    raise SystemExit('Exported Docker manifest is missing')
manifest_ids = {config_members.get(entry['Config']) for entry in manifest}
if not expected_ids.issubset(manifest_ids):
    raise SystemExit('Exported manifest/config does not match inspected images')
for record in records:
    record['exportManifestConfig'] = next(entry['Config'] for entry in manifest
                                          if config_members.get(entry['Config']) == record['configId'])
    record['foundationLockSha256'] = hashlib.sha256((root / 'deploy/foundation.lock.json').read_bytes()).hexdigest()
(output / 'export-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
(output / 'source-images.json').write_text(json.dumps(records, indent=2) + '\n', encoding='utf-8')
(output / 'archive.sha256').write_text(digest.hexdigest() + '  candidate-images.tar.gz\n', encoding='utf-8')
(output / 'source-revision.txt').write_text(subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True), encoding='utf-8')
print('Exported local candidate images with exact source/config/archive identities; acceptance recorded separately')
