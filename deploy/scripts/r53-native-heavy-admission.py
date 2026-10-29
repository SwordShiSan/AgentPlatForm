"""Prove both real API heavy-operation queue directions in the isolated R53 fixture.

No models, shell commands inside a sandbox, process kills or automatic unknown replay.
Snapshots use only scoped metadata SQL and Docker inspect. Production/window ownership
stays with files-local.py; call this after native R53 API acceptance, before observation.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r53-local'


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy/scripts' / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


support = load('r53_heavy_support', 'r53-native-acceptance.py')
native = load('r53_heavy_fixture', 'files-native-fixture.py')
native.configure('r53')


class Journal(support.Journal):
    def __init__(self):
        self.path = DATA / 'native-heavy-journal.json'
        self.rows = json.loads(self.path.read_text(encoding='utf-8')) if self.path.exists() else {}

    def fact(self, name, value):
        self.rows[name] = value
        self.write()
        return value


def snapshot(plan, workspace, previews):
    workspace = str(uuid.UUID(workspace))
    previews = [str(uuid.UUID(value)) for value in previews]
    code = '''from pathlib import Path
import json,subprocess,time
r=Path(ROOT);wid=WORKSPACE;previews=PREVIEWS
c=json.loads((r/'compose.json').read_text());assert c['name']=='agentplatform-r53-local'
pg=subprocess.check_output(['docker','compose','-p',c['name'],'-f',str(r/'compose.json'),'ps','-q','postgres'],text=True).strip()
query="SELECT json_build_object('heavy',(SELECT row_to_json(h) FROM pf.heavy_runtime_slot h WHERE id=1),'environment',(SELECT json_build_object('id',e.id,'state',e.state,'generation',e.generation,'version',e.version,'operationId',e.operation_id,'waitingHeavy',o.waiting_heavy) FROM pf.workspace_environment e JOIN pf.workspace_environment_operation o ON o.id=e.operation_id WHERE e.workspace_id='"+wid+"'),'previews',(SELECT coalesce(json_agg(json_build_object('id',p.id,'state',p.state,'attempts',p.attempts,'generation',p.generation)),'[]'::json) FROM pf.preview_job p WHERE p.workspace_id='"+wid+"'));"
result=json.loads(subprocess.check_output(['docker','exec',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','postgres','-d','file_runtime','-c',query],text=True))
ids=subprocess.check_output(['docker','ps','-q','--filter','label=ap.role'],text=True).split()
rows=json.loads(subprocess.check_output(['docker','inspect',*ids],text=True)) if ids else []
physical=[]
for row in rows:
 labels=row['Config'].get('Labels') or {};role=labels.get('ap.role')
 if role not in ['sandbox','preview-renderer'] or not row['State']['Running']:continue
 assert labels.get('ap.workspace')==wid if role=='sandbox' else labels.get('ap.preview') in previews,'Unexpected unrelated heavy runtime during isolated acceptance'
 physical.append({'id':row['Id'],'role':role,'previewId':labels.get('ap.preview'),'environmentId':labels.get('ap.environment'),'image':row['Image'],'runtime':row['HostConfig']['Runtime'],'memory':row['HostConfig']['Memory']})
assert len(physical)<=1,'Preview and business sandbox were physically concurrent'
assert all(p['runtime']=='runsc' and p['memory']==512*1024**2 for p in physical)
result.update(time=time.time(),physical=physical);print(json.dumps(result))
'''.replace('ROOT', repr(plan['root'])).replace('WORKSPACE', repr(workspace)).replace('PREVIEWS', repr(previews))
    return json.loads(native.remote(code, timeout=30))


def environment(client, workspace):
    status, row, headers = support.get(client, '/api/v1/workspaces/' + workspace + '/environment')
    match = support.header(headers, 'ETag')
    assert status == 200 and match == '"' + str(row['version']) + '"'
    return row, match


def transition(client, journal, workspace, label, action):
    key = 'environment:' + label
    frozen = journal.rows.get(key)
    if frozen:
        request = frozen['request']
    else:
        view, match = environment(client, workspace)
        assert ('PREPARE' if action == '' else 'SLEEP') in view['allowedActions'], view
        request = {'method': 'POST', 'path': '/api/v1/workspaces/' + workspace + '/environment' + action, 'ifMatch': match}
    # Recovery uses the original public request lookup, never a repeated lifecycle command.
    if frozen and frozen.get('response') is None and frozen['phase'] in ['SENT', 'UNKNOWN']:
        original = support.get(client, '/api/v1/workspaces/' + workspace + '/environment/requests/' + frozen['key'])[1]
        current, _ = environment(client, workspace)
        frozen.update(response={'operationId': original['operationId'], 'environment': current}, phase='ACKNOWLEDGED_BY_QUERY')
        journal.write()
    result = journal.once(client, key, request, headers={'If-Match': request['ifMatch']})
    assert result['operationId'] and result['environment']['workspaceId'] == workspace
    return result


def begin_preview(client, journal, file, label):
    body = {'versionId': file['versionId'], 'format': 'AUTO'}
    return journal.once(client, 'heavy-preview:' + label,
                        {'method': 'POST', 'path': '/api/v1/files/' + file['id'] + '/previews', 'body': body}, body)


def wait_environment(client, workspace, state):
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        row, _ = environment(client, workspace)
        if row['state'] == state:
            return row
        assert row['state'] not in ['ERROR', 'BLOCKED'], row
        time.sleep(0.2)
    raise RuntimeError('Original lifecycle operation is still pending; do not resubmit')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    plan = json.loads((DATA / 'native-plan.json').read_text(encoding='utf-8'))
    assert plan['project'] == 'agentplatform-r53-local' and plan['root'].startswith('/srv/agentplatform/acceptance/r44-r53-')
    assert json.loads((DATA / 'native-api-result.json').read_text())['status'] == 'PASS'
    ids = json.loads((DATA / 'native-preview-seed.json').read_text())
    workspace = str(uuid.UUID(ids['workspaceId']))
    journal = Journal()
    identity = {'root': plan['root'], 'source': plan['source'], 'workspaceId': workspace}
    assert journal.rows.get('fixture', identity) == identity
    journal.fact('fixture', identity)
    result_file = DATA / 'native-heavy-result.json'
    if result_file.exists():
        previous = json.loads(result_file.read_text())
        assert previous['status'] == 'PASS' and previous['root'] == plan['root']
        print('R53_ORIGINAL_HEAVY_ACCEPTANCE_ALREADY_PASS')
        return
    client = support.api.Client()
    content = (DATA / 'preview-fixtures/sample.docx').read_bytes()
    first = support.upload(client, journal, workspace, 'heavy-preview-first.docx', content)
    second = support.upload(client, journal, workspace, 'heavy-sandbox-first.docx', content)
    initial, _ = environment(client, workspace)
    assert initial['state'] in ['NOT_CREATED', 'PREPARING', 'READY', 'STOPPED'], initial

    preview1 = begin_preview(client, journal, first, 'first')
    if 'first-running' not in journal.rows:
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            row = support.get(client, '/api/v1/previews/' + preview1['id'])[1]
            if row['state'] == 'RUNNING':
                journal.fact('first-running', row)
                break
            assert row['state'] == 'QUEUED', 'Preview ended before the overlap witness; no second conversion is started'
            time.sleep(0.025)
        else:
            raise RuntimeError('Original preview did not start; query it instead of retrying')
    prepared = transition(client, journal, workspace, 'prepare-after-preview', '')
    if 'environment-waiting-preview' not in journal.rows:
        row = snapshot(plan, workspace, [preview1['id']])
        journal.fact('environment-waiting-preview', row)
    waiting = journal.rows['environment-waiting-preview']
    assert waiting['heavy']['kind'] == 'PREVIEW' and waiting['heavy']['reference_id'] == preview1['id'], waiting
    assert waiting['environment']['state'] == 'PREPARING' and waiting['environment']['waitingHeavy'], waiting
    assert not any(value['role'] == 'sandbox' for value in waiting['physical'])
    completed1 = support.wait_preview(client, preview1['id'])
    assert completed1['state'] == 'READY', completed1
    ready = wait_environment(client, workspace, 'READY')
    journal.fact('environment-ready-after-preview', snapshot(plan, workspace, [preview1['id']]))

    preview2 = begin_preview(client, journal, second, 'second')
    if 'preview-waiting-sandbox' not in journal.rows:
        row = snapshot(plan, workspace, [preview1['id'], preview2['id']])
        journal.fact('preview-waiting-sandbox', row)
    queued = journal.rows['preview-waiting-sandbox']
    assert queued['heavy']['kind'] == 'SANDBOX' and queued['environment']['state'] == 'READY', queued
    preview_row = next(value for value in queued['previews'] if value['id'] == preview2['id'])
    assert preview_row['state'] == 'QUEUED' and preview_row['attempts'] == 0, queued
    assert len(queued['physical']) == 1 and queued['physical'][0]['role'] == 'sandbox', queued
    sleeping = transition(client, journal, workspace, 'explicit-sleep', '/sleep')
    stopped = wait_environment(client, workspace, 'STOPPED')
    completed2 = support.wait_preview(client, preview2['id'])
    assert completed2['state'] == 'READY', completed2
    final = journal.fact('final-stopped-and-preview-ready', snapshot(plan, workspace, [preview1['id'], preview2['id']]))
    assert final['environment']['state'] == 'STOPPED' and not final['physical'], final
    assert final['heavy']['kind'] is None, final
    sources = ['deploy/scripts/r53-native-heavy-admission.py', 'deploy/scripts/r53-native-acceptance.py',
               'deploy/scripts/files-native-fixture.py', 'backend/platform-app/src/main/resources/db/migration/V20__preview_pipeline.sql']
    result = {'status': 'PASS', **identity, 'supplierCalls': 0, 'prepareOperationId': prepared['operationId'],
              'sleepOperationId': sleeping['operationId'], 'generation': ready['generation'], 'finalGeneration': stopped['generation'],
              'previewIds': [preview1['id'], preview2['id']], 'samples': [waiting, journal.rows['environment-ready-after-preview'], queued, final],
              'inputSha256': {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in sources},
              'scope': 'Actual durable queue/API/MQ/gVisor lifecycle: environment waits for preview, preview waits for sandbox, explicit sleep releases capacity. Physical counts are boundary samples, not continuous maxima.'}
    support.atomic(result_file, result)
    print(json.dumps({'status': 'PASS', 'supplierCalls': 0, 'directions': 2, 'result': str(result_file)}))


if __name__ == '__main__':
    main()
