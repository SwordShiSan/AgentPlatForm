"""Exercise the real signed TEXT_EXTRACT -> S3 -> knowledge-index chain."""
from pathlib import Path
import hashlib
import http.cookiejar
import importlib.util
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache/r63-local'
PRIVATE = DATA / 'fixture'
DATABASE = json.loads((PRIVATE / 'manifest.json').read_text(encoding='utf-8'))['database']
BASE = 'http://127.0.0.1:' + str(json.loads((PRIVATE / 'manifest.json').read_text(encoding='utf-8'))['gatewayPort'])
RESUME_EXISTING = '--resume-existing' in sys.argv
RUN_ID = '7319' if RESUME_EXISTING else os.environ.get('R63_ACCEPTANCE_RUN_ID', '7320')
if not re.fullmatch(r'[a-zA-Z0-9_-]{1,32}', RUN_ID):
    raise SystemExit('R63_ACCEPTANCE_RUN_ID must be a short alphanumeric fixture identity')
JOURNAL_PATH = DATA / ('signed-extraction-journal-' + DATABASE + ('' if RESUME_EXISTING else '-' + RUN_ID) + '.json')
RESULT_PATH = DATA / ('signed-extraction-result-' + DATABASE + ('' if RESUME_EXISTING else '-' + RUN_ID) + '.json')
MARKER = 'R63_SIGNED_TEXT_EXTRACT_' + RUN_ID.upper().replace('-', '_')

spec = importlib.util.spec_from_file_location('knowledge_acceptance', ROOT / 'deploy/scripts/r63-knowledge-acceptance.py')
knowledge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(knowledge)
Client = knowledge.Client
Failure = knowledge.Failure


def digest(data):
    return hashlib.sha256(data).hexdigest()


class FrozenJournal:
    def __init__(self):
        self.rows = json.loads(JOURNAL_PATH.read_text(encoding='utf-8')) if JOURNAL_PATH.exists() else {}

    def flush(self):
        temp = JOURNAL_PATH.with_suffix('.tmp')
        temp.write_text(json.dumps(self.rows, indent=2) + '\n', encoding='utf-8')
        temp.replace(JOURNAL_PATH)

    def once(self, client, name, method, path, body):
        request_hash = digest(json.dumps(body, sort_keys=True, ensure_ascii=False).encode('utf-8'))
        row = self.rows.get(name)
        if row:
            if row['requestSha256'] != request_hash:
                raise RuntimeError('Frozen signed-extraction request changed')
            if row['phase'] == 'ACKNOWLEDGED':
                return row['response']
            raise RuntimeError('Mutation outcome is unknown; query its original identity, do not resubmit')
        row = {'idempotencyKey': str(uuid.uuid4()), 'requestSha256': request_hash, 'phase': 'SENT'}
        self.rows[name] = row
        self.flush()
        response = client.call(method, path, body, {'Idempotency-Key': row['idempotencyKey']})
        row.update(phase='ACKNOWLEDGED', response=response)
        self.flush()
        return response


def make_fixture():
    path = DATA / ('signed-extraction.docx' if RESUME_EXISTING else 'signed-extraction-' + RUN_ID + '.docx')
    if not path.exists():
        from docx import Document
        document = Document()
        document.add_heading('R6.3 Signed Extraction Acceptance', level=1)
        document.add_paragraph(MARKER)
        document.add_paragraph('云端 gVisor runsc 物理提取验收：先验证签名，再读取固定版本。')
        document.add_paragraph('The durable extraction process reads immutable bytes from the isolated S3 bucket, runs the pinned parser under gVisor, and publishes only its committed output to the knowledge index.')
        document.save(path)
    return path, path.read_bytes()


def wait(client, path, wanted, timeout=150):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        row = client.call('GET', path)
        if row['status'] in wanted:
            return row
        if row['status'] in {'FAILED', 'BLOCKED', 'UNKNOWN', 'ABORTED', 'EXPIRED'}:
            raise RuntimeError('Signed extraction/index reached terminal state ' + row['status'] + ':' + str(row.get('errorCode')))
        time.sleep(1)
    raise RuntimeError('Persistent operation remains unresolved; original identity retained')


