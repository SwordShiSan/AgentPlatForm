"""Freeze resolved external Maven jars using actual module trees and local artifact hashes."""
import hashlib
import argparse
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[2]
repository = pathlib.Path.home() / '.m2/repository'
artifacts = {}
for module in sorted((root / 'backend').glob('platform-*')):
    tree = module / 'target/dependency-tree.txt'
    for line in tree.read_text(encoding='utf-8').splitlines():
        match = re.search(r'([\w.-]+):([\w.-]+):(jar|pom):(?:(\w[\w.-]*):)?([\w.-]+):(compile|runtime|test|provided|system)', line)
        if not match:
            continue
        group, name, extension, classifier, version, scope = match.groups()
        if group == 'com.agentplatform':
            continue
        key = ':'.join(filter(None, [group, name, extension, classifier, version]))
        file = repository / group.replace('.', '/') / name / version / (name + '-' + version + ('-' + classifier if classifier else '') + '.' + extension)
        if not file.is_file():
            raise SystemExit('Missing resolved artifact: ' + key)
        record = artifacts.setdefault(key, {'coordinate': key, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest(), 'modules': {}})
        record['modules'][module.name] = scope
lock = {'schemaVersion': 1, 'scope': 'External compile/runtime/test artifacts; own modules bound by source SHA', 'artifacts': list(artifacts.values())}
path = root / 'deploy/maven-artifacts.lock.json'
parser = argparse.ArgumentParser()
parser.add_argument('--write', action='store_true')
args = parser.parse_args()
if args.write:
    path.write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8', newline='\n')
elif json.loads(path.read_text()) != lock:
    raise SystemExit('Resolved Maven artifact drift; do not overwrite accepted hashes')
print('Resolved Maven artifact hashes:', len(artifacts))
