"""Meaningful fail-closed checks: bad product identity, changed SBOM, unrelated critical finding."""
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class Guards(unittest.TestCase):
    def test_workspace_empty_special_names_and_corruption(self):
        with tempfile.TemporaryDirectory() as temp:
            base = pathlib.Path(temp)
            work = base / 'workspace'
            work.mkdir()
            manifest = base / 'manifest.json'
            command = [sys.executable, str(ROOT / 'deploy/scripts/workspace-manifest.py')]
            subprocess.run(command + ['write', str(work), str(manifest)], check=True)
            subprocess.run(command + ['verify', str(work), str(manifest)], check=True)
            (work / 'empty directory').mkdir()
            names = ['space name.txt', '中文.txt'] + ([] if os.name == 'nt' else ['line\nbreak.txt'])
            for name in names:
                (work / name).write_bytes(b'fixture')
            subprocess.run(command + ['write', str(work), str(manifest)], check=True)
            subprocess.run(command + ['verify', str(work), str(manifest)], check=True)
            (work / names[0]).write_bytes(b'changed')
            self.assertNotEqual(0, subprocess.run(command + ['verify', str(work), str(manifest)]).returncode)

    def test_mutable_product_identity_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            run = subprocess.run([sys.executable, str(ROOT / 'deploy/scripts/mcp-applicability.py'), '--sbom', str(ROOT / 'backend/target/bom-production.json'), '--product', 'candidate:latest', '--output', str(pathlib.Path(temp) / 'vex.json')])
            self.assertNotEqual(0, run.returncode)

    def test_bound_vex_cannot_hide_unrelated_critical_or_changed_sbom(self):
        with tempfile.TemporaryDirectory() as temp:
            base = pathlib.Path(temp)
            sbom = base / 'bom.json'
            sbom.write_bytes((ROOT / 'backend/target/bom-production.json').read_bytes())
            vex = base / 'vex.json'
            product = 'urn:agentplatform:sbom:sha256:' + hashlib.sha256(sbom.read_bytes()).hexdigest()
            subprocess.run([sys.executable, str(ROOT / 'deploy/scripts/mcp-applicability.py'), '--sbom', str(sbom), '--product', product, '--output', str(vex)], check=True)
            report = base / 'raw.json'
            report.write_text(json.dumps({'matches': [{'vulnerability': {'id': 'FIXTURE-CRITICAL', 'severity': 'Critical'}, 'artifact': {'purl': 'pkg:maven/fixture/affected@1.0'}}]}))
            command = [sys.executable, str(ROOT / 'deploy/scripts/assess-mcp-report.py'), '--report', str(report), '--sbom', str(sbom), '--vex', str(vex), '--output', str(base / 'assessed.json')]
            self.assertNotEqual(0, subprocess.run(command).returncode)
            sbom.write_bytes(sbom.read_bytes() + b' ')
            self.assertNotEqual(0, subprocess.run(command).returncode)


if __name__ == '__main__':
    unittest.main()
