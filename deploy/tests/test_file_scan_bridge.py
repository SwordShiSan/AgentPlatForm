import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
import uuid

spec = importlib.util.spec_from_file_location("scan", Path(__file__).parents[1] / "scripts/file-scan-bridge.py")
scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan)


class ScanBridgeTests(unittest.TestCase):
    def test_root_mount_attestations_are_not_user_entries_but_lookalikes_remain_visible(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, ".platform-ready-" + str(uuid.uuid4())).write_bytes(b"internal mount attestation")
            Path(root, ".platform-ready-not-an-identity").write_bytes(b"ordinary file")
            Path(root, "visible.txt").write_bytes(b"666")
            result = scan.scan(self.header(), root)
            self.assertEqual([item["path"] for item in result["entries"]],
                             [".platform-ready-not-an-identity", "visible.txt"])
            Path(root, "nested").mkdir()
            name = ".platform-ready-" + str(uuid.uuid4())
            Path(root, "nested", name).write_bytes(b"ordinary nested file")
            self.assertEqual(scan.scan(self.header(parent="nested"), root)["entries"][0]["path"], "nested/" + name)

    def header(self, parent="", cursor=None, stamp=None, epoch=None):
        return {"protocol": "file-scan-v1", "operationId": str(uuid.uuid4()), "parent": parent,
                "cursor": cursor, "epoch": epoch or str(uuid.uuid4()), "stamp": stamp}

    def test_actual_hash_unicode_directory_and_no_file_body(self):
        with tempfile.TemporaryDirectory() as root:
            content = "中文😀666".encode()
            Path(root, "空😀.txt").write_bytes(content)
            Path(root, "目录").mkdir()
            result = scan.scan(self.header(), root)
            self.assertEqual(result["state"], "SCANNED")
            self.assertIsNone(result["next"])
            file = next(item for item in result["entries"] if item["kind"] == "FILE")
            self.assertEqual(file["sha256"], hashlib.sha256(content).hexdigest())
            self.assertEqual(file["bytes"], len(content))
            self.assertNotIn(content.decode(), scan.encoded(result).decode())

    def test_paging_is_bounded_and_directory_change_restarts_without_missing_inference(self):
        with tempfile.TemporaryDirectory() as root:
            for index in range(150):
                Path(root, f"{index:03d}.txt").write_bytes(b"x")
            first = scan.scan(self.header(), root)
            self.assertEqual(len(first["entries"]), 100)
            self.assertEqual(first["next"], "099.txt")
            header = self.header(cursor=first["next"], stamp=first["stamp"], epoch=first["epoch"])
            second = scan.scan(header, root)
            self.assertEqual(len(second["entries"]), 50)
            self.assertIsNone(second["next"])
            Path(root, "new.txt").write_bytes(b"y")
            changed = scan.scan(header, root)
            self.assertTrue(changed["restart"])
            self.assertEqual(changed["entries"], [])
            self.assertIsNone(changed["next"])

    def test_json_budget_accounts_for_escaped_filenames(self):
        with tempfile.TemporaryDirectory() as root:
            for index in range(100):
                Path(root, f"{index:03d}" + "\n" * 240).write_bytes(b"")
            result = scan.scan(self.header(), root)
            self.assertLessEqual(len(scan.encoded(result)), 28 * 1024)
            self.assertIsNotNone(result["next"])
            self.assertLess(len(result["entries"]), 100)

    def test_links_special_files_and_noncanonical_names_are_not_followed(self):
        for kind in ("soft", "hard", "fifo", "nfd"):
            with tempfile.TemporaryDirectory() as root:
                Path(root, "ordinary").write_bytes(b"x")
                if kind == "soft":
                    os.symlink("ordinary", Path(root, "alias"))
                elif kind == "hard":
                    os.link(Path(root, "ordinary"), Path(root, "alias"))
                elif kind == "fifo":
                    os.mkfifo(Path(root, "alias"))
                else:
                    Path(root, "e\u0301.txt").write_bytes(b"x")
                with self.subTest(kind=kind), self.assertRaises((ValueError, OSError)):
                    scan.scan(self.header(), root)

    def test_parent_and_cursor_are_scoped_to_one_directory(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, "nested").mkdir()
            Path(root, "nested", "a.txt").write_bytes(b"x")
            result = scan.scan(self.header(parent="nested"), root)
            self.assertEqual(result["entries"][0]["path"], "nested/a.txt")
            with self.assertRaises(ValueError):
                scan.scan(self.header(parent="nested", cursor="other/a.txt"), root)
            with self.assertRaises((ValueError, OSError)):
                scan.scan(self.header(parent="../outside"), root)


if __name__ == "__main__":
    unittest.main()
