"""Export R2.1 local images and verify OCI index/manifest/config/archive identities."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
from docker_preflight import require_linux

from execution_fixture import ROOT, DATA, STAGE
OUT = DATA / 'artifacts'
COMPONENTS = ['api', 'gateway', 'provider-fixture', 'framework-probe']


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def export(component):
    tag = 'agentplatform-' + component + (':r2.1-local' if STAGE == 'r21' else ':r2c-local')
    archive, compressed = OUT / (component + '.tar'), OUT / (component + '.tar.gz')
    subprocess.run(['docker', 'image', 'save', '--output', str(archive), tag], check=True)
    actual = json.loads(subprocess.check_output(['docker', 'image', 'inspect', tag]))[0]
    with tarfile.open(archive) as stream:
        def blob(digest):
            raw = stream.extractfile('blobs/sha256/' + digest.split(':')[1]).read()
            assert 'sha256:' + hashlib.sha256(raw).hexdigest() == digest
            return json.loads(raw)
        index_digest = actual['Descriptor']['digest']
        index = blob(index_digest)
        descriptors = [m for m in index['manifests'] if m.get('platform', {}).get('architecture') == 'amd64' and m.get('platform', {}).get('os') == 'linux']
        assert len(descriptors) == 1, 'Expected exactly one linux/amd64 manifest'
        manifest_digest = descriptors[0]['digest']
        manifest = blob(manifest_digest)
        config_id = manifest['config']['digest']
        config = blob(config_id)
        exported = json.load(stream.extractfile('manifest.json'))
        assert len(exported) == 1
        assert hashlib.sha256(stream.extractfile(exported[0]['Config']).read()).hexdigest() == config_id.split(':')[1]
        source = config['config']['Labels']['org.opencontainers.image.revision']
        assert len(source) == 40 and all(c in '0123456789abcdef' for c in source), 'A committed source identity is required'
        assert config['architecture'] == 'amd64' and config['os'] == 'linux'
    with archive.open('rb') as src, gzip.open(compressed, 'wb', compresslevel=1) as target:
        shutil.copyfileobj(src, target)
    return {'component': component, 'tag': tag, 'source': source, 'localId': actual['Id'], 'indexDigest': index_digest,
            'manifestDigest': manifest_digest, 'configId': config_id, 'platform': 'linux/amd64', 'archive': str(compressed),
            'archiveBytes': compressed.stat().st_size, 'archiveSha256': sha(compressed), 'exportManifest': exported,
            'registry': 'LOCAL_ONLY_NOT_PUSHED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('components', nargs='*', choices=COMPONENTS, help='Default: all four acceptance images')
    args = parser.parse_args()
    require_linux()
    assert OUT.resolve().is_relative_to(ROOT.resolve()), 'Refusing redirected artifact directory'
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / 'images.json'
    records = json.loads(path.read_text(encoding='utf-8')) if path.exists() else []
    for component in args.components or COMPONENTS:
        row = export(component)
        records = [old for old in records if old['component'] != component] + [row]
        path.write_text(json.dumps(records, indent=2), encoding='utf-8')
        print(json.dumps({k: row[k] for k in ['component', 'source', 'configId', 'archiveSha256']}), flush=True)


if __name__ == '__main__':
    main()