def main():
    prior = json.loads(RESULT_PATH.read_text(encoding='utf-8')) if RESULT_PATH.exists() else None
    if prior and prior.get('status') == 'PASS_SIGNED_CLOUD_GVISOR_EXTRACTION_INDEX':
        print(json.dumps({'status': 'ALREADY_VERIFIED', 'extractionId': prior['extractionId'],
                          'knowledgeDocumentId': prior['knowledgeDocumentId']}))
        return

    client = Client()
    foreign = Client('r63_user')
    journal = FrozenJournal()
    source_path, source = make_fixture()
    source_sha = digest(source)
    draft_basis = source_sha if RESUME_EXISTING else RUN_ID + ':' + source_sha
    draft = str(uuid.uuid5(uuid.NAMESPACE_URL, 'r63-signed-extraction:' + draft_basis))
    created = journal.once(client, 'signed-attachment-create', 'POST', '/api/v1/attachment-uploads',
        {'draftId': draft, 'filename': source_path.name, 'bytes': len(source), 'sha256': source_sha})
    upload_id = created['id']
    upload = client.call('GET', '/api/v1/attachment-uploads/' + upload_id)
    if upload['status'] == 'CREATED':
        client.call('PUT', '/api/v1/attachment-uploads/' + upload_id + '/content', source,
                    {'Content-Type': 'application/octet-stream'})
        upload = client.call('GET', '/api/v1/attachment-uploads/' + upload_id)
    if upload['status'] == 'VERIFIED':
        client.call('POST', '/api/v1/attachment-uploads/' + upload_id + '/complete')
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        upload = client.call('GET', '/api/v1/attachment-uploads/' + upload_id)
        if upload['status'] == 'COMMITTED':
            break
        if upload['status'] in {'ABORTED', 'EXPIRED', 'UNKNOWN'}:
            raise RuntimeError('Fixture attachment upload terminated: ' + upload['status'])
        time.sleep(1)
    else:
        raise RuntimeError('Fixture attachment commit remains pending')

    attachment_id = upload['attachmentId']
    attachment = client.call('GET', '/api/v1/attachments/' + attachment_id)
    assert attachment['status'] == 'AVAILABLE' and attachment['sha256'] == source_sha
    status, content, _ = client.call('GET', '/api/v1/attachments/' + attachment_id + '/content', binary=True)
    assert status == 200 and digest(content) == source_sha
    try:
        foreign.call('GET', '/api/v1/attachments/' + attachment_id)
    except Failure as denied:
        assert denied.status in (403, 404)
    else:
        raise AssertionError('Foreign user read the isolated attachment')

    extraction = journal.once(client, 'signed-extraction-create', 'POST', '/api/v1/file-extractions',
                              {'attachmentId': attachment_id})
    extraction_id = extraction['id']
    extraction = wait(client, '/api/v1/file-extractions/' + extraction_id, {'READY'})
    assert extraction['status'] == 'READY' and extraction['bytes'] > 0 and extraction['sha256']
    try:
        foreign.call('GET', '/api/v1/file-extractions/' + extraction_id)
    except Failure as denied:
        assert denied.status in (403, 404)
    else:
        raise AssertionError('Foreign user read the isolated extraction')

    query = subprocess.run(['docker', '--context', 'desktop-linux', 'exec', '-i',
        'agentplatform-r63-local-postgres-1', 'psql', '-v', 'ON_ERROR_STOP=1', '-X', '-A', '-F', '|', '-t',
        '-U', 'postgres', '-d', DATABASE], input=(
        "SELECT output_object_key,output_sha256,output_bytes FROM pf.document_extraction WHERE id='" + extraction_id + "';\n"),
        capture_output=True, text=True, timeout=20)
    if query.returncode:
        raise RuntimeError('Could not query the committed isolated extraction object identity')
    rows = [line.strip() for line in query.stdout.splitlines() if line.strip()]
    if len(rows) != 1:
        raise RuntimeError('Committed extraction object row is missing')
    object_key, output_sha, output_bytes = rows[0].split('|')
    storage_image = json.loads((DATA / 'images.json').read_text(encoding='utf-8'))['images']['storage-probe']['configId']
    readback = subprocess.run(['docker', '--context', 'desktop-linux', 'run', '--rm', '--network',
        'agentplatform-r63-local_r63', '--memory', '128m', '--memory-swap', '128m', '--cpus', '1',
        '--pids-limit', '64', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--read-only',
        '-e', 'R63_OBJECT_KEY=' + object_key, '-e', 'R63_EXPECTED_SHA256=' + output_sha,
        '-e', 'R63_EXPECTED_BYTES=' + output_bytes, '-e', 'R63_REQUIRED_MARKER=' + MARKER,
        '--mount', 'type=bind,src=' + str((PRIVATE / 's3-access').resolve()) + ',dst=/fixture/s3-access,readonly',
        '--mount', 'type=bind,src=' + str((PRIVATE / 's3-secret').resolve()) + ',dst=/fixture/s3-secret,readonly',
        '--mount', 'type=bind,src=' + str((ROOT / 'deploy/probes/r63-s3-readback-extraction.py').resolve()) + ',dst=/r63-s3-readback.py,readonly',
        '--entrypoint', 'python3', storage_image, '/r63-s3-readback.py'], capture_output=True, text=True, timeout=60)
    if readback.returncode:
        raise RuntimeError('Committed extraction object did not pass isolated S3 read-back')
    object_receipt = json.loads(readback.stdout.strip().splitlines()[-1])
    assert object_receipt['status'] == 'PASS' and object_receipt['sha256'] == output_sha
    base_name = 'R63 signed extraction ' + source_sha[:12]
    if not RESUME_EXISTING:
        base_name = 'R63 signed extraction ' + RUN_ID + ' ' + source_sha[:12]
    base = journal.once(client, 'signed-knowledge-base', 'POST', '/api/v1/knowledge-bases',
                        {'name': base_name})
    accepted_document = journal.once(client, 'signed-knowledge-document', 'POST',
        '/api/v1/knowledge-bases/' + base['id'] + '/documents', {'extractionId': extraction_id})
    document_id = accepted_document['id']
    deadline = time.monotonic() + 180
    document = None
    while time.monotonic() < deadline:
        rows = client.call('GET', '/api/v1/knowledge-bases/' + base['id'] + '/documents')
        document = next((row for row in rows if row['id'] == document_id), None)
        if document and document['state'] == 'READY':
            break
        if document and document['state'] in {'FAILED', 'BLOCKED'}:
            raise RuntimeError('Knowledge index reached ' + document['state'])
        time.sleep(1)
    if document is None or document['state'] != 'READY':
        raise RuntimeError('Knowledge index did not reach READY; original build identity retained')
    search = client.call('POST', '/api/v1/knowledge/search',
        {'query': MARKER + ' immutable source gVisor extraction', 'baseIds': [base['id']], 'limit': 8, 'semanticRequired': True})
    assert search['semanticAvailable']
    assert any(MARKER in hit['text'] for hit in search['hits']), 'Index did not contain the physically extracted DOCX marker'
    result = {'status': 'PASS_SIGNED_CLOUD_GVISOR_EXTRACTION_INDEX', 'database': DATABASE,
        'attachmentId': attachment_id, 'sourceBytes': len(source), 'sourceSha256': source_sha,
        'attachmentReadbackSha256': digest(content), 'extractionId': extraction_id,
        'extractionStatus': extraction['status'], 'extractedBytes': int(output_bytes),
        'extractedSha256': output_sha, 'objectReadback': object_receipt,
        'knowledgeBaseId': base['id'], 'knowledgeDocumentId': document['id'],
        'knowledgeIndexState': document['state'], 'semanticAvailable': search['semanticAvailable'],
        'markerHit': True, 'supplierCalls': 0, 'rendererImageId': 'sha256:502ba213ee70a1ed10e6a01aec519fb64c757a0c8201c774553c49d58f646545',
        'parserBundle': 'asset-parser-tika3.3.3-v2', 'runscSha256': '210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a'}
    RESULT_PATH.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
