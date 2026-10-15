"""Export local candidates, retaining source/config/manifest/archive provenance."""
import argparse
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
parser = argparse.ArgumentParser()
parser.add_argument('--reuse-archive', action='store_true')
args = parser.parse_args()
output = root / 'evidence/r0.4/images'
output.mkdir(parents=True, exist_ok=True)
records = []
inspected = []
for variable, image in images:
    identity = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image]))[0]
    assert identity['Architecture'] == 'amd64' and identity['Os'] == 'linux'
    inspected.append(identity)
    records.append({'variable': variable, 'tag': image, 'configId': identity['Id'],
                    'repoDigests': identity['RepoDigests'], 'architecture': 'linux/amd64'})
archive = output / 'candidate-images.tar.gz'
if not args.reuse_archive:
    process = subprocess.Popen(['docker', 'save'] + [image for _, image in images], stdout=subprocess.PIPE)
digest = hashlib.sha256()
if not args.reuse_archive:
    with gzip.open(archive, 'wb', compresslevel=1) as stream:
        for chunk in iter(lambda: process.stdout.read(1024 * 1024), b''):
            stream.write(chunk)
    if process.wait():
        raise SystemExit('Docker export failed')
with archive.open('rb') as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
configs = {}
manifest = None
with tarfile.open(archive, mode='r|gz') as exported:
    for member in exported:
        if not member.isfile():
            continue
        if member.name == 'manifest.json':
            manifest = json.load(exported.extractfile(member))
        elif member.size <= 10 * 1024 * 1024:
            content = exported.extractfile(member).read()
            try:
                value = json.loads(content)
            except (ValueError, UnicodeDecodeError):
                continue
            if isinstance(value, dict) and value.get('architecture') == 'amd64' and value.get('os') == 'linux' and 'rootfs' in value and 'config' in value:
                configs[member.name] = ('sha256:' + hashlib.sha256(content).hexdigest(), value)
if not isinstance(manifest, list):
    raise SystemExit('Exported Docker manifest is missing')
keys = ['Env', 'Cmd', 'Entrypoint', 'User', 'WorkingDir', 'Labels', 'Healthcheck', 'Volumes', 'ExposedPorts']
def normalized_config(config, key):
    value = config.get(key)
    if key in ['Labels', 'Healthcheck', 'Volumes', 'ExposedPorts']:
        return value or {}
    if key in ['Env', 'Cmd', 'Entrypoint']:
        return value or []
    return value or ''
for record, identity in zip(records, inspected):
    matches = []
    for entry in manifest:
        config = configs.get(entry['Config'])
        if not config:
            continue
        config_id, value = config
        if value['rootfs']['diff_ids'] != identity['RootFS']['Layers']:
            continue
        if any(normalized_config(value['config'], key) != normalized_config(identity['Config'], key) for key in keys):
            continue
        matches.append((entry['Config'], config_id))
    matches = list(set(matches))
    if len(matches) != 1:
        raise SystemExit('Exported config is missing or ambiguous for ' + record['tag'])
    record['localImageId'] = identity['Id']
    record['exportManifestConfig'], record['configId'] = matches[0]
    record['runtimeImagesLockSha256'] = hashlib.sha256((root / 'deploy/runtime-images.lock.json').read_bytes()).hexdigest()
(output / 'export-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
(output / 'source-images.json').write_text(json.dumps(records, indent=2) + '\n', encoding='utf-8')
(output / 'archive.sha256').write_text(digest.hexdigest() + '  candidate-images.tar.gz\n', encoding='utf-8')
(output / 'source-revision.txt').write_text(subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True), encoding='utf-8')
print('Exported local candidate images with exact source/config/archive identities; acceptance recorded separately')
