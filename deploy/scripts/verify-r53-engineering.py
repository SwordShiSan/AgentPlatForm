"""Frozen R53 reactor/profile/package gates. Physical PG/MQ/S3/runsc/browser receipts are separate."""
from contextlib import contextmanager
from pathlib import Path
from zipfile import ZipFile
import argparse, hashlib, importlib.util, json, os, subprocess, sys, time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r53-local'
JAVA = ROOT / '.tools/jdk-21.0.12.1/jdk-21.0.12.1+1'


def digest(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def source_identity():
    spec = importlib.util.spec_from_file_location('r53_identity', ROOT / 'deploy/scripts/verify-workspace-tool-admission.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    identity = module.source_identity()
    identity['files']['.dockerignore'] = digest(ROOT / '.dockerignore')
    # Only these inputs affect this backend/profile/renderer package check. Other acceptance scripts
    # remain in source.json for traceability; their edits do not pretend the compiled backend changed.
    relevant = {name: sha for name, sha in identity['files'].items() if name.startswith('backend/') or
                name.startswith('deploy/preview/') or name in ['deploy/preview-renderer.Dockerfile', 'deploy/preview-renderer.lock.json', '.dockerignore']}
    identity['sourceInputFiles'] = relevant
    identity['sourceInputSha256'] = hashlib.sha256(json.dumps(relevant, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return identity


def child_environment():
    if not (JAVA / 'bin/java.exe').is_file() or 'JAVA_VERSION="21.0.12.1"' not in (JAVA / 'release').read_text(encoding='utf-8'):
        raise RuntimeError('Pinned Java21 toolchain unavailable; do not install/reset an unrelated environment')
    env = dict(os.environ, JAVA_HOME=str(JAVA), PATH=str(JAVA / 'bin') + os.pathsep + os.environ['PATH'], OPENSPEC_TELEMETRY='0')
    for name in list(env):
        if name.endswith('_ACCEPTANCE') or '_TEST_JDBC_URL' in name or '_TEST_PASSWORD_FILE' in name:
            env.pop(name)
        elif name in ['DEEPSEEK_API_KEY', 'OPENAI_API_KEY', 'ANTHROPIC_API_KEY', 'SPRING_PROFILES_ACTIVE',
                      'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD']:
            env.pop(name)
    return env


@contextmanager
def controller_lock():
    DATA.mkdir(parents=True, exist_ok=True)
    # OS-owned locking is released on process exit, so a stale filename cannot require unsafe PID killing.
    with (DATA / 'engineering-controller.lock').open('a+b') as stream:
        stream.seek(0); stream.write(b'0'); stream.flush(); stream.seek(0)
        if os.name == 'nt':
            import msvcrt
            try: msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as busy: raise RuntimeError('Another R53 engineering controller is active; inspect its existing receipt') from busy
            try: yield
            finally: stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            try: yield
            finally: fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def report_snapshot():
    return {str(path.relative_to(ROOT)): (path.stat().st_mtime_ns, digest(path))
            for path in (ROOT / 'backend').glob('platform-*/target/surefire-reports/TEST-*.xml')}


def test_reports(before):
    records = []
    for path in sorted((ROOT / 'backend').glob('platform-*/target/surefire-reports/TEST-*.xml')):
        name = str(path.relative_to(ROOT)); actual = (path.stat().st_mtime_ns, digest(path))
        if before.get(name) == actual: continue
        suite = ET.parse(path).getroot()
        records.append({'path': name, 'sha256': actual[1], 'tests': int(suite.attrib.get('tests', 0)),
                        'failures': int(suite.attrib.get('failures', 0)), 'errors': int(suite.attrib.get('errors', 0)),
                        'skipped': int(suite.attrib.get('skipped', 0))})
    return {'tests': sum(item['tests'] for item in records), 'failures': sum(item['failures'] for item in records),
            'errors': sum(item['errors'] for item in records), 'skipped': sum(item['skipped'] for item in records),
            'reports': records, 'skippedMeaning': 'NOT_RUN in this reactor run; actual external gates require separate integration evidence'}


def packages():
    app = ROOT / 'backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar'
    renderer = app.with_name('platform-app-0.1.0-SNAPSHOT-renderer.jar')
    if not app.is_file() or not renderer.is_file(): raise RuntimeError('Actual app and renderer classifier packages are required')
    with ZipFile(app) as archive:
        entries = archive.namelist()
        if 'BOOT-INF/classes/com/agentplatform/app/PlatformApplication.class' not in entries:
            raise RuntimeError('Normal application package lacks its main class')
        denied_classes = [entry for entry in entries if entry.startswith('BOOT-INF/classes/com/agentplatform/renderer/')]
        prefixes = ['jodconverter-', 'libreoffice-', 'pdfbox-', 'fontbox-', 'imageio-', 'common-image-', 'common-lang-', 'common-io-', 'jsoup-']
        denied_libraries = [entry for entry in entries if entry.startswith('BOOT-INF/lib/') and any(Path(entry).name.startswith(prefix) for prefix in prefixes)]
        if denied_classes or denied_libraries: raise RuntimeError('Office/renderer payload leaked into the ordinary app package')
    with ZipFile(renderer) as archive:
        entries = archive.namelist()
        if 'com/agentplatform/renderer/PreviewRendererMain.class' not in entries:
            raise RuntimeError('Renderer classifier lacks its fixed entrypoint')
        unexpected = [entry for entry in entries if entry.endswith('.class') and not entry.startswith('com/agentplatform/renderer/')]
        if unexpected or any(entry.endswith('Test.class') for entry in entries):
            raise RuntimeError('Renderer classifier contains application/test classes')
    return {'status': 'PASS', 'ordinaryAppExcludesOfficeAndRenderer': True,
            'app': {'path': str(app.relative_to(ROOT)), 'bytes': app.stat().st_size, 'sha256': digest(app)},
            'renderer': {'path': str(renderer.relative_to(ROOT)), 'bytes': renderer.stat().st_size, 'sha256': digest(renderer)}}


def command(prepare):
    base = [str(ROOT / 'backend/mvnw.cmd'), '-B', '-ntp', '-f', str(ROOT / 'backend/pom.xml'), '-Ppreview-renderer']
    if prepare:
        # Resolve the new profile plugins/dependencies without compilation, tests, migrations or external systems.
        return base + ['-pl', 'platform-app', 'org.codehaus.mojo:build-helper-maven-plugin:3.6.1:add-source@renderer-source',
                       'org.codehaus.mojo:build-helper-maven-plugin:3.6.1:add-test-source@renderer-tests',
                       'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies@renderer-libraries']
    return base + ['-o', 'verify']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-dependencies', action='store_true', help='Online resolve only; never claims engineering verification')
    args = parser.parse_args()
    with controller_lock():
        output = DATA / (('engineering-prepare-' if args.prepare_dependencies else 'engineering-') + str(time.time_ns()))
        output.mkdir(); before = source_identity(); env = child_environment(); selected = command(args.prepare_dependencies)
        (output / 'source-before.json').write_text(json.dumps(before, indent=2) + '\n', encoding='utf-8')
        (output / 'invocation.json').write_text(json.dumps({'controllerPid': os.getpid(), 'command': selected, 'javaHome': str(JAVA),
            'profile': 'preview-renderer', 'supplierCalls': 0}, indent=2) + '\n', encoding='utf-8')
        report_before = report_snapshot(); started = time.monotonic(); log = output / 'maven.log'
        with log.open('wb') as stream: process = subprocess.run(selected, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
        checks = {}; error = None
        if process.returncode == 0:
            try:
                if not args.prepare_dependencies: checks['packages'] = packages()
                lock_log = output / 'renderer-lock.log'
                with lock_log.open('wb') as stream:
                    checked = subprocess.run([sys.executable, str(ROOT / 'deploy/scripts/preview-renderer-lock.py')], cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
                checks['rendererLock'] = {'status': 'PASS' if checked.returncode == 0 else 'FAIL', 'exitCode': checked.returncode, 'logSha256': digest(lock_log)}
                if checked.returncode: raise RuntimeError('Renderer classpath hash lock failed')
            except Exception as failed: error = str(failed)
        else: checks['packages'] = {'status': 'NOT_RUN', 'reason': 'Maven failed; stale artifacts are not acceptance evidence'}
        after = source_identity(); unchanged = before['sourceInputFiles'] == after['sourceInputFiles']
        (output / 'source-after.json').write_text(json.dumps(after, indent=2) + '\n', encoding='utf-8')
        revision_unchanged = before['commit'] == after['commit']
        success = process.returncode == 0 and error is None and ((unchanged and revision_unchanged) or args.prepare_dependencies)
        result = {'status': ('PREPARED_NOT_VERIFIED' if args.prepare_dependencies else 'PASS') if success else 'FAIL',
                  'exitCode': process.returncode, 'controllerExitCode': 0 if success else 1, 'inputUnchanged': unchanged,
                  'revisionUnchanged': revision_unchanged, 'source': before['commit'],
                  'sourceInputSha256': before['sourceInputSha256'], 'afterSourceInputSha256': after['sourceInputSha256'],
                  'seconds': round(time.monotonic() - started, 3), 'supplierCalls': 0, 'mavenLogSha256': digest(log),
                  'profile': 'preview-renderer', 'checks': checks, 'error': error,
                  'scope': 'Profile/Enforcer/DDD/Mapper/unit/package gates only. External and skipped gates remain separate NOT_RUN until their actual receipts pass.'}
        if not args.prepare_dependencies: result['tests'] = test_reports(report_before)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        summary = {key: value for key, value in result.items() if key != 'tests'}
        if 'tests' in result: summary['tests'] = {key: value for key, value in result['tests'].items() if key != 'reports'}
        print(output); print(json.dumps(summary, ensure_ascii=True))
        if not success: print(log.read_text(encoding='utf-8', errors='replace')[-7000:])
        return 0 if success else 1


if __name__ == '__main__': raise SystemExit(main())
