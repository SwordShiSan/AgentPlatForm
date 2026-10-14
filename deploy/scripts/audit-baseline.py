"""Create SBOM/vulnerability evidence with locked tools; fail closed on missing scans."""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tool-dir', type=pathlib.Path, default=ROOT / '.tools' / 'audit')
    parser.add_argument('--target', action='append', required=True, help='LABEL=Syft source, e.g. api=docker-archive:...')
    parser.add_argument('--output', type=pathlib.Path, default=ROOT / 'evidence' / 'r0.4' / 'audit')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    lock = json.loads((ROOT / 'deploy' / 'audit-tools.lock.json').read_text())
    suffix = '.exe' if os.name == 'nt' else ''
    executables = {}
    for tool in ['syft', 'grype']:
        binary = args.tool_dir / (tool + suffix)
        version = subprocess.check_output([str(binary), 'version'], text=True)
        expected = lock['tools'][tool]['version'].lstrip('v')
        if expected not in version:
            raise SystemExit(f'Unexpected {tool} version')
        (args.output / (tool + '-version.txt')).write_text(version, encoding='utf-8')
        executables[tool] = str(binary)
    status = subprocess.run([executables['grype'], 'db', 'status', '-o', 'json'], capture_output=True)
    (args.output / 'grype-db-status.json').write_bytes(status.stdout)
    if status.returncode:
        raise SystemExit('No valid vulnerability database; audit NOT_RUN')
    results = []
    for target in args.target:
        label, source = target.split('=', 1)
        if not label or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in label):
            raise SystemExit('Unsafe evidence label')
        syft_json = args.output / (label + '.syft.json')
        bom = args.output / (label + '.cdx.json')
        subprocess.run([executables['syft'], source, '-o', 'syft-json=' + str(syft_json), '-o', 'cyclonedx-json=' + str(bom)], check=True)
        report = args.output / (label + '.vulnerabilities.json')
        scan = subprocess.run([executables['grype'], 'sbom:' + str(syft_json), '--fail-on', 'high', '-o', 'json', '--file', str(report)])
        results.append({'label': label, 'source': source, 'exitCode': scan.returncode, 'status': 'PASS' if scan.returncode == 0 else 'FAIL'})
    (args.output / 'scan-results.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    files = sorted(p for p in args.output.iterdir() if p.is_file() and p.name != 'sha256.json')
    (args.output / 'sha256.json').write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}, indent=2) + '\n', encoding='utf-8')
    if any(r['exitCode'] for r in results):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
