"""Hash explicitly selected, non-secret R0.4 evidence; never traverse runtime/backup trees."""
import hashlib
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
output = ROOT / 'evidence' / 'r0.4'
output.mkdir(parents=True, exist_ok=True)
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
diff = subprocess.check_output(['git', 'diff', '--binary'], cwd=ROOT)
files = list(output.rglob('*.json'))
files.extend(output.glob('sdk-method-signatures.txt'))
for pattern in ['*/target/bom-*.json', '*/target/dependency-tree.txt', '*/target/effective-pom.xml', '*/target/surefire-reports/TEST-*.xml']:
    files.extend((ROOT / 'backend').glob(pattern))
files.extend((ROOT / 'backend/target').glob('bom-*.json'))
files.extend((ROOT / 'contracts').rglob('*.json'))
files.extend([ROOT / 'contracts/openapi.yaml', ROOT / 'deploy/audit-tools.lock.json', ROOT / 'deploy/probes/requirements.lock'])
records = []
for path in sorted(set(files)):
    if path.name == 'evidence-index.json' or not path.is_file():
        continue
    records.append({'path': path.relative_to(ROOT).as_posix(), 'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
manifest = {'schemaVersion': 1, 'gitHead': head, 'trackedDiffSha256': hashlib.sha256(diff).hexdigest(),
            'candidate': 'working-tree' if diff else 'committed-head', 'phaseStatus': 'IMPLEMENTING',
            'note': 'No production release. NOT_RUN and FAIL remain in OpenSpec verification. Untracked files require separate Git review.', 'files': records}
(output / 'evidence-index.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(f'Indexed {len(records)} explicitly selected files; no backups/secrets/raw cloud logs included')
