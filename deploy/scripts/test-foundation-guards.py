import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest

DIRECTORY = pathlib.Path(__file__).resolve().parent


def module(name):
    spec = importlib.util.spec_from_file_location(name, DIRECTORY / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class FoundationGuards(unittest.TestCase):
    def test_cached_input_drift_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / 'input.apk').write_bytes(b'corrupted')
            lock = {'archives': {'input.apk': {'sha256': 'a' * 64, 'url': 'https://example.invalid/input'}}}
            with self.assertRaises(ValueError):
                module('foundation-inputs').fetch_inputs(lock, root)

    def test_review_preserves_unrelated_critical_and_rejects_drift(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            source, evidence = root / 'source.txt', root / 'vendor.json'
            source.write_bytes(b'fixture source')
            evidence.write_bytes(b'fixture proof, not a production review')
            sbom, report, review = (root / name for name in ('bom.json', 'raw.json', 'review.json'))
            sbom.write_text('{}')
            product = 'urn:agentplatform:sbom:sha256:' + hashlib.sha256(sbom.read_bytes()).hexdigest()
            component = 'pkg:deb/debian/fixture@1.0?distro=debian-13'
            report.write_text(json.dumps({'matches': [
                {'vulnerability': {'id': 'CVE-2026-10001', 'severity': 'High'}, 'artifact': {'purl': component}},
                {'vulnerability': {'id': 'CVE-2026-10002', 'severity': 'Critical'}, 'artifact': {'purl': component}}]}))
            digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
            data = {'binding': {'product': product, 'reportSha256': digest(report), 'sbomSha256': digest(sbom),
                               'sources': {'source.txt': digest(source)}, 'evidence': {'vendor.json': digest(evidence)}},
                    'statements': [{'status': 'not_affected', 'justification': 'vulnerable_code_not_present',
                                    'impact_statement': 'Test fixture only', 'provenance': ['https://example.invalid/fixture'],
                                    'vulnerability': {'name': 'CVE-2026-10001'},
                                    'products': [{'@id': product, 'subcomponents': [{'@id': component}]}]}]}
            review.write_text(json.dumps(data))
            assessor = module('assess-applicability')
            self.assertEqual(1, assessor.assess(report, sbom, review, root)['unresolvedHighCritical'])
            evidence.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                assessor.assess(report, sbom, review, root)
            evidence.write_bytes(b'fixture proof, not a production review')
            data['binding']['product'] = 'candidate:latest'
            review.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                assessor.assess(report, sbom, review, root)


if __name__ == '__main__':
    unittest.main()
