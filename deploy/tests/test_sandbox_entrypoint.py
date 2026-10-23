"""Real POSIX pre-admission canary boundaries."""
import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
import uuid

spec = importlib.util.spec_from_file_location("entry", Path(__file__).parents[1] / "scripts/sandbox-entrypoint.py")
entry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entry)


class StartupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "workspace"
        self.root.mkdir()
        self.ids = [str(uuid.uuid4()) for _ in range(4)]
        self.args = self.ids + [1, 2, "a" * 64]
        self.name = ".platform-ready-" + self.ids[3]

    def test_command_bound_readback_and_no_reexecution(self):
        result = entry.attest(self.root, *self.args)
        data = (self.root / self.name).read_bytes()
        self.assertEqual(result["contentHash"], hashlib.sha256(data).hexdigest())
        self.assertEqual(data, entry.content(*self.args))
        self.assertEqual((self.root / self.name).stat().st_mode & 0o777, 0o600)
        with self.assertRaises(FileExistsError):
            entry.attest(self.root, *self.args)

    def test_existing_link_is_never_followed(self):
        victim = Path(self.temp.name) / "victim"
        victim.write_bytes(b"unchanged")
        (self.root / self.name).symlink_to(victim)
        with self.assertRaises(FileExistsError):
            entry.attest(self.root, *self.args)
        self.assertEqual(victim.read_bytes(), b"unchanged")

    def test_symlink_root_is_refused(self):
        alias = Path(self.temp.name) / "alias"
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(OSError):
            entry.attest(alias, *self.args)
        self.assertFalse((self.root / self.name).exists())

    def test_policy_or_identity_cannot_choose_paths(self):
        for args in [["../x"] + self.args[1:], self.args[:-1] + ["../policy"], self.ids + [0, 2, "a" * 64]]:
            with self.assertRaises(ValueError):
                entry.attest(self.root, *args)
        self.assertEqual(list(self.root.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
