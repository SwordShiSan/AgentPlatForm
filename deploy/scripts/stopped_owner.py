"""Trusted host-only stop evidence. No Docker socket or signing key reaches API/worker."""
import hashlib
import hmac
import json
import subprocess
import time
import uuid
from datetime import datetime, timezone


def reconcile(root, data, env, compose, service='worker', force=False):
    project = compose[compose.index('-p') + 1]
    assert project in {'agentplatform-r12-local', 'agentplatform-r21-local'}

    def output(command, **kwargs):
        return subprocess.check_output(command, cwd=root, env=env, text=True, **kwargs).strip()

    def inspect(container):
        return json.loads(output(['docker', 'inspect', container]))[0]

    def sql(query):
        return output(compose + ['exec', '-T', 'postgres', 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'chat', '-c', query])

    container = output(compose + ['ps', '-a', '-q', service])
    assert container and '\n' not in container, 'Expected one exact worker container'
    before = inspect(container)
    assert before['Config']['Labels']['com.docker.compose.project'] == project
    assert before['Config']['Labels']['com.docker.compose.service'] == service
    assert before['State']['Running'] and before['RestartCount'] == 0, 'Capture live original instance first'
    owner = str(uuid.UUID(output(['docker', 'exec', before['Id'], 'cat', '/tmp/chat-worker-ready'])))
    row = sql("SELECT a.task_id::text||','||a.id::text||','||a.fencing_token::text FROM pf.execution_slot s JOIN pf.attempt a ON a.task_id=s.task_id AND a.owner_id=s.owner_id AND a.fencing_token=s.fencing_token WHERE s.id=1 AND s.owner_id='" + owner + "' AND a.status='RUNNING';")
    assert row and '\n' not in row, 'Worker has no active owned task; do not generate fictitious proof'
    task, attempt, fence = row.split(',')
    task, attempt = str(uuid.UUID(task)), str(uuid.UUID(attempt))
    command = ['docker', 'kill', '--signal', 'KILL', before['Id']] if force else ['docker', 'stop', '--time', '15', before['Id']]
    subprocess.run(command, cwd=root, env=env, check=True, capture_output=True)
    after = inspect(before['Id'])
    assert after['Id'] == before['Id'] and after['Image'] == before['Image']
    assert after['State']['StartedAt'] == before['State']['StartedAt'] and not after['State']['Running']
    assert after['RestartCount'] == before['RestartCount']
    deadline = time.monotonic() + 75
    while sql("SELECT lease_until>clock_timestamp() FROM pf.execution_slot WHERE owner_id='" + owner + "';") == 't':
        assert time.monotonic() < deadline, 'Lease not expired; leave stopped and do not reconcile'
        time.sleep(2)
    # Re-inspect immediately before attestation: an intervening restart invalidates this proof.
    final = inspect(before['Id'])
    assert not final['State']['Running'] and final['State']['StartedAt'] == before['State']['StartedAt']
    payload = json.dumps(dict(ownerId=owner, taskId=task, attemptId=attempt, fence=int(fence), containerId=before['Id'],
                             imageId=before['Image'], startedAt=before['State']['StartedAt'], stoppedAt=final['State']['FinishedAt'],
                             observedAt=datetime.now(timezone.utc).isoformat()), sort_keys=True, separators=(',', ':'))
    key = (data / 'stop-proof-key').read_bytes()
    assert len(key) == 32
    proof = json.dumps({'payload': payload, 'signature': hmac.new(key, payload.encode(), hashlib.sha256).hexdigest()})
    (data / ('stopped-owner-' + owner + '.json')).write_text(proof, encoding='utf-8')
    # Secret is already a protected host file; stdin carries only the signed evidence.
    shell = 'umask 077; cat > /tmp/proof; cat /run/secrets/stop_proof_key > /tmp/proof-key; java -Xms16m -Xmx160m -jar /app/platform.jar --chat-action=reconcile-stopped-owner --proof-file=/tmp/proof --proof-key-file=/tmp/proof-key; result=$?; rm -f /tmp/proof /tmp/proof-key; exit "$result"'
    result = subprocess.run(compose + ['--profile', 'operator', 'run', '--rm', '-T', '--entrypoint', 'sh', 'operator', '-c', shell],
                            cwd=root, env=env, input=proof, text=True, capture_output=True, timeout=120)
    (data / ('reconcile-' + owner + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    assert result.returncode == 0, 'Reconciliation rejected; inspect protected evidence, leave worker stopped'
    return {'owner': owner, 'container': before['Id'], 'image': before['Image'], 'proofSha256': hashlib.sha256(proof.encode()).hexdigest()}
