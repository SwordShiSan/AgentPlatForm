import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

scripts = Path(os.environ.get('R2_RELEASE_SCRIPTS', Path(__file__).parents[1] / 'scripts'))
spec = importlib.util.spec_from_file_location('r2preflight', scripts / 'r2-release-preflight.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseAdmissionTest(unittest.TestCase):
    def valid(self):
        return dict(free=16 * module.GIB, incoming=200 * module.MIB,
                    expanded=512 * module.MIB, cold_data=100 * module.MIB,
                    physical=3655 * module.MIB, limits=2336 * module.MIB,
                    available=1000 * module.MIB)

    def test_exact_disk_boundary_and_one_byte_short(self):
        values = self.valid()
        required = module.validate_capacity(**values)
        values['free'] = required
        self.assertEqual(module.validate_capacity(**values), required)
        values['free'] -= 1
        with self.assertRaisesRegex(AssertionError, 'DISK_ADMISSION'):
            module.validate_capacity(**values)

    def test_host_and_container_memory_admission_are_independent(self):
        for changes, code in (({'limits': 2888 * module.MIB}, 'CONTAINER_LIMIT'),
                              ({'physical': 3000 * module.MIB}, 'PHYSICAL_MEMORY'),
                              ({'available': 768 * module.MIB - 1}, 'AVAILABLE_MEMORY')):
            with self.subTest(code=code):
                values = self.valid() | changes
                with self.assertRaisesRegex(AssertionError, code):
                    module.validate_capacity(**values)

    def test_receipt_never_overwrites_prior_evidence(self):
        cache = Path(os.environ.get('R2_TEST_TEMP', Path(__file__).resolve().parents[2] / '.cache'))
        cache.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=cache) as folder:
            path = Path(folder) / 'receipt.json'
            module.private_new(path, {'status': 'PASS'})
            original = path.read_bytes()
            with self.assertRaises(FileExistsError):
                module.private_new(path, {'status': 'FAIL'})
            self.assertEqual(path.read_bytes(), original)

    def test_other_project_unlimited_container_is_not_ignored(self):
        row = {'HostConfig': {'Memory': 0, 'MemorySwap': 0}}
        with self.assertRaisesRegex(AssertionError, 'UNBOUNDED_EXTERNAL'):
            module.validate_external_limits([row], 2336 * module.MIB, 3655 * module.MIB)

    def test_finite_other_project_counts_against_total_budget(self):
        row = {'HostConfig': {'Memory': 512 * module.MIB, 'MemorySwap': 512 * module.MIB}}
        self.assertEqual(module.validate_external_limits([row], 2336 * module.MIB, 3655 * module.MIB), 512 * module.MIB)
        with self.assertRaisesRegex(AssertionError, 'COEXISTING_CONTAINER_LIMIT'):
            module.validate_external_limits([row, row], 2336 * module.MIB, 3655 * module.MIB)


if __name__ == '__main__':
    unittest.main()
