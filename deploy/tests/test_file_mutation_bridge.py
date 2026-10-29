import importlib.util
from pathlib import Path
import tempfile
import unittest
import uuid

spec = importlib.util.spec_from_file_location('mutation', Path(__file__).resolve().parents[1] / 'scripts/file-mutation-bridge.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class MutationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'source').mkdir()
        (self.root / 'source/中文.txt').write_bytes('hello😀'.encode())
        self.hash = m.digest(m.inventory(self.root, 'source'))

    def test_tree_move_preserves_inventory_and_identity(self):
        result = m.move(self.root, 'source', 'dest', self.hash)
        self.assertEqual('APPLIED', result['state'])
        self.assertEqual(self.hash, m.digest(m.inventory(self.root, 'dest')))

    def test_existing_target_is_never_overwritten(self):
        (self.root / 'dest').mkdir()
        with self.assertRaisesRegex(m.Rejected, 'FILE_NAME_CONFLICT'):
            m.move(self.root, 'source', 'dest', self.hash)
        self.assertTrue((self.root / 'source/中文.txt').exists())

    def test_stale_manifest_has_no_effect(self):
        (self.root / 'source/中文.txt').write_bytes(b'changed')
        with self.assertRaisesRegex(m.Rejected, 'FILE_VERSION_CONFLICT'):
            m.move(self.root, 'source', 'dest', self.hash)

    def test_delete_isolation_query_does_not_replay(self):
        op = str(uuid.uuid4())
        m.isolate(self.root, 'source', op, self.hash)
        self.assertFalse((self.root / 'source').exists())
        for _ in range(2):
            self.assertEqual('ISOLATED', m.query_isolation(self.root, op, self.hash)['state'])
        self.assertTrue((self.root / m.AREA / op / 'payload/中文.txt').exists())

    def test_links_and_self_descendant_rejected(self):
        (self.root / 'source/link').symlink_to('/etc/passwd')
        with self.assertRaises(m.Rejected):
            m.inventory(self.root, 'source')
        with self.assertRaisesRegex(m.Rejected, 'FILE_MOVE_INTO_SELF'):
            m.move(self.root, 'source', 'source/nested', self.hash)

    def test_bounded_inventory(self):
        for i in range(999):
            (self.root / 'source' / str(i)).touch()
        with self.assertRaisesRegex(m.Rejected, 'FILE_BATCH_TOO_LARGE'):
            m.inventory(self.root, 'source')

    def test_reserved_paths_rejected(self):
        with self.assertRaisesRegex(m.Rejected, 'FILE_RESERVED_PATH'):
            m.path(m.AREA + '/arbitrary')

    def test_cleanup_receipt_is_idempotent(self):
        op = str(uuid.uuid4())
        m.isolate(self.root, 'source', op, self.hash)
        self.assertEqual('CLEANED', m.cleanup(self.root, op, self.hash)['state'])
        self.assertEqual('CLEANED', m.cleanup(self.root, op, self.hash)['state'])
        self.assertFalse((self.root / m.AREA / op / 'payload').exists())

    def test_changed_payload_is_not_cleanup_permission(self):
        op = str(uuid.uuid4())
        m.isolate(self.root, 'source', op, self.hash)
        (self.root / m.AREA / op / 'payload/中文.txt').write_bytes(b'late change')
        self.assertEqual('UNKNOWN', m.query_isolation(self.root, op, self.hash)['state'])
        with self.assertRaisesRegex(m.Rejected, 'FILE_RESULT_UNKNOWN'):
            m.cleanup(self.root, op, self.hash)

    def test_existing_user_operation_area_not_adopted(self):
        (self.root / m.AREA).mkdir()
        with self.assertRaisesRegex(m.Rejected, 'FILE_RESERVED_PATH_CONFLICT'):
            m.isolate(self.root, 'source', str(uuid.uuid4()), self.hash)
        self.assertTrue((self.root / 'source').exists())

    def test_no_receipt_after_cleanup_is_unknown(self):
        op = str(uuid.uuid4())
        m.isolate(self.root, 'source', op, self.hash)
        m.cleanup(self.root, op, self.hash)
        (self.root / m.AREA / op / 'cleanup.json').unlink()
        self.assertEqual('UNKNOWN', m.query_isolation(self.root, op, self.hash)['state'])
        with self.assertRaisesRegex(m.Rejected, 'FILE_RESULT_UNKNOWN'):
            m.cleanup(self.root, op, self.hash)

    def test_intent_without_rename_cannot_be_repeated(self):
        from unittest.mock import patch
        op = str(uuid.uuid4())
        with patch.object(m, 'no_replace', side_effect=RuntimeError('interrupted')):
            with self.assertRaises(RuntimeError):
                m.isolate(self.root, 'source', op, self.hash)
        self.assertEqual('UNKNOWN', m.query_isolation(self.root, op, self.hash)['state'])
        with self.assertRaises(FileExistsError):
            m.isolate(self.root, 'source', op, self.hash)
        self.assertTrue((self.root / 'source').exists())


if __name__ == '__main__':
    unittest.main()
