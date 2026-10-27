"""Linux guest-launcher functional tests; not proof of gVisor isolation or runner stopping."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

MODULE_PATH = Path('/opt/platform/sandbox-shell.py')
spec = importlib.util.spec_from_file_location('sandbox_shell', MODULE_PATH)
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class GuestShellTests(unittest.TestCase):
    def run_shell(self, cwd, command):
        return subprocess.run([sys.executable, str(MODULE_PATH), cwd, command],
                              capture_output=True, text=True, timeout=10)

    def test_actual_python_and_node_share_workspace_files(self):
        result = self.run_shell('', "python3 -c \"from pathlib import Path;Path('result.txt').write_text('中文😀',encoding='utf-8');print(666)\"")
        self.assertEqual(0, result.returncode)
        self.assertEqual('666\n', result.stdout)
        result = self.run_shell('', "node -e \"const fs=require('fs');if(fs.readFileSync('result.txt','utf8')!=='中文😀')process.exit(9);console.log(666)\"")
        self.assertEqual(0, result.returncode)
        self.assertEqual('666\n', result.stdout)

    def test_known_exit_code_stdin_eof_and_clean_environment(self):
        self.assertEqual(7, self.run_shell('', 'exit 7').returncode)
        result = self.run_shell('', 'if read line; then exit 9; fi; printf "%s" "${R44_LAUNCHER_FIXTURE-unset}"')
        self.assertEqual(0, result.returncode)
        self.assertEqual('unset', result.stdout)

    def test_directory_handles_accept_unicode_and_reject_linked_directory(self):
        with tempfile.TemporaryDirectory(dir='/workspace') as work:
            root=Path(work);(root/'中文').mkdir()
            handle=launcher.open_directory(root,'中文')
            self.assertTrue(os.path.samestat(os.fstat(handle),os.stat(root/'中文')))
            os.close(handle)
            (root/'linked').symlink_to(root/'中文',target_is_directory=True)
            with self.assertRaises(OSError):launcher.open_directory(root,'linked')

    def test_noncanonical_cwd_and_invalid_commands_are_rejected(self):
        for path in ['../other','/workspace','a/../b','a\\b']:
            self.assertNotEqual(0,self.run_shell(path,'true').returncode)
        self.assertNotEqual(0,self.run_shell('',' ').returncode)


if __name__ == '__main__':
    unittest.main()
