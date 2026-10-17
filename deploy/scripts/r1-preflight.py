"""Read-only R1 capacity and configuration admission; never prints configuration secrets."""
import json, os
from pathlib import Path
import subprocess

root = Path('/srv/agentplatform')
assert (root / 'deploy/r1-enabled').is_file()
env = dict(os.environ, PLATFORM_ROOT=str(root))
base = ['docker', 'compose', '--env-file', str(root / 'deploy/images.env')]
def config(project, files):
    args = base + ['-p', project]
    for file in files:
        args += ['-f', str(root / 'deploy' / file)]
    return json.loads(subprocess.check_output(args + ['--profile', 'worker', 'config', '--format', 'json'], env=env))
environment = config('agentplatform-env', ['compose.env.yaml', 'compose.production.env.yaml', 'compose.r1-http.env.yaml'])
application = config('agentplatform-app', ['compose.app.yaml', 'compose.production.app.yaml', 'compose.r1-http.app.yaml'])
services = [environment['services'][s] for s in ['postgres','redis','rabbitmq','master','volume','filer','s3']]
services += [application['services'][s] for s in ['api','worker','gateway']]
total = sum(int(s['mem_limit']) for s in services)
assert total <= 2887 * 1024**2
assert all(int(s['mem_limit']) == int(s['memswap_limit']) for s in services)
physical = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemTotal:'))) * 1024
assert physical - total >= 768 * 1024**2
for name in ['chat_master', 'chat_cursor']:
    file = root / 'secrets' / name
    assert file.is_file() and file.stat().st_size == 32
    assert file.stat().st_mode & 0o077 == 0, 'Secret permissions must be private'
api = application['services']['api']['environment']
assert api['SPRING_PROFILES_ACTIVE'] == 'identity-local,chat-local,r1-http'
worker = application['services']['worker']['environment']
assert worker['PLATFORM_CHAT_PROVIDER_BASE'] == 'https://api.deepseek.com'
assert str(worker['PLATFORM_CHAT_FIXTURE_PROVIDER']).lower() == 'false'
print(json.dumps({'status':'PASS','totalContainerLimitMiB':total//1024**2,'singleExecutionSlot':True,'swapExtension':False}))
