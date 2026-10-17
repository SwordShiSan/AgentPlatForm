"""Capture/check the explicitly selected contract baseline; preserve historical locks."""
import argparse
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
FILES = ['contracts/openapi.yaml', 'contracts/events/platform-event.schema.json']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--baseline', choices=['r0', 'r1.1', 'r1.2', 'r1.3'], default='r1.3')
    args = parser.parse_args()
    # Discover generated contracts and HC-1 ports from the actual source layout.
    files = list(FILES)
    generated = ROOT / 'frontend/src/shared/api/generated'
    if not generated.is_dir():
        raise SystemExit('Missing generated API contracts')
    files += [p.relative_to(ROOT).as_posix() for p in generated.rglob('*') if p.is_file() and p.suffix in ['.ts', '.json']]
    for module in ['platform-types', 'platform-domain']:
        source = ROOT / 'backend' / module / 'src/main/java'
        files += [p.relative_to(ROOT).as_posix() for p in source.rglob('*.java') if 'harness' in p.parts]
    hashes = {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in sorted(set(files))}
    target = ROOT / 'contracts' / (args.baseline + '-baseline.lock.json')
    if args.write:
        target.write_text(json.dumps({'schemaVersion': 1, 'scope': args.baseline + ' implemented HTTP, event envelope, generated types and HC-1', 'sha256': hashes}, indent=2) + '\n', encoding='utf-8')
    elif json.loads(target.read_text())['sha256'] != hashes:
        raise SystemExit('Contract drift: update Plan/OpenSpec and reaccept before freezing.')
    print(f'{args.baseline} contract baseline: {len(hashes)} files verified')


if __name__ == '__main__':
    main()
