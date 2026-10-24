import hashlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import patch
import errno
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location("bridge", Path(__file__).parents[1] / "scripts/file-stream-bridge.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class StreamBridgeTests(unittest.TestCase):
    def test_insufficient_temporary_blocks_or_inodes_preserve_original_without_reading_content(self):
        for available in [SimpleNamespace(f_frsize=4096, f_bsize=4096, f_bavail=0, f_favail=100),
                          SimpleNamespace(f_frsize=4096, f_bsize=4096, f_bavail=100, f_favail=0)]:
            with self.subTest(available=available), tempfile.TemporaryDirectory() as root:
                target=Path(root, "a.txt");target.write_bytes(b"old")
                content=io.BytesIO(b"new")
                with patch.object(bridge.os,"fstatvfs",return_value=available):
                    with self.assertRaisesRegex(bridge.Rejected,"FILE_STORAGE_FULL"):
                        bridge.apply(self.header(b"new",previous=hashlib.sha256(b"old").hexdigest()),content,root)
                self.assertEqual(content.tell(),0)
                self.assertEqual(target.read_bytes(),b"old")
                self.assertEqual([p.name for p in Path(root).iterdir()],["a.txt"])

    def test_replacement_requires_entire_new_temporary_file_and_rounds_up_blocks(self):
        with tempfile.TemporaryDirectory() as root:
            original=b"a"*8192;Path(root,"a.txt").write_bytes(original)
            content=b"b"*4097
            available=SimpleNamespace(f_frsize=4096,f_bsize=4096,f_bavail=1,f_favail=100)
            with patch.object(bridge.os,"fstatvfs",return_value=available):
                with self.assertRaisesRegex(bridge.Rejected,"FILE_STORAGE_FULL"):
                    bridge.apply(self.header(content,previous=hashlib.sha256(original).hexdigest()),io.BytesIO(content),root)
            self.assertEqual(Path(root,"a.txt").read_bytes(),original)

    def test_v2_completes_without_reading_for_transport_eof(self):
        class OpenFrame(io.BytesIO):
            def read(self, size=-1):
                if self.tell() == len(self.getvalue()):
                    raise AssertionError("must not wait for transport EOF")
                return super().read(min(size, 7))  # Partial reads are legal.
        for content in [b"", "中文😀".encode(), b"a" * 131073]:
            with self.subTest(size=len(content)), tempfile.TemporaryDirectory() as root:
                header=self.header(content);header["protocol"]="file-stream-v2"
                wire=OpenFrame(content+hashlib.sha256(content).digest())
                result=bridge.apply(header,wire,root)
                self.assertEqual(result["state"],"APPLIED")
                self.assertEqual(Path(root,"a.txt").read_bytes(),content)

    def test_v2_missing_or_corrupt_trailer_preserves_original(self):
        content=b"new"
        for trailer in [b"", b"a"*31, b"a"*32]:
            with self.subTest(trailer=len(trailer)), tempfile.TemporaryDirectory() as root:
                Path(root,"a.txt").write_bytes(b"old")
                header=self.header(content,previous=hashlib.sha256(b"old").hexdigest())
                header["protocol"]="file-stream-v2"
                with self.assertRaisesRegex(bridge.Rejected,"FILE_CONTENT_MISMATCH"):
                    bridge.apply(header,io.BytesIO(content+trailer),root)
                self.assertEqual(Path(root,"a.txt").read_bytes(),b"old")
                self.assertEqual(len(list(Path(root).iterdir())),1)

    def header(self, content, path="a.txt", previous=None):
        return {"protocol": "file-stream-v1", "operationId": str(uuid.uuid4()), "path": path,
                "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest(), "expectedSha256": previous}

    def test_create_replace_and_conflict(self):
        with tempfile.TemporaryDirectory() as root:
            original = b"first"
            bridge.apply(self.header(original), io.BytesIO(original), root)
            with self.assertRaisesRegex(bridge.Rejected, "FILE_NAME_CONFLICT"):
                bridge.apply(self.header(b"bad"), io.BytesIO(b"bad"), root)
            replacement = "中文666".encode()
            bridge.apply(self.header(replacement, previous=hashlib.sha256(original).hexdigest()), io.BytesIO(replacement), root)
            self.assertEqual(Path(root, "a.txt").read_bytes(), replacement)
            with self.assertRaisesRegex(bridge.Rejected, "FILE_VERSION_CONFLICT"):
                bridge.apply(self.header(b"bad", previous=hashlib.sha256(original).hexdigest()), io.BytesIO(b"bad"), root)

    def test_truncated_oversized_wrong_hash_preserve_original_and_clean_temp(self):
        for content in [b"", b"abcdef", b"xyz"]:
            with tempfile.TemporaryDirectory() as root:
                Path(root, "a.txt").write_bytes(b"original")
                h = self.header(b"abc", previous=hashlib.sha256(b"original").hexdigest())
                with self.assertRaisesRegex(bridge.Rejected, "FILE_CONTENT_MISMATCH"):
                    bridge.apply(h, io.BytesIO(content), root)
                self.assertEqual(Path(root, "a.txt").read_bytes(), b"original")
                self.assertEqual([p.name for p in Path(root).iterdir()], ["a.txt"])

    def test_zero_byte_file_and_path_boundaries(self):
        with tempfile.TemporaryDirectory() as root:
            bridge.apply(self.header(b"", path="空😀.txt"), io.BytesIO(), root)
            self.assertEqual(Path(root, "空😀.txt").stat().st_size, 0)
            for path in ["../escape", "/absolute", "a//b", "a/./b", "a\\b", "e\u0301.txt"]:
                with self.subTest(path=path), self.assertRaises(bridge.Rejected):
                    bridge.apply(self.header(b"a", path=path), io.BytesIO(b"a"), root)

    def test_existing_decomposed_name_is_not_silently_aliased(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root,"e\u0301.txt").write_bytes(b"old")
            with self.assertRaisesRegex(bridge.Rejected,"FILE_NAME_NORMALIZATION_CONFLICT"):
                bridge.apply(self.header(b"new",path="é.txt"),io.BytesIO(b"new"),root)
            self.assertEqual(len(list(Path(root).iterdir())),1)

    def test_late_new_target_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            original=bridge.rename_new
            def create_competitor(parent, temporary, name):
                Path(root,name).write_bytes(b"competitor")
                return original(parent,temporary,name)
            with patch.object(bridge,"rename_new",side_effect=create_competitor):
                with self.assertRaisesRegex(bridge.Rejected,"FILE_NAME_CONFLICT"):
                    bridge.apply(self.header(b"our-content"),io.BytesIO(b"our-content"),root)
            self.assertEqual(Path(root,"a.txt").read_bytes(),b"competitor")
            self.assertEqual(len(list(Path(root).iterdir())),1)

    def test_cli_device_guard_refuses_missing_or_different_mount_before_writing(self):
        with tempfile.TemporaryDirectory() as root:
            header=self.header(b"x")
            with self.assertRaisesRegex(bridge.Rejected,"FILE_MOUNT_REJECTED"):
                bridge.apply(header,io.BytesIO(b"x"),root,require_device=True)
            device=os.stat(root).st_dev
            header["filesystemDevice"]=[os.major(device),os.minor(device)+1]
            with self.assertRaisesRegex(bridge.Rejected,"FILE_MOUNT_REJECTED"):
                bridge.apply(header,io.BytesIO(b"x"),root,require_device=True)
            self.assertEqual(list(Path(root).iterdir()),[])
            header["filesystemDevice"]=[os.major(device),os.minor(device)]
            bridge.apply(header,io.BytesIO(b"x"),root,require_device=True)
            self.assertEqual(Path(root,"a.txt").read_bytes(),b"x")

    def test_before_write_storage_failure_keeps_old_file_and_after_rename_is_unknown(self):
        with tempfile.TemporaryDirectory() as root:
            target=Path(root,"a.txt");target.write_bytes(b"old")
            request=self.header(b"new",previous=hashlib.sha256(b"old").hexdigest())
            with patch.object(bridge.os,"write",side_effect=OSError(errno.ENOSPC,"fixture full")):
                with self.assertRaisesRegex(bridge.Rejected,"FILE_STORAGE_FULL"):
                    bridge.apply(request,io.BytesIO(b"new"),root)
            self.assertEqual(target.read_bytes(),b"old")
            sync=bridge.os.fsync;calls=0
            def fail_after_rename(fd):
                nonlocal calls
                calls+=1
                if calls==2:raise OSError(errno.EIO,"fixture parent fsync failure")
                return sync(fd)
            with patch.object(bridge.os,"fsync",side_effect=fail_after_rename):
                with self.assertRaisesRegex(bridge.Rejected,"FILE_RESULT_UNKNOWN"):
                    bridge.apply(request,io.BytesIO(b"new"),root)
            self.assertEqual(target.read_bytes(),b"new")
            # Recovery cannot repeat this command against the old base hash.
            with self.assertRaisesRegex(bridge.Rejected,"FILE_VERSION_CONFLICT"):
                bridge.apply(request,io.BytesIO(b"new"),root)

    @unittest.skipUnless(os.name == "posix", "descriptor-relative filesystem requires Linux")
    def test_hard_link_symlink_and_fifo_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, "original").write_bytes(b"x")
            os.link(Path(root, "original"), Path(root, "hard"))
            os.symlink("original", Path(root, "soft"))
            os.mkfifo(Path(root, "fifo"))
            for name in ["hard", "soft", "fifo"]:
                with self.subTest(name=name), self.assertRaises((bridge.Rejected, OSError)):
                    bridge.apply(self.header(b"a", path=name, previous=hashlib.sha256(b"x").hexdigest()), io.BytesIO(b"a"), root)


if __name__ == "__main__":
    unittest.main()
