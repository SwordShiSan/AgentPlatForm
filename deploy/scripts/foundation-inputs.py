"""Fetch frozen inputs before builds; hashes reject replacement archives/packages."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCK = ROOT / 'deploy/foundation.lock.json'


def fetch_inputs(lock, destination):
    destination.mkdir(parents=True, exist_ok=True)
    for name, item in lock.get('archives', {}).items():
        if pathlib.Path(name).name != name or not re.fullmatch(r'[a-f0-9]{64}', item['sha256']):
            raise ValueError('Invalid archive lock')
        target = destination / name
        if not target.exists():
            temporary = target.with_suffix(target.suffix + '.download')
            with urllib.request.urlopen(item['url'], timeout=120) as response, temporary.open('wb') as stream:
                while chunk := response.read(1024 * 1024):
                    stream.write(chunk)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != item['sha256']:
                temporary.unlink()
                raise ValueError('Downloaded input hash mismatch: ' + name)
            temporary.replace(target)
        if hashlib.sha256(target.read_bytes()).hexdigest() != item['sha256']:
            raise ValueError('Cached input hash mismatch: ' + name)


def verify_dockerfiles(lock):
    allowed = {item['reference'] for item in lock['images'].values()}
    for dockerfile in (ROOT / 'deploy').glob('*.Dockerfile'):
        stages = set()
        for line in dockerfile.read_text(encoding='utf-8').splitlines():
            if not line.startswith('FROM '):
                continue
            parts = line.split()
            if parts[1] not in allowed and parts[1] not in stages:
                raise ValueError('Unfrozen base in ' + dockerfile.name + ': ' + parts[1])
            if len(parts) == 4 and parts[2].upper() == 'AS':
                stages.add(parts[3])
    for item in lock['images'].values():
        if item['platform'] != 'linux/amd64' or not re.search(r'@sha256:[a-f0-9]{64}$', item['reference']):
            raise ValueError('Mutable or non-amd64 base')
    for source in lock.get('sources', {}).values():
        if not re.fullmatch(r'[a-f0-9]{40}', source['commit']):
            raise ValueError('Source commit must be exact')
        for name, expected in source.get('patchFiles', {}).items():
            path = (ROOT / name).resolve()
            if not path.is_relative_to(ROOT) or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError('Security patch drift: ' + name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fetch', action='store_true')
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    verify_dockerfiles(lock)
    if args.fetch:
        fetch_inputs(lock, ROOT / '.cache/foundation')
    print('Foundation input identities verified; acceptance status unchanged')


if __name__ == '__main__':
    main()
