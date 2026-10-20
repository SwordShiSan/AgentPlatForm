"""R2.4 exact-container checkpoint continuation. Only the isolated deterministic local fixture."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

assert os.environ.get('AGENTPLATFORM_ACCEPTANCE_STAGE') == 'r24', 'Select the explicit R2.4 fixture'
from execution_fixture import ROOT, DATA, ENV, COMPOSE, PROJECT
from execution_images import pin_exported
from stopped_owner import reconcile

spec = importlib.util.spec_from_file_location('execution_acceptance', Path(__file__).with_name('execution-acceptance.py'))
execution = importlib.util.module_from_spec(spec)
spec.loader.exec_module(execution)
sql, command, wait = execution.sql, execution.command, execution.wait
OUT = DATA / 'acceptance/recovery'
WINDOWS = {
    'input': ('input-checkpoint-after-seal', False, None),
    'prepared-tool': ('model-checkpoint-after-seal', True, None),
    'committed-tool': ('tool-checkpoint-after-seal', True, None),
    'terminal': ('terminal-checkpoint-after-seal', False, 'TERMINAL'),
    'unknown-model': ('response-before-round', False, 'RECOVERY_UNKNOWN_EFFECT'),
    'advanced-round': ('round-before-checkpoint', True, 'CHECKPOINT_LEDGER_ADVANCED'),
    'unknown-tool': ('tool-before-result', True, 'RECOVERY_UNKNOWN_EFFECT'),
    'advanced-tool': ('tool-after-result', True, 'CHECKPOINT_LEDGER_ADVANCED'),
}


def record(name, value):
    assert sql('SELECT physical_calls FROM pf.acceptance_model_meter WHERE id=1') == '0'
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / (name + '.json')).write_text(json.dumps(dict(status='PASS', realSupplierCalls=0, **value), indent=2), encoding='utf-8')
    print(name + ': PASS; no supplier request', flush=True)


def operator(body):
    # No secret on the command line; the evidence file is created0600 inside the isolated operator.
    shell = 'umask 077; cat > /tmp/recovery-command; java -Xms16m -Xmx160m -jar /app/platform.jar --chat-action=recover-checkpoint --evidence-file=/tmp/recovery-command; result=$?; rm -f /tmp/recovery-command; exit "$result"'
    result = subprocess.run(COMPOSE + ['--profile', 'operator', 'run', '--rm', '-T', '--entrypoint', 'sh', 'operator', '-c', shell],
                            cwd=ROOT, env=ENV, input=json.dumps(body), text=True, capture_output=True, timeout=90)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / ('operator-' + body['commandId'] + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    return result.returncode


def window(name):
    point, tools, refusal = WINDOWS[name]
    execution.workers(point)
    client = execution.Client()
    task = client.submit(('fixture:recovery-tool:' if tools else 'fixture:normal:r24:') + name + ':' + str(uuid.uuid4()))
    def ready():
        result = subprocess.run(['docker', 'exec', PROJECT + '-worker-1', 'cat', '/tmp/chat-fault-ready'], capture_output=True, text=True)
        return result.returncode == 0 and result.stdout.strip() == point
    wait(ready, 40)
    original = json.loads(sql("SELECT jsonb_build_object('attempt',a.id,'fence',a.fencing_token,'assistant',m.id,'deadline',t.execution_deadline_at) "
                              "FROM pf.attempt a JOIN pf.task t ON t.id=a.task_id JOIN pf.message m ON m.task_id=t.id AND m.role='ASSISTANT' WHERE t.id='" + task + "'"))
    calls_before = int(sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'"))
    proof = reconcile(ROOT, DATA, ENV, COMPOSE, force=True)
    projection = client.request('GET', '/api/v1/tasks/' + task + '/recovery')
    if refusal == 'TERMINAL':
        assert execution.status(task) == 'SUCCEEDED' and projection['stage'] == 'COMPLETED'
        assert int(sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'")) == calls_before == 1
        record(name, dict(task=task, original=original, stoppedProof=proof, noReplayedCall=True))
        return
    stop = sql("SELECT id FROM pf.attempt WHERE task_id='" + task + "' ORDER BY started_at DESC,id DESC LIMIT 1")
    body = dict(commandId=str(uuid.uuid4()), taskId=task, checkpointId=projection['checkpointId'], stopAttemptId=stop,
                expectedTaskVersion=projection['taskVersion'], expectedRevision=projection['revision'], operator='r24-local-acceptance')
    if refusal:
        if refusal == 'RECOVERY_UNKNOWN_EFFECT':
            assert not projection['eligible'] and projection['reasonCode'] == refusal
        assert operator(body) != 0, 'A damaged/advanced/unknown checkpoint must not queue'
        diagnostic=(OUT / ('operator-' + body['commandId'] + '.log')).read_text(encoding='utf-8')
        assert refusal in diagnostic, 'A different failure (including deadline or startup failure) cannot satisfy this refusal gate'
        assert sql("SELECT count(*) FROM pf.checkpoint_recovery WHERE task_id='" + task + "'") == '0'
        assert int(sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'")) == calls_before
        record(name, dict(task=task, stoppedProof=proof, refusal=refusal, calls=calls_before))
        return
    assert projection['eligible'] and projection['reasonCode'] == 'OPERATOR_VALIDATION_REQUIRED'
    assert operator(body) == 0, 'Inspect protected operator log; never reset the original deadline'
    assert sql("SELECT count(*) FROM pf.checkpoint_recovery WHERE task_id='" + task + "'") == '1'
    command(COMPOSE + ['up', '-d', '--no-deps', 'worker-2'])
    wait(lambda: execution.status(task) == 'SUCCEEDED', 45)
    assert sql("SELECT count(*) FROM pf.attempt WHERE task_id='" + task + "'") == '2'
    assert sql("SELECT count(*) FROM pf.message WHERE task_id='" + task + "'") == '2'
    assert sql("SELECT id FROM pf.message WHERE task_id='" + task + "' AND role='ASSISTANT'") == original['assistant']
    assert sql("SELECT execution_deadline_at::text FROM pf.task WHERE id='" + task + "'")
    # Compare timestamp semantics through PostgreSQL instead of formatted timezone strings.
    assert sql("SELECT execution_deadline_at='" + original['deadline'] + "'::timestamptz FROM pf.task WHERE id='" + task + "'") == 't'
    assert sql("SELECT count(*) FROM pf.checkpoint_recovery WHERE task_id='" + task + "' AND status='COMPLETED'") == '1'
    assert int(sql("SELECT count(*) FROM pf.model_call WHERE task_id='" + task + "'")) == (2 if tools else 1)
    effects = list((DATA / 'fixture-effects' / task).glob('*.effect'))
    assert len(effects) == (1 if tools else 0)
    assert sql("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL") == '0'
    assert operator(body) == 0, 'An identical command remains idempotent after completion'
    record(name, dict(task=task, original=original, stoppedProof=proof, independentAttempts=2, effects=len(effects), deadlineUnchanged=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('window', choices=list(WINDOWS))
    name = parser.parse_args().window
    pin_exported(ENV, DATA, required=True)
    candidate = json.loads((DATA / 'final-candidate.json').read_text(encoding='utf-8'))
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD:backend'], cwd=ROOT, text=True).strip() == candidate['backendTree']
    assert not subprocess.check_output(['git', 'diff', '--name-only', 'HEAD', '--', 'backend'], cwd=ROOT, text=True).strip()
    try:
        window(name)
    finally:
        # Do not release a live slot or restart a stopped owner on a failed assertion.
        print('Window ended; active slot and stopped-owner evidence retained on failure.', flush=True)


if __name__ == '__main__':
    main()
