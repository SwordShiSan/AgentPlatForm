"""Pinned-JDK local verification with an atomic-move preflight and truthful exit evidence.

Run from the authorized host when the known Windows execution sandbox restricts
SDK state files. It neither changes test assertions nor accesses Docker/production.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path, help='New evidence directory below repository .cache')
    args = parser.parse_args()
    java_home = args.java_home.resolve()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / '.cache') or output.exists():
        parser.error('Use a new evidence directory below this repository .cache')
    java = java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    if not java.is_file():
        parser.error('Pinned JDK executable is missing; do not install another toolchain')
    output.mkdir(parents=True)
    temporary = output / 'java-temp'
    temporary.mkdir()
    env = os.environ.copy()
    env.update(JAVA_HOME=str(java_home), PATH=str(java_home / 'bin') + os.pathsep + env['PATH'])
    env['JAVA_TOOL_OPTIONS'] = '-Djava.io.tmpdir="' + str(temporary) + '"'
    records = []
    code = 1

    def run(name, command):
        path = output / (name + '.log')
        start = time.monotonic()
        with path.open('xb') as log:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        record = {'name': name, 'exitCode': result.returncode, 'elapsedSeconds': round(time.monotonic() - start, 3),
                  'log': str(path.relative_to(ROOT)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        records.append(record)
        print(json.dumps(record), flush=True)
        return result.returncode

    try:
        code = run('atomic-move-preflight', [str(java), str(ROOT / 'deploy/probes/AtomicMovePreflight.java'), str(temporary)])
        if code == 0:
            wrapper = ROOT / 'backend' / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
            code = 1  # An interrupted subprocess must never inherit the successful preflight status.
            code = run('maven-verify', [str(wrapper), '-B', '-ntp', '-f', str(ROOT / 'backend/pom.xml'), 'verify'])
        return code
    finally:
        (output / 'result.json').write_text(json.dumps({'status': 'PASS' if code == 0 else 'FAIL', 'exitCode': code,
                'sourceBase': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'workingTreeClean': not bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
                'supplierCalls': 0, 'commands': records}, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    raise SystemExit(main())
