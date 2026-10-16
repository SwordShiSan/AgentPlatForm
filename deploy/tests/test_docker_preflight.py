import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('docker_preflight', Path(__file__).parents[1] / 'scripts/docker_preflight.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def response(code=0, output='', error=''):
    return subprocess.CompletedProcess([], code, output, error)


class DockerPreflightTest(unittest.TestCase):
    def test_running_engine_uses_current_context_without_starting_desktop(self):
        with patch.object(MODULE, 'invoke', side_effect=[response(output='{"OSType":"linux","MemTotal":3832545280}'), response(output='desktop-linux\n')]) as call:
            self.assertEqual('desktop-linux', MODULE.require_linux()['context'])
            self.assertEqual([('info', '--format', '{{json .}}'), ('context', 'show')], [item.args for item in call.call_args_list])

    def test_pipe_permission_error_needs_execution_permission_not_restart(self):
        for message in ['Access is denied.', 'permission denied', '拒绝访问']:
            with self.subTest(message=message), patch.object(MODULE, 'invoke', return_value=response(1, error=message)) as call:
                with self.assertRaises(MODULE.DockerUnavailable) as failure:
                    MODULE.require_linux(60)
                self.assertEqual('ENGINE_ACCESS_DENIED', failure.exception.code)
                self.assertEqual(1, call.call_count)

    def test_absent_pipe_with_running_desktop_is_backend_diagnostic(self):
        with patch.object(MODULE, 'invoke', side_effect=[response(1, error='pipe not found'), response(output='Status running')]):
            with self.assertRaises(MODULE.DockerUnavailable) as failure:
                MODULE.require_linux()
            self.assertEqual('BACKEND_NOT_READY', failure.exception.code)

    def test_windows_engine_is_rejected_without_context_mutation(self):
        with patch.object(MODULE, 'invoke', return_value=response(output='{"OSType":"windows"}')) as call:
            with self.assertRaises(MODULE.DockerUnavailable) as failure:
                MODULE.require_linux()
            self.assertEqual('WRONG_ENGINE', failure.exception.code)
            self.assertEqual(1, call.call_count)

    def test_cli_failure_is_sanitized(self):
        with patch.object(MODULE.subprocess, 'run', side_effect=FileNotFoundError()):
            with self.assertRaises(MODULE.DockerUnavailable) as failure:
                MODULE.require_linux()
            self.assertEqual('CLI_MISSING', failure.exception.code)


if __name__ == '__main__':
    unittest.main()
