"""Linux host safety boundaries; no container or service mutation in these tests."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

if os.name != 'posix':
    raise unittest.SkipTest('Host drivers require Linux fcntl; execute these tests on Linux')


def load(name, filename):
    scripts = Path(os.environ.get('R2_RELEASE_SCRIPTS', Path(__file__).parents[1] / 'scripts'))
    spec = importlib.util.spec_from_file_location(name, scripts / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


control = load('r2controltest', 'r2-release-control.py')
restore = load('r2restoretest', 'r2-isolated-restore.py')


class ReleaseSafetyTest(unittest.TestCase):
    def test_outside_operations_refused_before_any_command(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(control, 'output') as command:
            with self.assertRaises(AssertionError):
                control.Window(Path(folder))
            command.assert_not_called()

    def test_active_source_refuses_restore_before_mkdir_or_start(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            (path / 'backup-r2').mkdir()
            (path / 'backup-r2/checksums.json').write_text('{}')
            window = Mock(folder=path)
            with patch.object(restore, 'output', return_value='active-container'), patch.object(restore, 'run') as run:
                with self.assertRaisesRegex(AssertionError, 'Source/temporary'):
                    restore.restore_window(window)
                run.assert_not_called()
            self.assertEqual(sorted(p.name for p in path.iterdir()), ['backup-r2'])

    def test_failed_backup_enters_exact_r1_rollback(self):
        with tempfile.TemporaryDirectory() as folder:
            window = Mock(folder=Path(folder), mutated=True)
            window.backup.side_effect = RuntimeError('injected backup failure')
            with patch.object(control, 'Window', return_value=window), patch.object(sys, 'argv', ['driver', 'backup-r1', folder]):
                with self.assertRaisesRegex(RuntimeError, 'injected backup failure'):
                    control.main()
            window.rollback.assert_called_once_with()

    def test_pre_mutation_admission_failure_leaves_r1_untouched(self):
        with tempfile.TemporaryDirectory() as folder:
            window = Mock(folder=Path(folder), mutated=False)
            window.backup.side_effect = RuntimeError('capacity refused before stopping source')
            with patch.object(control, 'Window', return_value=window), patch.object(sys, 'argv', ['driver', 'backup-r1', folder]):
                with self.assertRaisesRegex(RuntimeError, 'capacity refused'):
                    control.main()
            window.rollback.assert_not_called()

    def test_import_failure_does_not_stop_or_redeploy_source(self):
        with tempfile.TemporaryDirectory() as folder:
            window = Mock(folder=Path(folder))
            window.import_images.side_effect = RuntimeError('bad archive')
            with patch.object(control, 'Window', return_value=window), patch.object(sys, 'argv', ['driver', 'import', folder]):
                with self.assertRaisesRegex(RuntimeError, 'bad archive'):
                    control.main()
            window.rollback.assert_not_called()

    def test_completed_action_refuses_reexecution(self):
        with tempfile.TemporaryDirectory() as folder:
            window = Mock(folder=Path(folder))
            (Path(folder) / 'activate.json').write_text(json.dumps({'status': 'PASS'}))
            with patch.object(control, 'Window', return_value=window), patch.object(sys, 'argv', ['driver', 'activate', folder]):
                with self.assertRaisesRegex(AssertionError, 'Completed action'):
                    control.main()
            window.activate.assert_not_called()
            window.rollback.assert_not_called()


if __name__ == '__main__':
    unittest.main()
