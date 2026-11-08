"""Run a bounded R6.2 PG/MQ/S3 integration fixture without touching other projects."""
from pathlib import Path
import json
import os
import secrets
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache' / 'r62-local' / 'integrated' / ('run-' + str(time.time_ns()))
DOCKER = ['docker', '--context', 'desktop-linux']
RABBIT = 'rabbitmq:4.3.6-management@sha256:8dd6e3570ddaa2ef82a6c3a8950e79c1f73893adfbdddbc374fc1f1ac8a0f5dd'
SEAWEED = 'chrislusf/seaweedfs@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d'


def call(args, **kwargs):
    return subprocess.run([str(value) for value in args], capture_output=True, check=True, **kwargs)


def port(container, internal):
    row = json.loads(call(DOCKER + ['inspect', container]).stdout)[0]
    bindings = row['NetworkSettings']['Ports'][internal + '/tcp']
    assert len(bindings) == 1 and bindings[0]['HostIp'] == '127.0.0.1'
    return int(bindings[0]['HostPort'])


def main():
    DATA.mkdir(parents=True)
    password = secrets.token_urlsafe(32)
    env_file, password_file = DATA / 'rabbit.env', DATA / 'rabbit-password'
    env_file.write_text('RABBITMQ_DEFAULT_USER=r62fixture\nRABBITMQ_DEFAULT_PASS=' + password
                        + '\nRABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2 +A 8\n', encoding='utf-8')
    password_file.write_text(password, encoding='utf-8')
    containers = []
    try:
        mq = call(DOCKER + ['run', '-d', '--rm', '--name', 'agentplatform-r62-integrated-mq-' + secrets.token_hex(5),
            '--label', 'ap.stage=R62-integrated', '--user', '999:999', '--memory', '512m', '--memory-swap', '512m', '--cpus', '1',
            '--publish', '127.0.0.1::5672', '--env-file', env_file,
            '--tmpfs', '/var/lib/rabbitmq:rw,size=128m,uid=999,gid=999,mode=0700', RABBIT], timeout=90).stdout.decode().strip()
        containers.append(mq)
        s3 = call(DOCKER + ['run', '-d', '--rm', '--name', 'agentplatform-r62-integrated-s3-' + secrets.token_hex(5),
            '--label', 'ap.stage=R62-integrated', '--memory', '640m', '--memory-swap', '640m', '--cpus', '1',
            '--publish', '127.0.0.1::8333', '--tmpfs', '/data:rw,size=128m', '--env', 'GOMAXPROCS=1',
            '--env', 'GOMEMLIMIT=480MiB', '--env', 'WEED_MASTER_VOLUME_GROWTH_COPY_1=1', '--entrypoint', 'weed', SEAWEED, 'server', '-dir=/data', '-ip=localhost',
            '-ip.bind=0.0.0.0', '-filer', '-s3', '-s3.ip.bind=0.0.0.0', '-master.volumeSizeLimitMB=64',
            '-volume.max=4', '-volume.minFreeSpace=1'], timeout=90).stdout.decode().strip()
        containers.append(s3)
        rabbit_port, s3_port = port(mq, '5672'), port(s3, '8333')
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            rabbit_ok = subprocess.run(DOCKER + ['exec', mq, 'rabbitmq-diagnostics', '-q', 'ping'], capture_output=True).returncode == 0
            try:
                with socket.create_connection(('127.0.0.1', s3_port), timeout=2): s3_ok = True
            except OSError: s3_ok = False
            if rabbit_ok and s3_ok: break
            for item in containers:
                assert call(DOCKER + ['inspect', '--format', '{{.State.Running}}', item]).stdout.strip() == b'true'
            time.sleep(2)
        else: raise RuntimeError('Isolated dependencies did not become ready')
        environment = dict(os.environ, R62_AMQP_PORT=str(rabbit_port), R62_AMQP_ACCEPTANCE='1',
            R62_AMQP_PASSWORD_FILE=str(password_file), R62_S3_PORT=str(s3_port), R62_INTEGRATED_ACCEPTANCE='1')
        result = subprocess.run(['python', str(ROOT / 'deploy/scripts/r62-postgres.py')], cwd=ROOT, env=environment,
                                text=True, capture_output=True, timeout=420)
        (DATA / 'postgres-test.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        if result.returncode: raise RuntimeError('Integrated test failed; inspect protected fixture logs')
        print(json.dumps({'status': 'PASS', 'scope': 'isolated PG/MQ/S3 Skill import pipeline', 'evidence': str(DATA.relative_to(ROOT))}))
    except Exception:
        for index, item in enumerate(containers):
            logs = subprocess.run(DOCKER + ['logs', item], capture_output=True, timeout=20)
            (DATA / ('container-' + str(index) + '.private.log')).write_bytes(logs.stdout + logs.stderr)
        raise
    finally:
        for item in reversed(containers):
            subprocess.run(DOCKER + ['stop', '-t', '5', item], capture_output=True, timeout=30)
        env_file.unlink(missing_ok=True); password_file.unlink(missing_ok=True)


if __name__ == '__main__': main()
