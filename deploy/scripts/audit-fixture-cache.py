"""Consume a frozen public upstream fixture artifact, with source fingerprint checks."""
import concurrent.futures
import hashlib
import json
import pathlib
import re
import tarfile
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]


def extract_source(bundle, work, prefix, expected_links):
    members = bundle.getmembers()
    links = {}
    for member in members:
        if (not member.name.startswith(prefix + '/') and member.name != prefix
                or '..' in pathlib.PurePosixPath(member.name).parts or '\\' in member.name):
            raise ValueError('Unexpected source archive path')
        if member.islnk():
            raise ValueError('Unexpected source hardlink')
        if member.issym():
            links[member.name[len(prefix) + 1:]] = member.linkname
    if links != expected_links:
        raise ValueError('Frozen source fixture links changed')

    def regular_filter(member, destination):
        return None if member.issym() else tarfile.data_filter(member, destination)

    # Do not let any fixture link redirect extraction. Restore exact trusted
    # test links only after regular files, including deliberately dangling links.
    bundle.extractall(work, filter=regular_filter)
    root = work / prefix
    for name, target in sorted(links.items()):
        path = root / name
        if not path.parent.resolve().is_relative_to(root.resolve()):
            raise ValueError('Fixture link parent escapes source root')
        path.symlink_to(target)


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


class RegistryRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        if urllib.parse.urlparse(url).scheme != 'https':
            raise ValueError('Fixture download redirect must retain TLS')
        redirected = super().redirect_request(request, fp, code, message, headers, url)
        if urllib.parse.urlparse(request.full_url).hostname != urllib.parse.urlparse(url).hostname:
            redirected.remove_header('Authorization')
        return redirected


def safe_path(root, name):
    relative = pathlib.PurePosixPath(name)
    if relative.is_absolute() or '\\' in name or '..' in relative.parts:
        raise ValueError('Unsafe fixture path')
    result = (root / name).resolve()
    if not result.is_relative_to(root.resolve()):
        raise ValueError('Fixture path escapes source root')
    return result


def unpack(blob, title, root):
    target = safe_path(root, title)
    with tarfile.open(blob) as archive:
        members = archive.getmembers()
        names = [m.name.rstrip('/') for m in members]
        if all(n == title or n.startswith(title + '/') for n in names):
            destination = root
        elif all(n == target.name or n.startswith(target.name + '/') for n in names):
            destination = target.parent
        elif target.suffix:
            raise ValueError('Unexpected file layer layout')
        else:
            destination = target
        destination.mkdir(parents=True, exist_ok=True)
        for member in members:
            safe_path(destination, member.name)
        archive.extractall(destination, filter='data')


def restore_layer(blob, layer, root):
    annotations = layer['annotations']
    title = annotations['org.opencontainers.image.title']
    if annotations.get('io.deis.oras.content.unpack') == 'true':
        unpack(blob, title, root)
    elif title == '.tmp/cache_paths.json' and 'io.deis.oras.content.unpack' not in annotations:
        target = safe_path(root, title)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(blob.read_bytes())
    else:
        raise ValueError('Unsupported frozen fixture layer format')


