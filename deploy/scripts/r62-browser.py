"""Run real-browser capability checks against the isolated R6.2 control plane."""
from pathlib import Path
import hashlib
import argparse
import importlib.util
import json
import os
import subprocess
import time
import zipfile

parser = argparse.ArgumentParser(); parser.add_argument('--skill-only', action='store_true'); parser.add_argument('--connector-only', action='store_true'); parser.add_argument('--probe-only', action='store_true')
args = parser.parse_args()
if sum([args.skill_only, args.connector_only, args.probe_only]) > 1: parser.error('Choose at most one focused test')
ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r62-local'
spec = importlib.util.spec_from_file_location('r62_local', ROOT / 'deploy/scripts/r62-local.py')
local = importlib.util.module_from_spec(spec); spec.loader.exec_module(local)
local.environment()
fixture = json.loads((DATA / 'fixture/manifest.json').read_text(encoding='utf-8'))
images = json.loads((DATA / 'images.json').read_text(encoding='utf-8'))
archive = DATA / 'fixture/browser-skill.zip'
skill_name = 'r62-browser-' + str(time.time_ns())
with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as package:
    package.writestr('SKILL.md', '---\nname: '+skill_name+'\ndescription: Local deterministic fixture\n---\nUse the selected material.\n')
out = DATA / ('browser-' + str(time.time_ns())); out.mkdir(parents=True)
if os.name == 'nt':
    user = subprocess.check_output(['whoami'], text=True, errors='replace').strip()
    subprocess.run(['icacls', str(out), '/inheritance:r', '/grant:r', user + ':(OI)(CI)F', 'SYSTEM:(OI)(CI)F'], check=True, capture_output=True)
node = ROOT / '.tools/node-v22.23.3-win-x64/node.exe'
env = dict(os.environ, R62_BROWSER_ACCEPTANCE='1', BASE_URL='http://127.0.0.1:' + str(fixture['gatewayPort']),
           R62_BROWSER_SKILL_NAME=skill_name,
           TEMP=str(DATA/'tmp'), TMP=str(DATA/'tmp'), PATH=str(node.parent) + os.pathsep + os.environ['PATH'])
command = [str(node), str(ROOT/'frontend/node_modules/@playwright/test/cli.js'), 'test', 'capabilities-r62.spec.ts',
           '--reporter=json', '--output', str(out/'artifacts')]
if args.skill_only: command += ['--grep', 'import, publish']
if args.connector_only: command += ['--grep', 'connector metadata']
if args.probe_only: command += ['--grep', 'discover and explicitly enable']
report, errors = out/'playwright.json', out/'playwright.stderr.log'
with report.open('xb') as stdout, errors.open('xb') as stderr:
    result = subprocess.run(command, cwd=ROOT/'frontend', env=env, stdout=stdout, stderr=stderr, timeout=300)
try:
    stats = json.loads(report.read_text(encoding='utf-8'))['stats']
    passed = result.returncode == 0 and stats['unexpected'] == 0 and stats['skipped'] == 0 and stats['expected'] == (1 if args.skill_only or args.connector_only or args.probe_only else 3)
except (ValueError, KeyError):
    stats = {}; passed = False
record = {'status': 'PASS' if passed else 'FAIL', 'exitCode': result.returncode, 'source': images['source'],
    'buildInputHash': images['inputHash'], 'runtimeConfigSha256': hashlib.sha256((DATA/'runtime-config.json').read_bytes()).hexdigest(),
    'imageConfigIds': {name: value['configId'] for name, value in images['images'].items()}, 'stats': stats,
    'scope': ('skill' if args.skill_only else 'connector' if args.connector_only else 'probe' if args.probe_only else 'all') + ': isolated control-plane browser, no physical runner or MCP business call', 'supplierCalls': 0,
    'reportSha256': hashlib.sha256(report.read_bytes()).hexdigest()}
(out/'result.json').write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'status': record['status'], 'evidence': str(out.relative_to(ROOT))}))
raise SystemExit(0 if passed else 1)
