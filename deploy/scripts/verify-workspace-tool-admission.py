"""Bounded R4.4 PG/SDK/file admission checks. No supplier or production access."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
JDBC = 'jdbc:postgresql://127.0.0.1:15457/r44_test?connectTimeout=2&socketTimeout=5'
GROUPS = {
    'capture-persistence': 'WorkspaceShellAdmissionPostgresTest#capturesCannotQueueBeforeTheOriginalShellHasACommittedWholeStop+signedCaptureJobPublishesOneImmutableVersionAndSettlesOnlyItsOriginalReservationDespiteResponseLoss',
    'capture-runner-wire': 'FileCaptureRunnerServiceTest,FileRunnerServiceTest,RunnerSignaturesTest',
    'capture-foundation': 'WorkspaceShellAdmissionPostgresTest,CapturedFileStreamTest,FileCaptureChannelTest,FileRunnerServiceTest',
    'admission': 'WorkspaceToolAdmissionPostgresTest',
    'files': 'WorkspaceFileToolPostgresTest,WorkspaceToolAdmissionPostgresTest',
    'file-roles': 'WorkspaceFileToolPostgresTest#runnerPredicateDoesNotRequirePrivateChatGrantsAndFileTriggerWorksForApiRole',
    'file-cancel': 'WorkspaceFileToolPostgresTest#cancelledParentBlocksExecutionButChildCanPersistUnknownWithoutReleasingReservation',
    'shell-admission': 'WorkspaceShellAdmissionPostgresTest,WorkspaceToolAdmissionPostgresTest,WorkspaceFileToolPostgresTest#runnerPredicateDoesNotRequirePrivateChatGrantsAndFileTriggerWorksForApiRole',
    'shell-wire': 'RunnerHttpTest,RunnerSignaturesTest,EnvironmentUseCodecsTest',
    'stop-admission': 'WorkspaceShellAdmissionPostgresTest,WorkspaceFileToolPostgresTest',
    'capture-stream': 'CapturedFileStreamTest,FileCaptureChannelTest,S3AssetStoreTest',
    'file-exchange': 'WorkspaceFileToolExchangeTest',
    'operation-projection': 'WorkspaceFileToolPostgresTest#publicOperationProjectionIsOwnerScopedTypedAndDoesNotExposeWriteContent',
    'operation-results': 'WorkspaceFileToolPostgresTest#publicOperationProjectionIsOwnerScopedTypedAndDoesNotExposeWriteContent+knownNameConflictKeepsRunningToolButRollsBackOnlyFileAdmissionAndReturnsFailedObservation,WorkspaceOperationsServiceTest',
    'operation-result-units': 'WorkspaceOperationsServiceTest',
}


def source_identity():
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    paths = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z', '--', 'backend', 'deploy'], cwd=ROOT)
    manifest = {}
    for name in sorted(set(paths.decode('utf-8').split('\0')) - {''}):
        path = ROOT / name
        if path.is_file():
            manifest[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    encoded = json.dumps(manifest, ensure_ascii=True, sort_keys=True).encode('utf-8')
    return {'commit': sha, 'inputManifestSha256': hashlib.sha256(encoded).hexdigest(), 'files': manifest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--group', choices=sorted(GROUPS), default='files')
    args = parser.parse_args()
    # Fail before the multi-module build when the calling process cannot access
    # loopback. This is an execution permission, not a Docker installation fault.
    try:
        with socket.create_connection(('127.0.0.1', 15457), timeout=3):
            pass
    except PermissionError as error:
        raise SystemExit('HOST_LOOPBACK_ACCESS_REQUIRED: use the authorized host execution entry; do not restart Docker') from error
    except OSError as error:
        raise SystemExit(f'R44_DATABASE_PREFLIGHT_FAILED: {type(error).__name__}; inspect the isolated R44 fixture') from error
    password = ROOT / '.cache/r44-local/fixture/postgres-password.txt'
    java = ROOT / '.tools/jdk-21.0.12.1/jdk-21.0.12.1+1'
    if not password.is_file() or not (java / 'bin/java.exe').is_file():
        raise SystemExit('R44_PINNED_INPUT_MISSING: reuse stage preflight; no automatic install/reset')
    identity = source_identity()
    folder = ROOT / '.cache/r44-local' / f'{args.group}-verify-{time.time_ns()}'
    folder.mkdir(parents=True)
    (folder / 'source.json').write_text(json.dumps(identity, indent=2) + '\n', encoding='utf-8', newline='\n')
    env = os.environ.copy()
    env.update(JAVA_HOME=str(java), R44_TEST_JDBC_URL=JDBC, R44_TEST_PASSWORD_FILE=str(password))
    env['PATH'] = str(java / 'bin') + os.pathsep + env['PATH']
    command = [str(ROOT / 'backend/mvnw.cmd'), '-B', '-ntp', '-f', str(ROOT / 'backend/pom.xml'),
               '-pl', 'platform-app', '-am', '-Dtest=' + GROUPS[args.group], '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
    started = time.monotonic()
    with (folder / 'maven.log').open('wb') as log:
        process = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    unchanged = identity == source_identity()
    result = {'status': 'PASS' if process.returncode == 0 and unchanged else 'FAIL',
              'exitCode': process.returncode, 'inputUnchanged': unchanged, 'source': identity['commit'],
              'inputManifestSha256': identity['inputManifestSha256'], 'supplierCalls': 0,
              'scope': 'Selected stage tests only; physical/object ports may be deterministic. Consult source and assertions; not integrated release proof',
              'testSelection': GROUPS[args.group],
              'seconds': round(time.monotonic() - started, 2),
              'logSha256': hashlib.sha256((folder / 'maven.log').read_bytes()).hexdigest()}
    (folder / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(folder)
    print(json.dumps(result))
    if process.returncode:
        print('\n'.join((folder / 'maven.log').read_bytes().decode('utf-8', errors='replace').splitlines()[-50:]))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
