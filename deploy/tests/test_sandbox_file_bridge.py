"""POSIX security boundaries; run on Linux, not a mocked Windows filesystem."""
import base64
import importlib.util
import os
from pathlib import Path
import tempfile
import threading
import unittest

spec = importlib.util.spec_from_file_location("bridge", Path(__file__).parents[1] / "scripts/sandbox-file-bridge.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class SandboxFileBridgeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / "workspace"
        self.root.mkdir()

    def call(self, action, path, content=None):
        request = {"action": action, "path": path}
        if content is not None:
            request["contentBase64"] = base64.b64encode(content).decode()
        return bridge.execute(request, str(self.root))

    def test_atomic_unicode_roundtrip_and_bounded_listing(self):
        content = "中文😀\n666".encode()
        result = self.call("WRITE", "中文.txt", content)
        self.assertEqual(result["sha256"], self.call("READ", "中文.txt")["sha256"])
        self.assertEqual(content, base64.b64decode(self.call("READ", "中文.txt")["contentBase64"]))
        for i in range(130):
            (self.root / str(i)).touch()
        listing = self.call("LIST", "")
        self.assertEqual(128, len(listing["entries"]))
        self.assertTrue(listing["truncated"])

    def test_traversal_and_unknown_action_rejected(self):
        for path in ["/etc/passwd", "../outside", "a/../b", "a//b", "a\\b", "a\x00b", ".", "a/"]:
            with self.assertRaises((bridge.Rejected, OSError), msg=repr(path)):
                self.call("READ", path)
        with self.assertRaises(bridge.Rejected):
            self.call("SHELL", "anything")

    def test_symlink_leaf_and_parent_never_followed(self):
        outside = self.root.parent / "outside"
        outside.write_bytes(b"private")
        (self.root / "link").symlink_to(outside)
        (self.root / "parent").symlink_to(self.root.parent, target_is_directory=True)
        for action in ("READ", "WRITE"):
            for path in ("link", "parent/outside"):
                with self.assertRaises((bridge.Rejected, OSError)):
                    self.call(action, path, b"replace" if action == "WRITE" else None)
        self.assertEqual(b"private", outside.read_bytes())

    def test_hardlink_fifo_and_oversize_refused(self):
        outside = self.root.parent / "outside"
        outside.write_bytes(b"private")
        os.link(outside, self.root / "hard")
        os.mkfifo(self.root / "pipe")
        for path in ("hard", "pipe"):
            with self.assertRaises(bridge.Rejected):
                self.call("READ", path)
            with self.assertRaises(bridge.Rejected):
                self.call("WRITE", path, b"bad")
        with self.assertRaises(bridge.Rejected):
            self.call("WRITE", "large", b"x" * (bridge.MAX_FILE + 1))
        self.assertFalse((self.root / "large").exists())

    def test_symlink_parent_replacement_race_does_not_expose_foreign_bytes(self):
        outside = self.root.parent / "foreign"
        outside.mkdir()
        (outside / "value").write_bytes(b"foreign")
        parent = self.root / "parent"
        parent.mkdir()
        (parent / "value").write_bytes(b"own")
        hold = self.root / "hold"
        stop = threading.Event()
        def race():
            while not stop.is_set():
                parent.rename(hold)
                parent.symlink_to(outside, target_is_directory=True)
                parent.unlink()
                hold.rename(parent)
        thread = threading.Thread(target=race)
        thread.start()
        try:
            for _ in range(300):
                try:
                    result = self.call("READ", "parent/value")
                    self.assertEqual(b"own", base64.b64decode(result["contentBase64"]))
                except OSError:
                    pass
        finally:
            stop.set()
            thread.join(5)
        self.assertFalse(thread.is_alive())


if __name__ == "__main__":
    unittest.main()
