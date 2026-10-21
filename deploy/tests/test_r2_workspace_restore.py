import ast
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
import subprocess
import sys
from unittest.mock import Mock, patch

scripts = Path(os.environ.get('R2_RELEASE_SCRIPTS', Path(__file__).parents[1] / 'scripts'))
spec = importlib.util.spec_from_file_location('r2workspacetest', scripts / 'r2-workspace-restore.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WorkspaceRestoreTest(unittest.TestCase):
    @unittest.skipUnless(hasattr(os, 'geteuid') and os.geteuid() == 0, 'Isolated UID drop requires Linux host root')
    def test_actual_child_prefix_writes_after_trusted_chdir_under_0700_parent(self):
        tree = ast.parse((scripts / 'r2-workspace-restore.py').read_text())
        script = next(ast.literal_eval(node.value) for node in ast.walk(tree)
                      if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'script' for t in node.targets))
        prefix = script.split("if mode=='bytes':", 1)[0]
        with tempfile.TemporaryDirectory() as folder:
            parent = Path(folder)
            self.assertEqual(parent.stat().st_mode & 0o777, 0o700)
            child = parent / 'workspace'; child.mkdir(mode=0o700)
            os.chown(child, 10001, 10001)
            result = subprocess.run([sys.executable, '-c', prefix + "\nassert os.getuid()==10001\n(p/'uid-probe').write_text('test-only')\n", str(child), 'prefix'], capture_output=True)
            self.assertEqual(result.returncode, 0, 'Non-root child could not use its verified cwd')
            self.assertEqual((child / 'uid-probe').read_text(), 'test-only')
            self.assertEqual(parent.stat().st_mode & 0o777, 0o700)

    def test_non_restore_root_refused_before_mount_command(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(module.subprocess, 'check_output') as command:
            with self.assertRaises(AssertionError):
                module.verified_mount(Path(folder))
            command.assert_not_called()

    def test_bytes_and_inodes_are_tested_with_independent_limits(self):
        with tempfile.TemporaryDirectory() as folder:
            workspace = Path(folder)
            with patch.object(module, 'verified_mount', return_value=workspace), patch.object(module.os, 'chown'), patch.object(module.shutil, 'disk_usage', return_value=Mock(free=3 * 1024**3)), patch.object(module.subprocess, 'run') as command, patch('builtins.print'):
                module.quota(workspace)
            calls = [call.args[0] for call in command.call_args_list]
            limits = [(index, call[3]) for index, call in enumerate(calls) if call[0] == 'xfs_quota' and call[3].startswith('limit')]
            byte = next(index for index, call in enumerate(calls) if call[0] == 'python3' and call[-1] == 'bytes')
            inode = next(index for index, call in enumerate(calls) if call[0] == 'python3' and call[-1] == 'inodes')
            self.assertIn('bhard=128k ihard=100000', limits[0][1])
            self.assertIn('bhard=0 ihard=8', limits[1][1])
            self.assertLess(limits[0][0], byte)
            self.assertLess(byte, limits[1][0])
            self.assertLess(limits[1][0], inode)
            compile(calls[byte][2], 'quota-child', 'exec')
            self.assertEqual(list(workspace.iterdir()), [])


if __name__ == '__main__':
    unittest.main()
