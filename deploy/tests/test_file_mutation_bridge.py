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

    def header(self, kind, operation):
        import os
        rows=m.inventory(self.root,'source')
        public=[{k:v for k,v in row.items() if k not in ('device','inode')} for row in rows]
        manifest=m.encoded(public).decode()
        device=self.root.stat().st_dev
        return {'protocol':'file-mutation-v1','operationId':operation,'kind':kind,'sourcePath':'source','targetPath':'dest',
                'manifestJson':manifest,'manifestHash':m.hashlib.sha256(manifest.encode()).hexdigest(),
                'filesystemDevice':[os.major(device),os.minor(device)]}

    def test_versioned_move_command_uses_semantic_manifest(self):
        header=self.header('MOVE',str(uuid.uuid4()))
        result=m.execute(header,self.root)
        self.assertEqual('MUTATED',result['state'])
        self.assertEqual(header['manifestHash'],result['manifestHash'])
        self.assertEqual(2,result['entries'])

    def test_delete_and_separate_cleanup_command(self):
        parent=str(uuid.uuid4())
        header=self.header('DELETE',parent)
        self.assertEqual('MUTATED',m.execute(header,self.root)['state'])
        self.assertTrue((self.root/m.AREA/parent/'payload').exists())
        header.update(operationId=str(uuid.uuid4()),kind='DELETE_CLEANUP',parentOperationId=parent)
        self.assertEqual('MUTATED',m.execute(header,self.root)['state'])
        self.assertFalse((self.root/m.AREA/parent/'payload').exists())

    def test_tampered_command_rejected_before_moving(self):
        header=self.header('MOVE',str(uuid.uuid4()))
        header['manifestJson']='[]'
        with self.assertRaisesRegex(m.Rejected,'FILE_RECEIPT_MISMATCH'):
            m.execute(header,self.root)
        self.assertTrue((self.root/'source').exists())

    def restore_header(self,kind,manifest,target='restored.txt'):
        import os
        raw=m.encoded(manifest).decode();device=self.root.stat().st_dev
        return {'protocol':'file-mutation-v1','operationId':str(uuid.uuid4()),'kind':kind,'sourcePath':'source/中文.txt',
                'targetPath':target,'manifestJson':raw,'manifestHash':m.hashlib.sha256(raw.encode()).hexdigest(),
                'filesystemDevice':[os.major(device),os.minor(device)]}

    def test_active_restore_reads_stream_and_replaces_exact_base(self):
        import io
        original=(self.root/'source/中文.txt').read_bytes();data=b'historical bytes'
        header=self.restore_header('RESTORE_FILE',{'sourceMissing':False,'expectedSha256':m.hashlib.sha256(original).hexdigest()},'source/中文.txt')
        header.update(bytes=len(data),sha256=m.hashlib.sha256(data).hexdigest())
        self.assertEqual('MUTATED',m.execute(header,self.root,io.BytesIO(data+m.hashlib.sha256(data).digest()))['state'])
        self.assertEqual(data,(self.root/'source/中文.txt').read_bytes())

    def test_missing_restore_creates_without_overwrite(self):
        import io
        data=b'preserved version'
        header=self.restore_header('RESTORE_FILE',{'sourceMissing':True,'expectedSha256':None})
        header.update(bytes=len(data),sha256=m.hashlib.sha256(data).hexdigest())
        m.execute(header,self.root,io.BytesIO(data+m.hashlib.sha256(data).digest()))
        with self.assertRaises(m.Rejected):m.execute(header,self.root,io.BytesIO(data+m.hashlib.sha256(data).digest()))
        self.assertEqual(data,(self.root/'restored.txt').read_bytes())

    def test_alternate_restore_failure_after_move_is_unknown(self):
        import io
        original=(self.root/'source/中文.txt').read_bytes()
        header=self.restore_header('RESTORE_FILE',{'sourceMissing':False,'expectedSha256':m.hashlib.sha256(original).hexdigest()})
        header.update(bytes=10,sha256='a'*64)
        with self.assertRaisesRegex(m.Rejected,'FILE_RESULT_UNKNOWN'):
            m.execute(header,self.root,io.BytesIO(b'cut'))
        self.assertEqual(original,(self.root/'restored.txt').read_bytes())

    def test_tree_restore_atomically_publishes_hash_checked_files_and_empty_directories(self):
        import io
        data='中文😀'.encode()
        manifest=[{'kind':'DIRECTORY','path':''},{'kind':'DIRECTORY','path':'empty'},
            {'kind':'FILE','path':'text.txt','bytes':len(data),'sha256':m.hashlib.sha256(data).hexdigest()},
            {'kind':'FILE','path':'zero','bytes':0,'sha256':m.hashlib.sha256(b'').hexdigest()}]
        header=self.restore_header('RESTORE_TREE',manifest,'tree')
        result=m.execute(header,self.root,io.BytesIO(data+bytes.fromhex(header['manifestHash'])))
        self.assertEqual(4,result['entries']);self.assertEqual(data,(self.root/'tree/text.txt').read_bytes())
        self.assertTrue((self.root/'tree/empty').is_dir());self.assertEqual(b'',(self.root/'tree/zero').read_bytes())

    def test_failed_tree_stream_cleans_own_staging_and_never_publishes_partial_tree(self):
        import io
        manifest=[{'kind':'DIRECTORY','path':''},{'kind':'FILE','path':'file','bytes':20,'sha256':'a'*64}]
        header=self.restore_header('RESTORE_TREE',manifest,'tree')
        with self.assertRaisesRegex(m.Rejected,'FILE_CONTENT_MISMATCH'):
            m.execute(header,self.root,io.BytesIO(b'cut'))
        self.assertFalse((self.root/'tree').exists())
        self.assertFalse((self.root/m.AREA/header['operationId']/'payload').exists())


if __name__ == '__main__':
    unittest.main()
