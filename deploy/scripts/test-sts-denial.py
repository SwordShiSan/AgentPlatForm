import importlib.util
import pathlib
import unittest

path = pathlib.Path(__file__).resolve().parents[1] / 'probes/sts_denial.py'
spec = importlib.util.spec_from_file_location('sts_denial', path)
denial = importlib.util.module_from_spec(spec)
spec.loader.exec_module(denial)


class StsDenialTests(unittest.TestCase):
    def test_missing_route_empty_refusal(self):
        denial.validate_denial(405, b'')

    def test_explicit_xml_denials(self):
        for status, code in [(403, 'AccessDenied'), (405, 'MethodNotAllowed'), (503, 'ServiceUnavailable')]:
            denial.validate_denial(status, ('<Error><Code>' + code + '</Code></Error>').encode())

    def test_success_and_unexpected_status_blocked(self):
        for status in [200, 301, 404, 500]:
            with self.assertRaises(AssertionError):
                denial.validate_denial(status, b'<AssumeRoleResponse/>')

    def test_empty_unavailable_or_malformed_body_is_not_denial(self):
        for status, body in [(503, b''), (403, b'not XML'), (405, b'garbage')]:
            with self.assertRaises(denial.ET.ParseError):
                denial.validate_denial(status, body)

    def test_tokens_even_in_error_status_blocked(self):
        with self.assertRaises(AssertionError):
            denial.validate_denial(403, b'<Error><Code>AccessDenied</Code><Credentials/></Error>')

    def test_unrelated_error_code_blocked(self):
        with self.assertRaises(AssertionError):
            denial.validate_denial(403, b'<Error><Code>InvalidAccessKeyId</Code></Error>')


if __name__ == '__main__':
    unittest.main()