def verify_fingerprints(root, manifest, paths, source_hashes=None):
    if paths['digest'] != manifest['annotations']['fingerprint']:
        raise ValueError('Fixture manifest fingerprint mismatch')
    titles = {layer['annotations']['org.opencontainers.image.title'] for layer in manifest['layers']}
    checks = []
    for entry in paths['paths']:
        if entry['path'] not in titles:
            raise ValueError('Unbound fixture source path')
        parent = safe_path(root, entry['path']).parent
        if not parent.is_relative_to(root.resolve()):
            raise ValueError('Fixture output must be inside source root')
        if not entry['input']:
            raise ValueError('Missing fixture source fingerprints')
        for name, expected in entry['input'].items():
            if not re.fullmatch('[a-f0-9]{64}', expected):
                raise ValueError('Invalid source fingerprint')
            # A generator can emit nested output such as classifiers/bin while
            # fingerprinting ./config.yaml relative to testdata, not classifiers.
            origins = [parent]
            while origins[-1] != root.resolve():
                origins.append(origins[-1].parent)
            candidates = {safe_path(origin, name) for origin in origins}
            matches = [p for p in candidates if p.is_relative_to(root.resolve()) and p.is_file() and sha256(p) == expected
                       and (source_hashes is None or source_hashes.get(p.relative_to(root).as_posix()) == expected)]
            if not matches:
                raise ValueError('Fixture source changed or absent: ' + entry['path'] + '/' + name)
            if len(matches) != 1:
                raise ValueError('Ambiguous fixture source fingerprint: ' + name)
            checks.append({'path': matches[0].relative_to(root).as_posix(), 'sha256': expected})
    return checks


def restore(root, output):
    lock_path = ROOT / 'deploy/security/syft-fixture-cache.json'
    data = lock_path.read_bytes()
    foundation = json.loads((ROOT / 'deploy/foundation.lock.json').read_text())
    binding = foundation['sources']['syft']['fixtureCache']
    if hashlib.sha256(data).hexdigest() != binding['manifestSha256']:
        raise ValueError('Frozen fixture manifest changed')
    manifest = json.loads(data)
    repo = 'anchore/oss-cache/syft-test-fixture-cache'
    opener = urllib.request.build_opener(RegistryRedirect())
    query = urllib.parse.urlencode({'service': 'ghcr.io', 'scope': 'repository:' + repo + ':pull'})
    with opener.open('https://ghcr.io/token?' + query, timeout=60) as response:
        token = json.load(response)['token']
    request = urllib.request.Request('https://ghcr.io/v2/' + repo + '/manifests/' + binding['reference'].split('@')[1],
                                     headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.oci.image.manifest.v1+json'})
    with opener.open(request, timeout=60) as response:
        if hashlib.sha256(response.read()).hexdigest() != binding['manifestSha256']:
            raise ValueError('Registry fixture manifest mismatch')
    source_hashes = {path.relative_to(root).as_posix(): sha256(path) for path in root.rglob('*') if path.is_file()}
    cache = ROOT / '.cache/audit-fixture-blobs'
    cache.mkdir(parents=True, exist_ok=True)

    def fetch(layer):
        digest = layer['digest']
        if not re.fullmatch('sha256:[a-f0-9]{64}', digest):
            raise ValueError('Invalid fixture layer identity')
        path = cache / digest[7:]
        if not path.exists():
            request = urllib.request.Request('https://ghcr.io/v2/' + repo + '/blobs/' + digest,
                                             headers={'Authorization': 'Bearer ' + token})
            temporary = path.with_suffix('.download')
            with opener.open(request, timeout=120) as response, temporary.open('wb') as stream:
                while chunk := response.read(1024 * 1024):
                    stream.write(chunk)
            if temporary.stat().st_size != layer['size'] or sha256(temporary) != digest[7:]:
                raise ValueError('Downloaded fixture layer hash mismatch')
            temporary.replace(path)
        if path.stat().st_size != layer['size'] or sha256(path) != digest[7:]:
            raise ValueError('Cached fixture layer hash mismatch')
        return path

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        blobs = list(pool.map(fetch, manifest['layers']))
    for blob, layer in zip(blobs, manifest['layers']):
        restore_layer(blob, layer, root)
    paths = json.loads((root / '.tmp/cache_paths.json').read_text())
    checks = verify_fingerprints(root, manifest, paths, source_hashes)
    output.write_text(json.dumps({'reference': binding['reference'], 'manifestSha256': binding['manifestSha256'],
                                  'layers': len(blobs), 'sourceInputs': checks}, indent=2) + '\n')
    print('Frozen upstream fixture layers and source fingerprints verified:', len(blobs), len(checks))
