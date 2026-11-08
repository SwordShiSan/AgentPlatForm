"""Enable the signed cloud-runner route only in the isolated R63 fixture."""
import importlib.util
import hashlib
import json
import os
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('r63_local', ROOT / 'deploy/scripts/r63-local.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
env = fixture.environment()
image_id = 'sha256:502ba213ee70a1ed10e6a01aec519fb64c757a0c8201c774553c49d58f646545'
bundle = 'asset-parser-tika3.3.3-v2'
runsc = '210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a'
material = ('preview-runtime-v1|' + image_id + '|' + bundle + '|' + runsc +
            '|systrap|uid10001|net-none|ro|caps-none|no-devices|one-readonly-input|memory512m-swap512m|cpu1|pids128|tmpfs256m|timeout120s|output100m|pages200|image40mp|zip500m|source-or-fixed-output|office26.2.6|jod5.1.1|pdfbox3.0.8|jsoup1.23.2|twelvemonkeys3.13.1')
policy = hashlib.sha256(material.encode()).hexdigest()
env.update(R63_PREVIEW_IMAGE_ID=image_id, R63_PREVIEW_BUNDLE=bundle, R63_PREVIEW_POLICY_HASH=policy)

compose = ['docker', '--context', 'desktop-linux', 'compose', '-p', 'agentplatform-r63-local',
           '-f', str(ROOT / 'deploy/compose.r63-local.yaml'),
           '-f', str(ROOT / 'deploy/compose.r63-local-cloud-runner.yaml')]
for args in (['config', '--quiet'],
             ['up', '-d', '--no-build', '--no-deps', '--force-recreate', '--wait',
              '--wait-timeout', '300', 'api', 'worker']):
    result = subprocess.run(compose + args, cwd=ROOT, env=env, capture_output=True, text=True, timeout=360)
    if result.returncode:
        raise SystemExit('Isolated R63 cloud-runner Compose operation failed: ' + ' '.join(args))

base = 'http://127.0.0.1:' + env['R63_PORT']
deadline = time.monotonic() + 30
while time.monotonic() < deadline:
    try:
        req = urllib.request.Request(base + '/api/v1/readiness', headers={'Accept': 'application/json'})
        with urllib.request.urlopen(req, timeout=3) as response:
            if response.status == 200:
                break
    except Exception:
        time.sleep(1)
else:
    raise SystemExit('R63 API readiness did not recover after the isolated override')

queues = subprocess.run(['docker', '--context', 'desktop-linux', 'exec', 'agentplatform-r63-local-rabbitmq-1',
                         'rabbitmqctl', 'list_queues', '-p', '/agentplatform', 'name', 'consumers'],
                        capture_output=True, text=True, timeout=20)
if queues.returncode or not any(row.split()[:2] == ['ap.asset-extract.v1', '1'] for row in queues.stdout.splitlines()):
    raise SystemExit('The R63 extraction consumer is not ready')
print(json.dumps({'status': 'READY', 'api': 'ready', 'extractionConsumer': True,
                  'rendererImageId': image_id, 'bundle': bundle, 'policyHash': policy}))
