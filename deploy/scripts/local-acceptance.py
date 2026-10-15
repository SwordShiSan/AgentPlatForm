"""Isolated local Docker functional fixtures; never operate the cloud/legacy stack."""
import argparse
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r04-local'
NETWORK = 'agentplatform-r04-local-backplane'
ENV = dict(os.environ, PLATFORM_ROOT=DATA.as_posix(), PLATFORM_BACKPLANE=NETWORK,
           PLATFORM_INGRESS='agentplatform-r04-local-ingress',
           PLATFORM_EGRESS_CONTROL='agentplatform-r04-local-egress', GATEWAY_PORT='18084',
           PLATFORM_API_IMAGE='agentplatform-api:r0.1-local',
           PLATFORM_GATEWAY_IMAGE='agentplatform-gateway:r0.1-local')
ENV.update(json.loads((ROOT / 'deploy/runtime-images.lock.json').read_text())['images'])


def run(command):
    subprocess.run(command, cwd=ROOT, env=ENV, check=True)


def compose(app=False):
    result = ['docker', 'compose', '-p', 'agentplatform-r04-local-' + ('app' if app else 'env'),
              '-f', 'deploy/compose.app.yaml' if app else 'deploy/compose.env.yaml']
    return result if app else result + ['-f', 'deploy/compose.local.yaml']


def probe(mode):
    command = ['docker', 'run', '--rm', '--network', NETWORK, '--user', '10001:10001',
               '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
               '--memory', '192m', '--pids-limit', '128', '--tmpfs', '/tmp:rw,noexec,nosuid,size=16m',
               '-e', 'PROBE_MODE=' + mode,
               '--mount', 'type=bind,src=' + (DATA / 'reports').as_posix() + ',dst=/reports']
    for name in ['pg_api', 'pg_migration', 'mq_publisher', 'mq_execution', 'redis', 's3_read', 's3_write'] + (['s3_admin'] if mode == 'bootstrap' else []):
        command += ['--mount', 'type=bind,src=' + (DATA / 'secrets' / name).as_posix() + ',dst=/run/secrets/' + name + ',readonly']
    run(command + ['agentplatform-storage-probe:r0.3-ci'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['prepare', 'start', 'storage', 'java-storage', 'restart', 'stop'])
    mode = parser.parse_args().mode
    if mode == 'prepare':
        run([sys.executable, 'deploy/scripts/prepare-cloud-config.py'])
        (DATA / 'reports').mkdir(exist_ok=True)
        run(compose() + ['config', '--quiet'])
        run(compose(True) + ['--profile', 'migration', '--profile', 'worker', '--profile', 'runner', '--profile', 'egress', 'config', '--quiet'])
    elif mode == 'start':
        run(compose() + ['create'])
        for suffix in ['object-master', 'object-volume', 'object-filer']:
            volume = 'agentplatform-r04-local-env_' + suffix
            identity = json.loads(subprocess.check_output(['docker', 'volume', 'inspect', volume]))[0]
            if identity.get('Labels', {}).get('com.docker.compose.project') != 'agentplatform-r04-local-env':
                raise ValueError('Refusing ownership changes outside local fixture volumes')
            run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--user', '0:0',
                 '--cap-drop', 'ALL', '--cap-add', 'CHOWN', '--security-opt', 'no-new-privileges',
                 '--mount', 'type=volume,src=' + volume + ',dst=/data', '--entrypoint', '/bin/chown',
                 ENV['SEAWEEDFS_IMAGE'], '10001:10001', '/data'])
        run(compose() + ['up', '-d', '--wait', '--wait-timeout', '240'])
        run(compose(True) + ['--profile', 'migration', 'run', '--rm', 'migrate'])
        run(compose(True) + ['--profile', 'worker', '--profile', 'runner', '--profile', 'egress', 'up', '-d', '--wait', '--wait-timeout', '240'])
    elif mode == 'storage':
        probe('bootstrap')
        probe('initial')
    elif mode == 'java-storage':
        run(['docker', 'run', '--rm', '--network', NETWORK, '--user', '10001:10001',
             '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
             '--memory', '512m', '--pids-limit', '128', '--tmpfs', '/tmp:rw,noexec,nosuid,size=32m',
             '-e', 'PLATFORM_PROBE_JDBC_URL=jdbc:postgresql://postgres:5432/agentplatform?connectTimeout=2&socketTimeout=3',
             '-e', 'PLATFORM_PROBE_PASSWORD_FILE=/run/secrets/pg_api',
             '--mount', 'type=bind,src=' + (DATA / 'reports').as_posix() + ',dst=/reports',
             '--mount', 'type=bind,src=' + (DATA / 'secrets/pg_api').as_posix() + ',dst=/run/secrets/pg_api,readonly',
             '--entrypoint', 'java', 'agentplatform-framework-probe:r0.2-local', '-Xmx256m', '-cp',
             '/opt/probe/target/test-classes:/opt/probe/classes:/opt/probe/lib/*',
             'org.junit.platform.console.ConsoleLauncher', 'execute', '--select-class',
             'com.agentplatform.infrastructure.storage.PostgresStateStorageIT',
             '--reports-dir=/reports/java-storage', '--fail-if-no-tests'])
    elif mode == 'restart':
        run(compose() + ['stop', 's3', 'filer', 'volume', 'master'])
        run(compose() + ['up', '-d', '--wait', '--wait-timeout', '240', 'master', 'volume', 'filer', 's3'])
        run(compose() + ['restart', 'redis'])
        run(compose() + ['up', '-d', '--wait', '--wait-timeout', '240'])
        probe('restart')
    elif mode == 'stop':
        run(compose(True) + ['--profile', 'worker', '--profile', 'runner', '--profile', 'egress', 'stop'])
        run(compose() + ['stop'])


if __name__ == '__main__':
    main()
