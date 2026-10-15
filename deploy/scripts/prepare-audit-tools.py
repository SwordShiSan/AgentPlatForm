"""Resolve official stable tools once; verify archives before extracting locally."""
import hashlib
import json
import pathlib
import re
import subprocess
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
CACHE = ROOT / '.tools' / 'audit'
LOCK = ROOT / 'deploy' / 'audit-tools.lock.json'


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'AgentPlatForm-R0.4'})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    if LOCK.exists():
        raise SystemExit('Lock already exists; refuse version drift. Use pinned archives.')
    result = {'schemaVersion': 1, 'tools': {}, 'actions': {}}
    for name, repository in [('syft', 'anchore/syft'), ('grype', 'anchore/grype')]:
        with urllib.request.urlopen(f'https://github.com/{repository}/releases/latest', timeout=120) as response:
            release_url = response.geturl()
        version = release_url.rsplit('/', 1)[-1]
        assert re.fullmatch(r'v\d+\.\d+\.\d+', version), version
        base = f'https://github.com/{repository}/releases/download/{version}/'
        prefix = f'{name}_{version[1:]}'
        checksum_text = fetch(base + prefix + '_checksums.txt').decode()
        entries = []
        for suffix in ['windows_amd64.zip', 'linux_amd64.tar.gz']:
            asset = {'name': prefix + '_' + suffix, 'browser_download_url': base + prefix + '_' + suffix}
            expected = next(line.split()[0] for line in checksum_text.splitlines()
                            if line.split()[-1].lstrip('*') == asset['name'])
            target = CACHE / asset['name']
            data = target.read_bytes() if target.exists() else fetch(asset['browser_download_url'])
            assert hashlib.sha256(data).hexdigest() == expected, asset['name']
            target.write_bytes(data)
            entries.append({'name': asset['name'], 'url': asset['browser_download_url'], 'sha256': expected})
            if suffix.endswith('.zip'):
                with zipfile.ZipFile(target) as archive:
                    binary = archive.read(name + '.exe')
                (CACHE / (name + '.exe')).write_bytes(binary)
        result['tools'][name] = {'version': version, 'source': release_url, 'license': 'Apache-2.0', 'archives': entries}
    with urllib.request.urlopen('https://github.com/CycloneDX/cyclonedx-maven-plugin/releases/latest', timeout=120) as response:
        release_url = response.geturl()
    version = release_url.rsplit('-', 1)[-1]
    assert re.fullmatch(r'\d+\.\d+\.\d+', version)
    result['tools']['cyclonedx-maven'] = {'version': version, 'source': release_url, 'license': 'Apache-2.0'}
    for name, major in [('checkout', 'v5'), ('setup-java', 'v5'), ('setup-node', 'v5'), ('upload-artifact', 'v4')]:
        lines = subprocess.check_output(['git', 'ls-remote', f'https://github.com/actions/{name}.git', f'refs/tags/{major}', f'refs/tags/{major}^{{}}'], text=True).splitlines()
        selected = next((line for line in lines if line.endswith('^{}')), lines[0])
        sha = selected.split()[0]
        assert re.fullmatch('[0-9a-f]{40}', sha)
        result['actions'][name] = {'major': major, 'sha': sha, 'source': f'https://github.com/actions/{name}'}
    LOCK.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
