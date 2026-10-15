"""Run after reactor install; produce actual per-module Maven resolution reports."""
import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
backend = ROOT / 'backend'
wrapper = str(backend / ('mvnw.cmd' if os.name == 'nt' else 'mvnw'))
for module in sorted(backend.glob('platform-*')):
    if not (module / 'pom.xml').exists():
        continue
    command = [wrapper, '-B', '-ntp', '-f', str(module / 'pom.xml'), 'dependency:tree',
               '-DoutputFile=' + str(module / 'target/dependency-tree.txt'), 'help:effective-pom',
               '-Doutput=' + str(module / 'target/effective-pom.xml')]
    if os.name != 'nt':
        command = ['sh'] + command
    subprocess.run(command, cwd=backend, check=True)
