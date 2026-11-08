"""Focused safety checks for the isolated R6.2 stop entry point."""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('r62_local', ROOT / 'deploy/scripts/r62-local.py')
local = importlib.util.module_from_spec(spec); spec.loader.exec_module(local)

def row(identifier, service):
    return {'Id': identifier, 'Name': '/agentplatform-r62-local-' + service + '-1',
            'Config': {'Labels': {'com.docker.compose.project': 'agentplatform-r62-local', 'com.docker.compose.service': service}}}

class StopTest(unittest.TestCase):
    def test_stops_applications_before_dependencies(self):
        commands = []; listed = [False]
        def call(args, **kwargs):
            commands.append(args)
            if args[:3] == ['docker', 'ps', '-q']:
                if not listed[0]: listed[0] = True; return 'a b c'
                return ''
            if args[:2] == ['docker', 'inspect']: return json.dumps([row('a', 'api'), row('b', 'postgres'), row('c', 'worker')])
            if args[:2] == ['docker', 'stop']: return ''
            raise AssertionError(args)
        with patch.object(local, 'call', side_effect=call), patch('builtins.print'): local.stop()
        stopped = [command[4:] for command in commands if command[:2] == ['docker', 'stop']]
        self.assertEqual([['a', 'c'], ['b']], stopped)

    def test_unrecognized_service_refuses_all_stops(self):
        commands = []
        def call(args, **kwargs):
            commands.append(args)
            if args[:3] == ['docker', 'ps', '-q']: return 'x'
            if args[:2] == ['docker', 'inspect']: return json.dumps([row('x', 'unknown')])
            raise AssertionError('unexpected operation')
        with patch.object(local, 'call', side_effect=call), self.assertRaises(RuntimeError): local.stop()
        self.assertFalse(any(command[:2] == ['docker', 'stop'] for command in commands))

if __name__ == '__main__': unittest.main()
