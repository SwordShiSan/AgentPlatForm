"""Explicit local acceptance identities; no arbitrary project or data directory selection."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGE = os.environ.get('AGENTPLATFORM_ACCEPTANCE_STAGE', 'r21')
assert STAGE in {'r21', 'r2c'}, 'Unsupported local acceptance stage'
PREFIX = {'r21': 'R21', 'r2c': 'R2C'}[STAGE]
PROJECT = 'agentplatform-' + STAGE + '-local'
DATA = ROOT / ('.cache/' + STAGE + '-local')
COMPOSE_FILE = {'r21': 'deploy/compose.execution-local.yaml', 'r2c': 'deploy/compose.framework-local.yaml'}[STAGE]
PORTS = {'r21': (15443, 15683, 15693, 18088), 'r2c': (15444, 15684, 15694, 18089)}[STAGE]
ENV = dict(os.environ, **{PREFIX + '_ROOT': DATA.as_posix(), PREFIX + '_MQ_HOSTNAME': 'agentplatform-' + STAGE + '-mq',
                         PREFIX + '_PROVIDER_BASE': 'http://fixture:8080', PREFIX + '_FIXTURE_PROVIDER': 'true',
                         'EXECUTION_IMAGE_PREFIX': PREFIX, 'R12_ROOT': DATA.as_posix()})
COMPOSE = ['docker', 'compose', '-p', PROJECT, '-f', COMPOSE_FILE]
BASE = 'http://127.0.0.1:' + str(PORTS[3])
