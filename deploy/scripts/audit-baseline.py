"""Create SBOM/vulnerability evidence with locked tools; fail closed on missing scans."""
import argparse
import hashlib
import importlib.util
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
    parser.add_argument('--review-dir', type=pathlib.Path, default=ROOT / 'deploy/security/reviews')
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
        spdx = args.output / (label + '.spdx.json')
        source_options = [] if source.startswith(('dir:', 'file:')) else ['--platform', 'linux/amd64']
        subprocess.run([executables['syft'], source, *source_options, '-o', 'syft-json=' + str(syft_json), '-o', 'cyclonedx-json=' + str(bom), '-o', 'spdx-json=' + str(spdx)], check=True)
        inventory = json.loads(syft_json.read_text(encoding='utf-8'))
        licenses = [{'purl': item.get('purl'), 'name': item['name'], 'version': item['version'],
                     'licenses': item.get('licenses', []), 'locations': item.get('locations', [])}
                    for item in inventory['artifacts']]
        (args.output / (label + '.licenses.json')).write_text(json.dumps(licenses, indent=2) + '\n', encoding='utf-8')
        report = args.output / (label + '.vulnerabilities.json')
        scan = subprocess.run([executables['grype'], 'sbom:' + str(syft_json), '--fail-on', 'high', '-o', 'json', '--file', str(report)])
        assessed_exit = scan.returncode
        reviewed = set()
        review_invalid = False
        raw = json.loads(report.read_text(encoding='utf-8')) if report.exists() and scan.returncode in [0, 2] else {}
        if scan.returncode in (0, 2) and (not isinstance(raw.get('matches'), list) or raw.get('ignoredMatches')):
            review_invalid = True
        mcp_match = any(m['vulnerability']['id'] == 'GHSA-8jxr-pr72-r468' and m['artifact'].get('purl') == 'pkg:maven/io.modelcontextprotocol.sdk/mcp-core@0.17.2?type=jar' for m in raw.get('matches', []))
        if scan.returncode == 2 and mcp_match:
            data = json.loads(syft_json.read_text(encoding='utf-8'))
            image_id = data.get('source', {}).get('metadata', {}).get('imageID')
            product = ('pkg:oci/' + label + '@' + image_id) if image_id else ('urn:agentplatform:sbom:sha256:' + hashlib.sha256(syft_json.read_bytes()).hexdigest())
            vex = args.output / (label + '.vex.json')
            proof = subprocess.run([sys.executable, str(ROOT / 'deploy/scripts/mcp-applicability.py'), '--sbom', str(syft_json), '--product', product, '--output', str(vex)])
            if proof.returncode == 0:
                assessment_path = args.output / (label + '.assessment.json')
                assessment_path.unlink(missing_ok=True)
                assessed = subprocess.run([sys.executable, str(ROOT / 'deploy/scripts/assess-mcp-report.py'), '--report', str(report), '--sbom', str(syft_json), '--vex', str(vex), '--output', str(args.output / (label + '.assessment.json'))])
                assessed_exit = assessed.returncode
                # The verifier may reject unrelated blockers while still proving its one
                # exact MCP statement. Never reuse an assessment from an older attempt.
                if assessed.returncode in (0, 1) and assessment_path.exists():
                    checked = json.loads(assessment_path.read_text(encoding='utf-8'))
                    if checked['rawReportSha256'] == hashlib.sha256(report.read_bytes()).hexdigest() and checked['sbomSha256'] == hashlib.sha256(syft_json.read_bytes()).hexdigest():
                        reviewed.update((item['advisory'], item['purl']) for item in checked['assessments'] if item['status'] == 'NOT_AFFECTED')
        review_path = args.review_dir / (label + '.json')
        if scan.returncode in (0, 2) and review_path.exists():
            spec = importlib.util.spec_from_file_location('applicability', ROOT / 'deploy/scripts/assess-applicability.py')
            assessor = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(assessor)
            try:
                checked = assessor.assess(report, syft_json, review_path)
                (args.output / (label + '.review-assessment.json')).write_text(json.dumps(checked, indent=2) + '\n', encoding='utf-8')
                reviewed.update((item['advisory'], item['purl']) for item in checked['findings'] if item['disposition'] == 'NOT_AFFECTED')
            except (ValueError, KeyError, OSError, json.JSONDecodeError) as error:
                review_invalid = True
                (args.output / (label + '.review-invalid.txt')).write_text(str(error), encoding='utf-8')
        if scan.returncode in (0, 2):
            blockers = [m for m in raw.get('matches', []) if m['vulnerability']['severity'] in ('High', 'Critical') and (m['vulnerability']['id'], m['artifact'].get('purl')) not in reviewed]
            assessed_exit = 1 if review_invalid or blockers else 0
        results.append({'label': label, 'source': source, 'rawExitCode': scan.returncode, 'exitCode': assessed_exit, 'status': 'PASS' if assessed_exit == 0 else 'FAIL'})
    (args.output / 'scan-results.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    files = sorted(p for p in args.output.iterdir() if p.is_file() and p.name != 'sha256.json')
    (args.output / 'sha256.json').write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}, indent=2) + '\n', encoding='utf-8')
    if any(r['exitCode'] for r in results):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
