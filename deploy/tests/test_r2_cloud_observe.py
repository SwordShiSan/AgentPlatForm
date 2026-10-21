import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, Mock, patch

if os.name != 'posix':
    raise unittest.SkipTest('Linux host observation helper')
scripts = Path(os.environ.get('R2_RELEASE_SCRIPTS', Path(__file__).parents[1] / 'scripts'))
spec = importlib.util.spec_from_file_location('r2observetest', scripts / 'r2-cloud-observe.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
SOURCE = 'a' * 40
TASK = '00000000-0000-0000-0000-000000000001'
CONVERSATION = '00000000-0000-0000-0000-000000000002'
USAGE = [{'callId': 'call-1', 'status': 'COMPLETED', 'inputTokens': 30, 'outputTokens': 2, 'costMicros': 76, 'estimated': True}]


def facts():
    proof = dict(status='PASS', applicationSource=SOURCE, taskId=TASK, conversationId=CONVERSATION,
                 taskStatus='SUCCEEDED', model='deepseek-flash', physicalCalls=1, usage=copy.deepcopy(USAGE))
    return proof, dict(taskId=TASK, conversationId=CONVERSATION, status='SUCCEEDED'), {'items': copy.deepcopy(USAGE)}, {'values': {'modelName': 'deepseek-flash'}}


class CloudObserveTest(unittest.TestCase):
    def test_foreign_sse_uses_event_stream_accept_on_real_request(self):
        session = module.Session('124.222.55.140')
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = 404
        response.read.return_value = b'{}'
        session.opener = Mock()
        session.opener.open.return_value = response
        module.foreign_sse_denied(session, CONVERSATION)
        request = session.opener.open.call_args.args[0]
        self.assertEqual(request.get_header('Accept'), 'text/event-stream')
        self.assertEqual(request.method, 'GET')

    def test_definitive_unchanged_result_can_be_reused(self):
        module.validate_reused_task(*facts(), SOURCE)

    def test_unknown_usage_source_or_identity_mismatch_refused(self):
        for kind in ('unknown', 'missing-usage', 'different-source', 'different-task', 'different-cost'):
            proof, detail, usage, config = facts()
            if kind == 'unknown':
                detail['status'] = 'BLOCKED'
            elif kind == 'missing-usage':
                proof['usage'][0]['inputTokens'] = None
                usage = {'items': proof['usage']}
            elif kind == 'different-source':
                proof['applicationSource'] = 'b' * 40
            elif kind == 'different-task':
                detail['taskId'] = CONVERSATION
            else:
                usage['items'][0]['costMicros'] += 1
            with self.subTest(kind=kind), self.assertRaises(AssertionError):
                module.validate_reused_task(proof, detail, usage, config, SOURCE)

    def test_duplicate_physical_calls_refused(self):
        proof, detail, usage, config = facts()
        proof['usage'] *= 2
        proof['physicalCalls'] = 2
        usage['items'] = proof['usage']
        with self.assertRaises(AssertionError):
            module.validate_reused_task(proof, detail, usage, config, SOURCE)

    def test_reuse_branch_never_submits_a_task(self):
        proof, detail, usage, config = facts()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prior = root / 'operations/previous/prior-task-proof.json'
            prior.parent.mkdir(parents=True)
            prior.write_text(json.dumps(proof))
            accounts = root / 'operations/r1-accounts/accounts.json'
            accounts.parent.mkdir()
            accounts.write_text(json.dumps({'accounts': [{'username': 'r1acceptance', 'password': 'test-only'}, {'username': 'other', 'password': 'test-only'}]}))
            user, foreign = Mock(), Mock()
            def owner_request(method, path, **kwargs):
                self.assertEqual(method, 'GET', 'Reuse must not submit any mutation')
                if path == '/tasks/' + TASK:
                    return 200, detail
                if path.endswith('/usage'):
                    return 200, usage
                if path.endswith('/configuration'):
                    return 200, config
                if '/events?' in path:
                    return 200, 'id:1\n' if path.endswith('after=0') else ''
                return (400, {}) if path.startswith('/../') else (200, {})
            user.request.side_effect = owner_request
            foreign.request.side_effect = lambda method, path, **kwargs: (400, {}) if path.startswith('/../') else (404, {})
            window = Mock(folder=root / 'window', admission={'applicationSource': SOURCE})
            window.folder.mkdir()
            with patch.object(module, 'ROOT', root), patch.object(module, 'Session', side_effect=[user, foreign]), patch.object(module, 'submit_one_task') as submit, patch.object(module, 'observe_resources') as resource:
                module.observe(window, 1800, prior)
                submit.assert_not_called()
                self.assertEqual(resource.call_args.args[-1]['newSupplierCalls'], 0)
            receipts = {call.args[0]: call.args[1] for call in window.receipt.call_args_list}
            self.assertEqual(receipts['http-smoke']['physicalCalls'], 0)
            self.assertEqual(receipts['http-smoke']['newTaskSubmissions'], 0)


if __name__ == '__main__':
    unittest.main()
