"""Frozen R53 reactor/profile/package gates. Physical PG/MQ/S3/runsc/browser receipts are separate."""
from contextlib import contextmanager
from pathlib import Path
from zipfile import ZipFile
import argparse, hashlib, importlib.util, json, os, re, shutil, subprocess, sys, time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r53-local'
JAVA = ROOT / '.tools/jdk-21.0.12.1/jdk-21.0.12.1+1'
UPSTREAM = ['platform-types', 'platform-domain', 'platform-api', 'platform-infrastructure', 'platform-trigger']


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


def command(prepare, resume=False):
    base = [str(ROOT / 'backend/mvnw.cmd'), '-B', '-ntp', '-f', str(ROOT / 'backend/pom.xml'), '-Ppreview-renderer']
    if prepare:
        # Resolve the new profile plugins/dependencies without compilation, tests, migrations or external systems.
        return base + ['-pl', 'platform-app', 'org.codehaus.mojo:build-helper-maven-plugin:3.6.1:add-source@renderer-source',
                       'org.codehaus.mojo:build-helper-maven-plugin:3.6.1:add-test-source@renderer-tests',
                       'org.apache.maven.plugins:maven-install-plugin:3.1.4:help',
                       'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies@renderer-libraries']
    return base + ['-o', 'verify'] + (['-rf', ':platform-app'] if resume else [])


