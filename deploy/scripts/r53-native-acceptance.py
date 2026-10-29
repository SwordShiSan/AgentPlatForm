"""Actual R53 API/PG/MQ/runner/S3 preview assertions through the isolated SSH tunnel.

Only fixed generated fixtures are uploaded. Mutation identities are atomically journaled
before sending; ambiguous writes without a returned identity stop for reconciliation.
This script never pauses production, starts infrastructure, or sends model requests.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r53-local'
FIXTURES = DATA / 'preview-fixtures'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy/scripts' / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    value.configure('r53')
    return value


api = module('r53_file_api', 'files-native-api.py')


def atomic(path, value):
    temporary = path.with_name(path.name + '.' + str(uuid.uuid4()) + '.tmp')
    with temporary.open('x', encoding='utf-8', newline='\n') as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write('\n')
    os.replace(temporary, path)


class Journal:
    def __init__(self):
        self.path = DATA / 'native-preview-journal.json'
        self.rows = json.loads(self.path.read_text(encoding='utf-8')) if self.path.exists() else {}

    def write(self):
        atomic(self.path, self.rows)

    def freeze(self, name, request):
        existing = self.rows.get(name)
        if existing:
            assert existing['request'] == request, 'Frozen acceptance input changed: ' + name
            return existing
        row = {'request': request, 'key': str(uuid.uuid4()), 'phase': 'FROZEN'}
        self.rows[name] = row
        self.write()
        return row

    def once(self, client, name, request, body=None, headers=None):
        row = self.freeze(name, request)
        if row.get('response') is not None:
            return row['response']
        assert row['phase'] == 'FROZEN', 'Original mutation unconfirmed; query its original identity, do not resend: ' + name
        row['phase'] = 'SENT'
        self.write()
        try:
            status, result, _ = client.request(request['method'], request['path'], body,
                                             {'Idempotency-Key': row['key'], **(headers or {})})
        except Exception as failure:
            row['phase'] = 'REJECTED' if isinstance(failure, api.ApiFailure) and 400 <= failure.status < 500 else 'UNKNOWN'
            if isinstance(failure, api.ApiFailure):
                row['error'] = {'status': failure.status, 'code': failure.code}
            self.write()
            raise
        row.update(phase='ACKNOWLEDGED', response=result, status=status)
        self.write()
        return result


def get(client, path, binary=False, headers=None):
    # A safe read may wait briefly for the existing single transfer permit.
    for attempt in range(8):
        try:
            return client.request('GET', path, headers=headers, binary=binary)
        except api.ApiFailure as failure:
            if failure.status != 423 or attempt == 7:
                raise
            time.sleep(min(1, 0.25 * (attempt + 1)))


def header(values, name):
    return next((value for key, value in values.items() if key.lower() == name.lower()), None)


def sha(content):
    return hashlib.sha256(content).hexdigest()


def expect_error(action, statuses):
    try:
        action()
    except api.ApiFailure as failure:
        assert failure.status in statuses, (failure.status, failure.code)
        return {'status': failure.status, 'code': failure.code}
    raise AssertionError('Expected a rejected request')


def wait_workspace(client, identity):
    for _ in range(100):
        row = get(client, '/api/v1/workspaces/' + identity)[1]
        if row['state'] == 'READY':
            return row
        assert row['state'] not in ['ERROR', 'BLOCKED'], row.get('errorCode')
        time.sleep(1)
    raise RuntimeError('Original workspace still pending; no second create was sent')


def wait_preview(client, identity):
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        row = get(client, '/api/v1/previews/' + identity)[1]
        if row['state'] in ['READY', 'FAILED']:
            return row
        if row['state'] == 'BLOCKED':
            raise RuntimeError('Original preview BLOCKED: ' + identity + ' ' + str(row.get('errorCode')))
        time.sleep(1)
    raise RuntimeError('Original preview pending; query it instead of resubmitting: ' + identity)


def upload(client, journal, workspace, name, content):
    assert len(content) <= 4 * 1024 * 1024, 'Acceptance fixtures must remain small; this is not a product upload limit'
    body = {'path': name, 'bytes': len(content), 'sha256': sha(content)}
    row = journal.once(client, 'upload-create:' + name,
                       {'method': 'POST', 'path': '/api/v1/workspaces/' + workspace + '/uploads', 'body': body}, body)
    original = row
    row = get(client, '/api/v1/uploads/' + original['id'])[1]
    transfer_name = 'upload-content:' + name
    transfer_request = {'method': 'PUT', 'path': '/api/v1/uploads/' + row['id'] + '/content', 'bytes': len(content), 'sha256': sha(content)}
    if row['state'] == 'CREATED':
        journal.once(client, transfer_name, transfer_request, content, {'Content-Type': 'application/octet-stream'})
    elif row['state'] in ['UPLOADING', 'VERIFYING', 'UNKNOWN']:
        raise RuntimeError('Original upload unresolved; it was not retransmitted: ' + row['id'])
    row = get(client, '/api/v1/uploads/' + original['id'])[1]
    complete_name = 'upload-complete:' + name
    if row.get('operationId'):
        operation = {'id': row['operationId']}
        frozen = journal.rows.get(complete_name)
        if frozen and frozen.get('response') is None:
            # Actual upload metadata is the authoritative recovery binding.
            frozen.update(response=operation, phase='ACKNOWLEDGED_BY_QUERY')
            journal.write()
    else:
        assert row['state'] == 'VERIFIED', row['state']
        operation = journal.once(client, complete_name,
                                 {'method': 'POST', 'path': '/api/v1/uploads/' + original['id'] + '/complete'})
    outcome = api.wait_operation(client, operation['id'])
    assert outcome['state'] == 'COMMITTED', outcome.get('errorCode')
    metadata = get(client, '/api/v1/files/' + original['fileId'])[1]
    assert metadata['versionId'] == original['versionId'] and metadata['sha256'] == sha(content)
    actual = get(client, '/api/v1/files/' + metadata['id'] + '/content?versionId=' + original['versionId'], binary=True)
    assert actual[1] == content
    assert 'attachment' in header(actual[2], 'Content-Disposition')
    entry_key = 'upload-created-entry:' + name
    if entry_key not in journal.rows:
        journal.rows[entry_key] = metadata
        journal.write()
    frozen_entry = journal.rows[entry_key]
    assert frozen_entry['id'] == metadata['id'] and frozen_entry['versionId'] == original['versionId']
    return frozen_entry


def preview_request_key(name):
    # A corrected renderer is a different frozen preview input, not a replay of an old command.
    # Keep every original request/result in the journal, including historical failures.
    plan = json.loads((DATA / 'native-plan.json').read_text(encoding='utf-8'))
    image = plan['gateway']['candidate']['images']['preview-renderer']['configId']
    return 'preview-create:' + image + ':' + name


def preview(client, journal, file, requested='AUTO', label=None):
    name = label or file['name'] + ':' + requested
    body = {'versionId': file['versionId'], 'format': requested}
    key = preview_request_key(name)
    row = journal.once(client, key,
                       {'method': 'POST', 'path': '/api/v1/files/' + file['id'] + '/previews', 'body': body}, body)
    result = wait_preview(client, row['id'])
    assert result['fileId'] == file['id'] and result['versionId'] == file['versionId']
    journal.rows[key]['latest'] = result
    journal.write()
    return result


def preview_bytes(client, row):
    assert row['state'] == 'READY' and 0 <= row['bytes'] <= 4 * 1024 * 1024, row
    status, content, headers = get(client, '/api/v1/previews/' + row['id'] + '/content', binary=True)
    assert status == 200 and len(content) == row['bytes'] and sha(content) == row['sha256']
    assert header(headers, 'Content-Type').split(';')[0] == row['mediaType']
    assert header(headers, 'X-Content-Type-Options') == 'nosniff'
    assert header(headers, 'Cache-Control') == 'no-store'
    return content


def seed(client, journal):
    body = {'name': 'R53 isolated preview acceptance'}
    workspace = journal.once(client, 'workspace-create', {'method': 'POST', 'path': '/api/v1/workspaces', 'body': body}, body)
    wait_workspace(client, workspace['id'])
    manifest = json.loads((FIXTURES / 'fixture-manifest.json').read_text(encoding='utf-8'))
    files = {}
    for name in ['sample.docx', 'sample.xlsx', 'sample.pptx', 'sample.png', 'sample.gif', 'sample.webp',
                 'sample.txt', 'sample.html', 'empty.txt', 'corrupt.docx']:
        content = (FIXTURES / name).read_bytes()
        assert manifest[name] == {'bytes': len(content), 'sha256': sha(content)}, 'Generated fixture drift: ' + name
        files[name] = upload(client, journal, workspace['id'], name, content)
    record = {'workspaceId': workspace['id'], 'userId': client.me['id'], 'spaceId': client.space, 'files': files}
    atomic(DATA / 'native-preview-seed.json', record)
    return record


def verify(client, journal, ids):
    files = ids['files']
    outputs = {}
    for name, expected in [('sample.docx', 'PDF'), ('sample.xlsx', 'PDF'), ('sample.pptx', 'PDF'),
                           ('sample.png', 'IMAGE'), ('sample.gif', 'IMAGE'), ('sample.webp', 'IMAGE'),
                           ('sample.txt', 'TEXT'), ('sample.html', 'TEXT'), ('empty.txt', 'TEXT')]:
        row = preview(client, journal, files[name])
        assert row['state'] == 'READY' and row['format'] == expected, (name, row)
        content = preview_bytes(client, row)
        if expected == 'PDF':
            assert content.startswith(b'%PDF-') and 1 <= row['pages'] <= 200
        elif expected == 'IMAGE':
            assert content.startswith(b'\x89PNG\r\n\x1a\n')
        else:
            assert content == (FIXTURES / name).read_bytes()
        outputs[name] = row

    html = preview(client, journal, files['sample.html'], 'HTML_STATIC')
    sanitized = preview_bytes(client, html).decode('utf-8')
    assert html['format'] == 'HTML_STATIC' and '666' in sanitized
    for forbidden in ['<script', 'example.invalid', 'window.previewExecuted', '<img', 'onerror=']:
        assert forbidden.lower() not in sanitized.lower(), 'Active/external HTML was not removed'

    # Known successful repeat and a new idempotency identity must resolve the same cached job and cost nothing.
    name = preview_request_key('sample.docx:AUTO')
    frozen = journal.rows[name]
    before = get(client, '/api/v1/workspaces/' + ids['workspaceId'] + '/storage-usage')[1]
    repeated = client.request('POST', frozen['request']['path'], frozen['request']['body'], {'Idempotency-Key': frozen['key']})[1]
    cached = preview(client, journal, files['sample.docx'], label='same-version-cache')
    after = get(client, '/api/v1/workspaces/' + ids['workspaceId'] + '/storage-usage')[1]
    assert repeated['id'] == cached['id'] == outputs['sample.docx']['id']
    assert cached['revision'] == outputs['sample.docx']['revision']
    assert (before['committedBytes'], before['reservedBytes']) == (after['committedBytes'], after['reservedBytes'])
    expect_error(lambda: client.request('POST', frozen['request']['path'],
                                        {**frozen['request']['body'], 'format': 'PDF'}, {'Idempotency-Key': frozen['key']}), [409])

    # A true native PDF is a new immutable source version, not a second copy of a converted preview asset.
    content = preview_bytes(client, outputs['sample.docx'])
    native_pdf = upload(client, journal, ids['workspaceId'], 'sample.pdf', content)
    before_pdf = get(client, '/api/v1/workspaces/' + ids['workspaceId'] + '/storage-usage')[1]
    native = preview(client, journal, native_pdf)
    assert native['state'] == 'READY' and native['format'] == 'PDF' and preview_bytes(client, native) == content
    after_pdf = get(client, '/api/v1/workspaces/' + ids['workspaceId'] + '/storage-usage')[1]
    assert (before_pdf['committedBytes'], before_pdf['reservedBytes']) == (after_pdf['committedBytes'], after_pdf['reservedBytes'])
    last = min(63, len(content) - 1)
    status, selected, headers = get(client, '/api/v1/previews/' + native['id'] + '/content', binary=True,
                                    headers={'Range': 'bytes=0-' + str(last)})
    assert status == 206 and selected == content[:last + 1]
    assert header(headers, 'Content-Range') == 'bytes 0-' + str(last) + '/' + str(len(content))
    expect_error(lambda: get(client, '/api/v1/previews/' + native['id'] + '/content', binary=True,
                            headers={'Range': 'bytes=0-1,4-5'}), [416])

    before_bad = get(client, '/api/v1/workspaces/' + ids['workspaceId'] + '/storage-usage')[1]
    corrupt = preview(client, journal, files['corrupt.docx'])
    assert corrupt['state'] == 'FAILED' and corrupt['errorCode'] and not corrupt['retryable'], corrupt
    assert 'RETRY' not in corrupt['allowedActions']
    expect_error(lambda: get(client, '/api/v1/previews/' + corrupt['id'] + '/content', binary=True), [409])
    original = get(client, '/api/v1/files/' + files['corrupt.docx']['id'] + '/content?versionId=' + files['corrupt.docx']['versionId'], binary=True)[1]
    assert original == (FIXTURES / 'corrupt.docx').read_bytes()
    after_bad = get(client, '/api/v1/workspaces/' + ids['workspaceId'] + '/storage-usage')[1]
    assert (before_bad['committedBytes'], before_bad['reservedBytes']) == (after_bad['committedBytes'], after_bad['reservedBytes'])

    foreign = api.Client('r53_user')
    denied = []
    for path in ['/api/v1/previews/' + native['id'], '/api/v1/previews/' + native['id'] + '/content']:
        denied.append(expect_error(lambda path=path: get(foreign, path, binary=path.endswith('/content')), [403, 404]))
    denied.append(expect_error(lambda: foreign.request('POST', '/api/v1/files/' + native_pdf['id'] + '/previews',
                        {'versionId': native_pdf['versionId'], 'format': 'AUTO'}, {'Idempotency-Key': str(uuid.uuid4())}), [403, 404]))

    # An independent source is mutated so browser fixtures stay present for subsequent acceptance.
    pinned = upload(client, journal, ids['workspaceId'], 'pinned-source.txt', b'fixed preview source 666\n')
    pinned_preview = preview(client, journal, pinned)
    pinned_bytes = preview_bytes(client, pinned_preview)
    move = journal.once(client, 'pinned-move', {'method': 'POST', 'path': '/api/v1/files/' + pinned['id'] + '/move',
                                             'body': {'targetParentId': None, 'name': 'pinned-renamed.txt'}, 'revision': pinned['revision']},
                        {'targetParentId': None, 'name': 'pinned-renamed.txt'}, {'If-Match': '"' + str(pinned['revision']) + '"'})
    assert api.wait_operation(client, move['id'])['state'] == 'COMMITTED'
    moved = journal.rows.get('pinned-moved-metadata')
    if not moved:
        moved = get(client, '/api/v1/files/' + pinned['id'])[1]
        assert moved['path'] == 'pinned-renamed.txt' and moved['versionId'] == pinned['versionId']
        journal.rows['pinned-moved-metadata'] = moved
        journal.write()
    assert preview_bytes(client, pinned_preview) == pinned_bytes
    deletion = journal.once(client, 'pinned-delete', {'method': 'DELETE', 'path': '/api/v1/files/' + pinned['id'], 'revision': moved['revision']},
                            headers={'If-Match': '"' + str(moved['revision']) + '"'})
    assert api.wait_operation(client, deletion['id'])['state'] == 'COMMITTED'
    assert get(client, '/api/v1/files/' + pinned['id'])[1]['collectionState'] == 'DELETED'
    assert preview_bytes(client, pinned_preview) == pinned_bytes

    ordered = ['sample.docx', 'sample.xlsx', 'sample.pptx', 'sample.png', 'sample.txt', 'sample.html']
    browser = {'workspaceId': ids['workspaceId'], 'files': [
        {'id': files[name]['id'], 'versionId': files[name]['versionId'], 'name': name,
         'expectedFormat': outputs[name]['format'], 'expectedText': '666' if outputs[name]['format'] in ['PDF', 'TEXT'] else None,
         'sha256': files[name]['sha256']} for name in ordered]}
    browser['files'].append({'id': native_pdf['id'], 'versionId': native_pdf['versionId'], 'name': 'sample.pdf',
                             'expectedFormat': 'PDF', 'expectedText': '666', 'sha256': native_pdf['sha256']})
    atomic(DATA / 'native-preview-identities.json', browser)
    result = {'status': 'PASS', 'supplierCalls': 0, 'root': json.loads((DATA / 'native-plan.json').read_text())['root'],
              'scope': 'actual API/PG/MQ/signed runner/gVisor/S3 immutable Office/PDF/image/text/static HTML, cache+idempotency/quota/Range/hash/corrupt source download/foreign denial/pinned read after move+delete',
              'workspaceId': ids['workspaceId'], 'previews': {name: {'id': row['id'], 'format': row['format'], 'bytes': row['bytes'],
                'sha256': row['sha256'], 'pages': row['pages'], 'state': row['state']} for name, row in outputs.items()},
              'nativePdf': native, 'staticHtml': html, 'corruptDocument': corrupt, 'foreignDenials': denied,
              'quotaNativePdf': {'before': before_pdf['committedBytes'], 'after': after_pdf['committedBytes']},
              'pinnedAfterDelete': {'previewId': pinned_preview['id'], 'sha256': sha(pinned_bytes)}}
    atomic(DATA / 'native-api-result.json', result)
    print(json.dumps({'status': 'PASS', 'supplierCalls': 0, 'previews': len(outputs) + 4, 'result': str(DATA / 'native-api-result.json')}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['seed', 'verify', 'run'])
    args = parser.parse_args()
    plan = json.loads((DATA / 'native-plan.json').read_text(encoding='utf-8'))
    assert plan['project'] == 'agentplatform-r53-local' and plan['root'].startswith('/srv/agentplatform/acceptance/r44-r53-')
    assert api.BASE == 'http://127.0.0.1:18153'
    client = api.Client()
    journal = Journal()
    seed_file = DATA / 'native-preview-seed.json'
    if args.action == 'verify':
        ids = json.loads(seed_file.read_text(encoding='utf-8'))
    else:
        ids = seed(client, journal)
    if args.action != 'seed':
        verify(client, journal, ids)
    else:
        print('R53_ACTUAL_UPLOAD_SEED_PASS')


if __name__ == '__main__':
    main()
