"""Compile/run the trusted V2 integrity probe against the accepted image, with consumers stopped."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid
from docker_preflight import require_linux

assert os.environ.get('AGENTPLATFORM_ACCEPTANCE_STAGE') == 'r24', 'Explicit local R2.4 fixture required'
from execution_fixture import ROOT, DATA, ENV, COMPOSE, PROJECT


def output(args):
    return subprocess.check_output(args, cwd=ROOT, env=ENV, text=True).strip()


def main():
    require_linux()
    ids = output(COMPOSE + ['ps', '-q']).splitlines()
    assert ids, 'Start only the existing local database/dependencies first'
    rows = json.loads(output(['docker', 'inspect', *ids]))
    assert all(row['Config']['Labels']['com.docker.compose.project'] == PROJECT for row in rows)
    assert all(row['Config']['Labels']['com.docker.compose.service'] in {'postgres', 'rabbitmq', 'redis'} for row in rows), 'Stop application, fixture and operator processes before compilation'
    limits = [int(row['HostConfig']['Memory']) for row in rows]
    assert all(value > 0 for value in limits)
    coexisting = sum(limits) + 512 * 1024 * 1024
    assert coexisting <= 2887 * 1024 * 1024, 'The temporary probe counts toward the combined memory maximum'
    memory = output(COMPOSE + ['exec', '-T', 'postgres', 'cat', '/proc/meminfo'])
    available = int(next(line.split()[1] for line in memory.splitlines() if line.startswith('MemAvailable:'))) * 1024
    assert available >= 768 * 1024 * 1024, 'Host reserve below768MiB'
    candidate = json.loads((DATA / 'final-candidate.json').read_text())
    checkout = Path(candidate['canonicalCheckout']).resolve()
    assert checkout.is_relative_to((ROOT / '.cache').resolve())
    records = json.loads((DATA / 'artifacts/images.json').read_text())
    image = next(row for row in records if row['component'] == 'framework-probe')
    assert image['source'] == candidate['source']
    source = ROOT / 'deploy/probes/RecoveryIntegrityProbe.java'
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    target = DATA / 'acceptance' / ('integrity-classes-' + source_hash[:12] + '-' + uuid.uuid4().hex[:8])
    target.mkdir(parents=True)
    java = ROOT / '.tools/jdk-21.0.12.1/jdk-21.0.12.1+1/bin'
    classpath = os.pathsep.join(str(checkout / 'backend/platform-app' / name) for name in ['target/probe-libs/*', 'target/classes', 'target/test-classes'])
    log = DATA / 'acceptance/integrity-probe.log'
    with log.open('w', encoding='utf-8') as stream:
        subprocess.run([str(java / ('javac.exe' if os.name == 'nt' else 'javac')), '-J-Xmx256m', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(target), str(source)], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=True)
        args = ['docker', 'run', '--rm', '--name', 'agentplatform-r24-integrity-probe', '--network', PROJECT,
                '--read-only', '--user', '10001:10001', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                '--memory', '512m', '--memory-swap', '512m', '--pids-limit', '128',
                '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m,uid=10001,gid=10001,mode=700',
                '--mount', 'type=bind,src=' + target.as_posix() + ',dst=/opt/extra,readonly',
                '--mount', 'type=bind,src=' + (DATA / 'postgres-password').as_posix() + ',dst=/run/secrets/pg,readonly',
                '--mount', 'type=bind,src=' + (DATA / 'credential-master-key').as_posix() + ',dst=/run/secrets/master,readonly',
                '--entrypoint', 'java', image['localId'], '-Xms16m', '-Xmx256m', '-cp',
                '/opt/probe/lib/*:/opt/probe/classes:/opt/probe/target/test-classes:/opt/extra', 'com.agentplatform.app.chat.RecoveryIntegrityProbe']
        subprocess.run(args, cwd=ROOT, env=ENV, stdout=stream, stderr=subprocess.STDOUT, check=True)
    text = log.read_text(encoding='utf-8')
    assert 'PASS integrity-refusals:10;' in text
    bytecode = target / 'com/agentplatform/app/chat/RecoveryIntegrityProbe.class'
    report = {'status': 'PASS', 'scope': 'REAL_PG_V2_REFUSALS_AND_COMMAND_CAS_NO_EXECUTOR_LAUNCHED', 'cases': 10,
              'applicationSource': candidate['source'], 'probeSourceSha256': source_hash,
              'probeBytecodeSha256': hashlib.sha256(bytecode.read_bytes()).hexdigest(),
              'probeImage': image['localId'], 'logSha256': hashlib.sha256(log.read_bytes()).hexdigest(),
              'exitCode': 0, 'supplierCalls': 0, 'proofOfContainerStop': False,
              'coexistingLimitBytesIncludingProbe': coexisting, 'sampledHostAvailableBytes': available}
    (DATA / 'acceptance/integrity-probe.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('PASS10 V2 integrity/codec/quota/cancellation/CAS cases on real PG and accepted non-root image; no executor/supplier call.')


if __name__ == '__main__':
    main()