def resume_proof(prior_path, current, output):
    """Reuse only intact upstream reports from a frozen failure before app tests; never reuse local-repo guesses."""
    prior_path = prior_path.resolve(strict=True)
    if not prior_path.is_relative_to(DATA.resolve()) or prior_path.name != 'result.json':
        raise RuntimeError('Resume receipt must be an existing protected R53 result')
    prior = json.loads(prior_path.read_text(encoding='utf-8'))
    original = json.loads((prior_path.parent / 'source-before.json').read_text(encoding='utf-8'))
    if not prior.get('inputUnchanged') or not prior.get('revisionUnchanged') or prior.get('profile') != 'preview-renderer' or prior.get('status') != 'FAIL':
        raise RuntimeError('Prior receipt is not a frozen failed whole-reactor run')
    if prior.get('sourceInputSha256') != original.get('sourceInputSha256') or original['sourceInputFiles'] != current['sourceInputFiles']:
        raise RuntimeError('Backend or renderer package inputs changed; app-only resume is forbidden')
    prior_log = prior_path.parent / 'maven.log'
    if digest(prior_log) != prior['mavenLogSha256']: raise RuntimeError('Prior Maven log changed')
    text = prior_log.read_text(encoding='utf-8', errors='replace')
    for module in ['platform-parent', *UPSTREAM]:
        if not re.search(r'(?m)^\[INFO\]\s+' + re.escape(module) + r'\s+\.+\s+SUCCESS\b', text):
            raise RuntimeError('Prior upstream lifecycle was not successful: ' + module)
    app_section = text.rsplit('Building platform-app ', 1)[-1]
    if not re.search(r'(?m)^\[INFO\]\s+platform-app\s+\.+\s+FAILURE\b', app_section) or '--- surefire:' in app_section:
        raise RuntimeError('Prior failure is not before app test execution')
    tests = prior.get('tests', {})
    if tests.get('failures') or tests.get('errors') or not tests.get('tests') or not tests.get('reports'):
        raise RuntimeError('Prior upstream tests are missing or failed')
    records = []
    for item in tests['reports']:
        relative = Path(item['path'].replace('\\', '/'))
        if len(relative.parts) < 5 or relative.parts[0] != 'backend' or relative.parts[1] not in UPSTREAM or relative.parts[2:4] != ('target', 'surefire-reports'):
            raise RuntimeError('Prior report is not an upstream Surefire result')
        source = (ROOT / relative).resolve(strict=True)
        if not source.is_relative_to((ROOT / 'backend' / relative.parts[1] / 'target/surefire-reports').resolve()) or digest(source) != item['sha256']:
            raise RuntimeError('Prior report bytes changed; no stale report reuse')
        saved = output / 'reused-upstream-reports' / relative.parts[1] / source.name
        saved.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, saved)
        record = dict(item, reusedFromReceiptSha256=digest(prior_path), preservedPath=str(saved.relative_to(ROOT)))
        records.append(record)
    modules = {}
    for module in ['platform-parent', *UPSTREAM]:
        relevant = {name: sha for name, sha in current['sourceInputFiles'].items() if name == 'backend/pom.xml' or
                    name.startswith('backend/.mvn/') or module != 'platform-parent' and name.startswith('backend/' + module + '/')}
        modules[module] = hashlib.sha256(json.dumps(relevant, sort_keys=True).encode()).hexdigest()
    proof = {'status': 'PASS_REUSED', 'priorReceipt': str(prior_path.relative_to(ROOT)), 'priorReceiptSha256': digest(prior_path),
             'priorReceiptStatus': prior['status'], 'priorSource': prior['source'], 'sourceInputSha256': original['sourceInputSha256'],
             'upstreamModuleInputSha256': modules, 'tests': sum(item['tests'] for item in records), 'failures': 0, 'errors': 0,
             'skipped': sum(item['skipped'] for item in records), 'reports': records}
    (output / 'reused-upstream-proof.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    return proof


def install_verified_upstream(env, output):
    """Install these exact-source reactor artifacts, then compare every installed JAR/POM to its actual build output."""
    selected = [str(ROOT / 'backend/mvnw.cmd'), '-B', '-ntp', '-o', '-f', str(ROOT / 'backend/pom.xml'),
                '-Ppreview-renderer', '-pl', '!platform-app', '-DskipTests', 'install']
    version = ET.parse(ROOT / 'backend/pom.xml').getroot().findtext('{http://maven.apache.org/POM/4.0.0}version')
    local = Path.home() / '.m2/repository/com/agentplatform'
    prior_artifacts = {}
    for module in ['platform-parent', *UPSTREAM]:
        target = ROOT / 'backend' / module / 'target' / (module + '-' + version + '.jar')
        installed = local / module / version / target.name
        prior_artifacts[module] = {'targetJarSha256': digest(target) if target.is_file() else None,
                                   'installedJarSha256': digest(installed) if installed.is_file() else None}
    (output / 'upstream-artifacts-before.json').write_text(json.dumps(prior_artifacts, indent=2) + '\n', encoding='utf-8')
    log = output / 'upstream-install.log'
    with log.open('wb') as stream: process = subprocess.run(selected, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
    record = {'exitCode': process.returncode, 'command': selected, 'logSha256': digest(log),
              'scope': 'Fresh exact-source package/install only; skipped tests do not supply additional PASS results',
              'beforeArtifactSha256': digest(output / 'upstream-artifacts-before.json'), 'artifacts': []}
    if process.returncode: return record
    for module in ['platform-parent', *UPSTREAM]:
        original = ROOT / 'backend' / ('pom.xml' if module == 'platform-parent' else module + '/pom.xml')
        installed = local / module / version / (module + '-' + version + '.pom')
        if not installed.is_file() or digest(original) != digest(installed): raise RuntimeError('Fresh upstream POM was not installed: ' + module)
        artifact = {'module': module, 'pomSha256': digest(original)}
        if module != 'platform-parent':
            target = ROOT / 'backend' / module / 'target' / (module + '-' + version + '.jar')
            copied = local / module / version / target.name
            if not target.is_file() or not copied.is_file() or digest(target) != digest(copied): raise RuntimeError('Fresh upstream JAR was not installed: ' + module)
            artifact.update(jarSha256=digest(target), installedJar=str(copied))
        record['artifacts'].append(artifact)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--prepare-dependencies', action='store_true', help='Online resolve only; never claims engineering verification')
    mode.add_argument('--resume-app-from', type=Path, help='Reuse intact upstream reports and verify app only with freshly installed exact-source upstream artifacts')
    args = parser.parse_args()
    with controller_lock():
        output = DATA / (('engineering-prepare-' if args.prepare_dependencies else 'engineering-') + str(time.time_ns()))
        output.mkdir(); before = source_identity(); env = child_environment(); selected = command(args.prepare_dependencies, bool(args.resume_app_from))
        (output / 'source-before.json').write_text(json.dumps(before, indent=2) + '\n', encoding='utf-8')
        (output / 'invocation.json').write_text(json.dumps({'controllerPid': os.getpid(), 'command': selected, 'javaHome': str(JAVA),
            'profile': 'preview-renderer', 'supplierCalls': 0}, indent=2) + '\n', encoding='utf-8')
        report_before = report_snapshot(); started = time.monotonic(); log = output / 'maven.log'
        checks = {}; error = None; reused = None; exit_code = 1
        try:
            if args.resume_app_from:
                reused = resume_proof(args.resume_app_from, before, output)
                checks['upstreamInstall'] = install_verified_upstream(env, output)
                (output / 'upstream-install.json').write_text(json.dumps(checks['upstreamInstall'], indent=2) + '\n', encoding='utf-8')
                if checks['upstreamInstall']['exitCode']: raise RuntimeError('Upstream install failed; app verification was not started')
                if source_identity()['sourceInputFiles'] != before['sourceInputFiles']: raise RuntimeError('Backend inputs changed during upstream install')
                # Install deliberately skipped tests. Only fresh app XML can enter the new test set.
                report_before = report_snapshot()
            with log.open('wb') as stream: exit_code = subprocess.run(selected, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT).returncode
        except Exception as failed:
            error = str(failed)
            if not log.exists(): log.write_text('Verification not started: ' + error + '\n', encoding='utf-8')
        if exit_code == 0:
            try:
                if not args.prepare_dependencies: checks['packages'] = packages()
                lock_log = output / 'renderer-lock.log'
                with lock_log.open('wb') as stream:
                    checked = subprocess.run([sys.executable, str(ROOT / 'deploy/scripts/preview-renderer-lock.py')], cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
                checks['rendererLock'] = {'status': 'PASS' if checked.returncode == 0 else 'FAIL', 'exitCode': checked.returncode, 'logSha256': digest(lock_log)}
                if checked.returncode: raise RuntimeError('Renderer classpath hash lock failed')
                if args.prepare_dependencies:
                    annotation = Path.home() / '.m2/repository/com/google/errorprone/error_prone_annotations/2.41.0/error_prone_annotations-2.41.0.jar'
                    if not annotation.is_file(): raise RuntimeError('Managed annotation JAR was not actually prepared')
                    checks['managedAnnotation'] = {'coordinate': 'com.google.errorprone:error_prone_annotations:2.41.0', 'sha256': digest(annotation)}
            except Exception as failed: error = str(failed)
        else: checks['packages'] = {'status': 'NOT_RUN', 'reason': 'Maven failed; stale artifacts are not acceptance evidence'}
        after = source_identity(); unchanged = before['sourceInputFiles'] == after['sourceInputFiles']
        (output / 'source-after.json').write_text(json.dumps(after, indent=2) + '\n', encoding='utf-8')
        revision_unchanged = before['commit'] == after['commit']
        success = exit_code == 0 and error is None and ((unchanged and revision_unchanged) or args.prepare_dependencies)
        result = {'status': ('PREPARED_NOT_VERIFIED' if args.prepare_dependencies else 'PASS') if success else 'FAIL',
                  'exitCode': exit_code, 'controllerExitCode': 0 if success else 1, 'inputUnchanged': unchanged,
                  'revisionUnchanged': revision_unchanged, 'source': before['commit'],
                  'sourceInputSha256': before['sourceInputSha256'], 'afterSourceInputSha256': after['sourceInputSha256'],
                  'seconds': round(time.monotonic() - started, 3), 'supplierCalls': 0, 'mavenLogSha256': digest(log),
                  'profile': 'preview-renderer', 'checks': checks, 'error': error,
                  'scope': 'Profile/Enforcer/DDD/Mapper/unit/package gates only. External and skipped gates remain separate NOT_RUN until their actual receipts pass.'}
        if not args.prepare_dependencies:
            actual_tests = test_reports(report_before)
            if reused is not None:
                app_records = [item for item in actual_tests['reports'] if item['path'].replace('\\', '/').startswith('backend/platform-app/')]
                combined = reused['reports'] + app_records
                result['tests'] = {name: sum(item[name] for item in combined) for name in ['tests', 'failures', 'errors', 'skipped']}
                result['tests'].update(reports=combined, reusedUpstreamTests=reused['tests'], freshAppTests=sum(item['tests'] for item in app_records),
                                       skippedMeaning='Install skips are not PASS. Actual skipped tests remain NOT_RUN.')
                result['resume'] = {key: value for key, value in reused.items() if key != 'reports'}
            else: result['tests'] = actual_tests
            if success and (result['tests']['failures'] or result['tests']['errors'] or reused is not None and not result['tests']['freshAppTests']):
                success = False; result.update(status='FAIL', controllerExitCode=1, error='Fresh app tests are missing or reported failures')
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        summary = {key: value for key, value in result.items() if key != 'tests'}
        if 'tests' in result: summary['tests'] = {key: value for key, value in result['tests'].items() if key != 'reports'}
        print(output); print(json.dumps(summary, ensure_ascii=True))
        if not success: print(log.read_text(encoding='utf-8', errors='replace')[-7000:])
        return 0 if success else 1


if __name__ == '__main__': raise SystemExit(main())
