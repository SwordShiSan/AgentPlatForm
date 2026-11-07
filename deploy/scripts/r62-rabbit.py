"""Run real broker handoff test on a disposable, isolated RabbitMQ instance."""
from pathlib import Path
import json
import os
import secrets
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache' / 'r62-local' / 'rabbit' / ('run-' + str(time.time_ns()))
DOCKER = ['docker', '--context', 'desktop-linux']
IMAGE = 'rabbitmq:4.3.6-management@sha256:8dd6e3570ddaa2ef82a6c3a8950e79c1f73893adfbdddbc374fc1f1ac8a0f5dd'


def call(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], capture_output=True, check=True, **kwargs)


def main():
    DATA.mkdir(parents=True, exist_ok=True)
    password = secrets.token_urlsafe(32)
    env_file, password_file = DATA / 'rabbit.env', DATA / 'password'
    env_file.write_text('RABBITMQ_DEFAULT_USER=r62fixture\nRABBITMQ_DEFAULT_PASS=' + password
                        + '\nRABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2 +A 8\n', encoding='utf-8')
    password_file.write_text(password, encoding='utf-8')
    identity = None
    try:
        identity = call(DOCKER + ['run', '-d', '--name', 'agentplatform-r62-mq-' + secrets.token_hex(5),
            '--label', 'ap.stage=R62-rabbit', '--user', '999:999', '--memory', '512m', '--memory-swap', '512m', '--cpus', '1',
            '--publish', '127.0.0.1::5672', '--env-file', env_file,
            '--tmpfs', '/var/lib/rabbitmq:rw,size=128m,uid=999,gid=999,mode=0700', IMAGE], timeout=90).stdout.decode().strip()
        row = json.loads(call(DOCKER + ['inspect', identity]).stdout)[0]
        binding = row['NetworkSettings']['Ports']['5672/tcp']
        assert len(binding) == 1 and binding[0]['HostIp'] == '127.0.0.1'
        for _ in range(90):
            if call(DOCKER + ['inspect', '--format', '{{.State.Running}}', identity]).stdout.decode().strip() != 'true':
                logs = subprocess.run(DOCKER + ['logs', identity], capture_output=True, timeout=20)
                (DATA / 'startup.private.log').write_bytes(logs.stdout + logs.stderr)
                raise RuntimeError('isolated broker exited during startup')
            if subprocess.run(DOCKER + ['exec', identity, 'rabbitmq-diagnostics', '-q', 'ping'], capture_output=True).returncode == 0:
                break
            time.sleep(1)
        else:
            logs = subprocess.run(DOCKER + ['logs', identity], capture_output=True, timeout=20)
            (DATA / 'startup.private.log').write_bytes(logs.stdout + logs.stderr)
            raise RuntimeError('isolated broker not ready')
        env = dict(os.environ, R62_AMQP_PORT=binding[0]['HostPort'], R62_AMQP_PASSWORD_FILE=str(password_file), R62_AMQP_ACCEPTANCE='1')
        result = subprocess.run([str(ROOT / 'backend' / 'mvnw.cmd'), '-Dtest=SkillMessagingRabbitTest',
                                 '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], cwd=ROOT / 'backend',
                                env=env, capture_output=True, timeout=300)
        (DATA / 'maven.log').write_bytes(result.stdout + result.stderr)
        receipt = {'status': 'PASS' if result.returncode == 0 else 'FAIL', 'exitCode': result.returncode,
                   'scope': 'real RabbitMQ confirms, failed claim redelivery and duplicate handoff; in-memory claim fixture, not real PG/S3',
                   'source': call(['git', 'rev-parse', 'HEAD'], cwd=ROOT).stdout.decode().strip(), 'image': IMAGE}
        (DATA / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(receipt))
        if result.returncode: raise RuntimeError('RabbitMQ test failed; inspect protected maven.log')
    finally:
        if identity:
            row = json.loads(call(DOCKER + ['inspect', identity]).stdout)[0]
            assert row['Config']['Labels'].get('ap.stage') == 'R62-rabbit'
            call(DOCKER + ['rm', '-f', identity])
        env_file.unlink(missing_ok=True)
        password_file.unlink(missing_ok=True)


if __name__ == '__main__': main()
