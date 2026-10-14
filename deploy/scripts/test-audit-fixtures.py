import hashlib
import importlib.util
import io
import pathlib
import tarfile
import tempfile
import unittest

script = pathlib.Path(__file__).with_name('audit-fixture-cache.py')
spec = importlib.util.spec_from_file_location('fixtures', script)
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)


class FixtureGuards(unittest.TestCase):
    def test_oras_raw_fingerprint_file_is_not_a_tar_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp).resolve()
            blob = root / 'raw-file'
            blob.write_bytes(b'{"digest":"fixture"}')
            layer = {'annotations': {'org.opencontainers.image.title': '.tmp/cache_paths.json'}}
            fixtures.restore_layer(blob, layer, root)
            self.assertEqual(blob.read_bytes(), (root / '.tmp/cache_paths.json').read_bytes())
            layer['annotations']['org.opencontainers.image.title'] = 'unexpected.json'
            with self.assertRaisesRegex(ValueError, 'Unsupported frozen fixture'):
                fixtures.restore_layer(blob, layer, root)
    def test_unlocked_source_symlink_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp).resolve()
            blob = root / 'source.tar'
            with tarfile.open(blob, 'w') as archive:
                item = tarfile.TarInfo('source/testdata/link')
                item.type = tarfile.SYMTYPE
                item.linkname = '/outside'
                archive.addfile(item)
            with tarfile.open(blob) as archive, self.assertRaisesRegex(ValueError, 'fixture links changed'):
                fixtures.extract_source(archive, root, 'source', {})
            self.assertFalse((root / 'source').exists())
    def test_archive_layouts_remain_in_named_directory(self):
        for name in ['fixtures/cache/a.txt', 'cache/a.txt', 'a.txt']:
            with tempfile.TemporaryDirectory() as temp:
                root = pathlib.Path(temp).resolve()
                blob = root / 'layer.tar'
                with tarfile.open(blob, 'w') as archive:
                    item = tarfile.TarInfo(name)
                    item.size = 3
                    archive.addfile(item, io.BytesIO(b'abc'))
                fixtures.unpack(blob, 'fixtures/cache', root)
                self.assertEqual(b'abc', (root / 'fixtures/cache/a.txt').read_bytes())

    def test_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp).resolve()
            blob = root / 'layer.tar'
            with tarfile.open(blob, 'w') as archive:
                archive.addfile(tarfile.TarInfo('../../escape'))
            with self.assertRaises(ValueError):
                fixtures.unpack(blob, 'fixtures/cache', root)

    def test_fingerprint_requires_original_source_not_restored_cache(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp).resolve()
            (root / 'fixtures').mkdir()
            (root / 'fixtures/input.c').write_bytes(b'original')
            digest = hashlib.sha256(b'original').hexdigest()
            manifest = {'annotations': {'fingerprint': 'frozen'}, 'layers': [
                {'annotations': {'org.opencontainers.image.title': 'fixtures/cache'}}]}
            paths = {'digest': 'frozen', 'paths': [{'path': 'fixtures/cache', 'input': {'input.c': digest}}]}
            result = fixtures.verify_fingerprints(root, manifest, paths, {'fixtures/input.c': digest})
            self.assertEqual('fixtures/input.c', result[0]['path'])
            with self.assertRaisesRegex(ValueError, 'source changed or absent'):
                fixtures.verify_fingerprints(root, manifest, paths, {})
            (root / 'fixtures/input.c').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'source changed or absent'):
                fixtures.verify_fingerprints(root, manifest, paths, {'fixtures/input.c': digest})

    def test_unrelated_manifest_fingerprint_rejected(self):
        with self.assertRaisesRegex(ValueError, 'manifest fingerprint mismatch'):
            fixtures.verify_fingerprints(pathlib.Path('.'), {'annotations': {'fingerprint': 'a'}}, {'digest': 'b'})


if __name__ == '__main__':
    unittest.main()
