import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[1] / 'scripts/check-remote-python-payloads.py'
spec = importlib.util.spec_from_file_location('remote_payload_check', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RemotePayloadPreflightTest(unittest.TestCase):
    def inspect(self, source):
        return module.inspect_source(source, 'fixture.py')

    def test_outer_python_valid_but_inner_newline_invalid_is_caught(self):
        result = self.inspect("remote_run('''p.write_text('fixture_secret_value\\n')''')")
        self.assertEqual(result['literalPayloads'], 1)
        self.assertEqual(len(result['errors']), 1)
        self.assertNotIn('fixture_secret_value', result['errors'][0]['category'])

    def test_literal_escaped_newline_passes(self):
        result = self.inspect("remote_run('''p.write_text('line\\\\n')''')")
        self.assertFalse(result['errors'])

    def test_dynamic_payload_is_reported_not_claimed_valid(self):
        result = self.inspect('remote_run(prefix + body)')
        self.assertEqual(result['dynamicPayloadsNotChecked'], 1)
        self.assertEqual(result['literalPayloads'], 0)


if __name__ == '__main__':
    unittest.main()
