"""Isolated R6.3 retrieval scale probe using the real local API and PostgreSQL."""
from pathlib import Path
import argparse
import importlib.util
import json
import math
import statistics
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache' / 'r63-local'
MANIFEST = json.loads((DATA / 'fixture' / 'manifest.json').read_text(encoding='utf-8'))
DATABASE = MANIFEST['database']
JOURNAL_PATH = DATA / f'knowledge-acceptance-{DATABASE}.json'
RESULT_PATH = DATA / f'knowledge-acceptance-result-{DATABASE}.json'
CONTAINER = 'agentplatform-r63-local-postgres-1'
TARGETS = (10_000, 20_000)
SAMPLES = 40
WARMUP = 5
QUALITY_QUERIES = [
    {'id': 'zh-main', 'query': '北极星流程 实时权限 持久化修改', 'marker': 'R63_NORTHSTAR_731'},
    {'id': 'zh-permission-before-write', 'query': '北极星流程在持久化修改前的实时权限检查', 'marker': '先确认实时权限'},
    {'id': 'zh-update-authorization', 'query': '执行持久化修改前先确认什么授权', 'marker': '实时权限'},
    {'id': 'zh-source-location', 'query': '知识索引要保留原始版本和页码或文本偏移', 'marker': 'page or text offsets'},
    {'id': 'zh-location-detail', 'query': '索引来源定位使用页面或文本位置', 'marker': 'page or text offsets'},
    {'id': 'en-source-version', 'query': 'Knowledge indexing immutable source versions text offsets', 'marker': 'immutable source versions'},
    {'id': 'en-position-preservation', 'query': 'What source positions does knowledge indexing preserve?', 'marker': 'page or text offsets'},
    {'id': 'cross-en-to-zh-permission', 'query': 'Before a persistent update, check current permissions in real time.', 'marker': '实时权限'},
    {'id': 'cross-zh-to-en-version', 'query': '知识检索必须依据不可变的源文件版本', 'marker': 'immutable source versions'},
    {'id': 'cross-zh-to-en-offset', 'query': '文档的页码和文本偏移必须保留', 'marker': 'page or text offsets'},
]


def load_acceptance_client():
    spec = importlib.util.spec_from_file_location('r63_knowledge_acceptance', ROOT / 'deploy/scripts/r63-knowledge-acceptance.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def scalar(sql):
    result = subprocess.run(['docker', 'exec', '-i', CONTAINER, 'psql', '-v', 'ON_ERROR_STOP=1', '-X', '-A', '-t', '-U', 'postgres', '-d', DATABASE],
                            input=sql, capture_output=True, text=True, timeout=90)
    if result.returncode:
        detail = ' '.join(result.stderr.splitlines()[:2])
        raise RuntimeError('R63 isolated retrieval scale SQL failed (' + str(result.returncode) + '): ' + detail[:300])
    return result.stdout.strip()


def active_fixture(base_id, document_id):
    if str(uuid.UUID(base_id)) != base_id or str(uuid.UUID(document_id)) != document_id:
        raise RuntimeError('Fixture identity is malformed')
    sql = f"""
SELECT d.space_id::text,d.id::text,d.active_index_version::text,count(c.ordinal)::bigint
FROM pf.knowledge_document d
JOIN pf.knowledge_base b ON b.space_id=d.space_id AND b.id=d.knowledge_base_id AND b.state='ACTIVE'
JOIN pf.knowledge_index_version v ON v.space_id=d.space_id AND v.document_id=d.id AND v.index_version=d.active_index_version AND v.state='READY'
LEFT JOIN pf.knowledge_index_chunk c ON c.space_id=d.space_id AND c.document_id=d.id AND c.index_version=d.active_index_version
WHERE b.id='{base_id}' AND d.id='{document_id}' AND d.status='READY'
GROUP BY d.space_id,d.id,d.active_index_version
"""
    rows = [row for row in scalar(sql).splitlines() if row]
    if len(rows) != 1:
        raise RuntimeError('Expected exactly one ready isolated benchmark document')
    space, document, version, count = rows[0].split('|')
    return space, document, version, int(count)


def add_noise(space, document, version, current, target):
    missing = target - current
    if missing <= 0:
        return current
    start = int(scalar(f"SELECT COALESCE(max(ordinal),-1)+1 FROM pf.knowledge_index_chunk WHERE space_id='{space}' AND document_id='{document}' AND index_version='{version}'"))
    statement = f"""
INSERT INTO pf.knowledge_index_chunk(space_id,document_id,index_version,ordinal,chunk_sha256,body,embedding,source_location,source_start,source_end)
SELECT '{space}','{document}','{version}',{start}+n,
       repeat(md5('r63-scale-'||n::text),2),'R63_SCALE_UNRELATED_FIXTURE_'||n::text,
       array_fill(0::real,ARRAY[384])::pf.vector,
       jsonb_build_object('kind','scale-fixture','ordinal',n),n,n+1
FROM generate_series(1,{missing}) n
"""
    scalar(statement)
    observed = int(scalar(f"SELECT count(*) FROM pf.knowledge_index_chunk WHERE space_id='{space}' AND document_id='{document}' AND index_version='{version}'"))
    if observed != target:
        raise RuntimeError('Synthetic scale fixture count did not reach the requested target')
    scalar(f"ANALYZE pf.knowledge_index_chunk")
    return observed


def measure(client, base_id, expected_marker):
    query = '北极星流程 实时权限 持久化修改'
    payload = {'query': query, 'baseIds': [base_id], 'limit': 8, 'semanticRequired': True}
    for _ in range(WARMUP):
        response = client.call('POST', '/api/v1/knowledge/search', payload)
        if not any(expected_marker in hit['text'] for hit in response['hits']):
            raise RuntimeError('Warm-up lost the expected grounded search hit')
    samples = []
    for _ in range(SAMPLES):
        started = time.perf_counter()
        response = client.call('POST', '/api/v1/knowledge/search', payload)
        samples.append((time.perf_counter() - started) * 1000)
        if not response.get('semanticAvailable') or not any(expected_marker in hit['text'] for hit in response['hits']):
            raise RuntimeError('Measured retrieval lost semantic availability or the expected source')
    samples.sort()
    p95 = samples[math.ceil(0.95 * len(samples)) - 1]
    return {'samples': len(samples), 'warmup': WARMUP, 'p50Ms': round(statistics.median(samples), 2),
            'p95Ms': round(p95, 2), 'maxMs': round(samples[-1], 2), 'expectedHitReturned': True}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--quality-only', action='store_true', help='Reuse an existing 20k scale receipt and run only the fixed retrieval-quality corpus')
    args = parser.parse_args()
    journal = json.loads(JOURNAL_PATH.read_text(encoding='utf-8'))
    result = json.loads(RESULT_PATH.read_text(encoding='utf-8'))
    base_id = journal['knowledge-base']['response']['id']
    document_id = journal['knowledge-document']['response']['id']
    if result.get('status') != 'PASS' or result.get('supplierCalls') != 0:
        raise RuntimeError('The isolated R6.3 real-API baseline is not PASS')
    client_type = load_acceptance_client().Client
    client = client_type('r63_admin')
    space, document, version, count = active_fixture(base_id, document_id)
    if document != document_id:
        raise RuntimeError('Benchmark document identity changed')
    if args.quality_only:
        prior = json.loads((DATA / f'retrieval-scale-result-{DATABASE}.json').read_text(encoding='utf-8'))
        if prior.get('database') != DATABASE + ' (isolated fixture)' or prior.get('documentId') != document or count != 20_000:
            raise RuntimeError('Quality-only mode requires the matching immutable 20k scale fixture')
        measurements = prior['measurements']
    else:
        if count != 1:
            raise RuntimeError('Scale probe requires a fresh isolated acceptance database with exactly one real indexed chunk')
        measurements = {}
        for target in TARGETS:
            count = add_noise(space, document, version, count, target)
            measurements[str(target)] = {'chunkCount': count, **measure(client, base_id, 'R63_NORTHSTAR_731')}
    quality_rows = []
    for case in QUALITY_QUERIES:
        response = client.call('POST', '/api/v1/knowledge/search',
                               {'query': case['query'], 'baseIds': [base_id], 'limit': 8, 'semanticRequired': True})
        hit = response.get('semanticAvailable') and any(case['marker'] in row['text'] for row in response['hits'])
        quality_rows.append({'id': case['id'], 'hitAt8': bool(hit), 'resultCount': len(response['hits'])})
    no_answer = client.call('POST', '/api/v1/knowledge/search',
                            {'query': 'How do I bake sourdough bread in a wood oven?', 'baseIds': [base_id], 'limit': 8, 'semanticRequired': True})
    hit_rate = sum(row['hitAt8'] for row in quality_rows) / len(quality_rows)
    targets_met = {key: value['p95Ms'] <= 1000 for key, value in measurements.items()}
    quality = {'corpus': 'fixed Chinese, English and cross-lingual query set', 'queries': quality_rows,
               'hitAt8': sum(row['hitAt8'] for row in quality_rows), 'queryCount': len(quality_rows),
               'hitRate': round(hit_rate, 3), 'targetMet': hit_rate >= 0.90,
               'noAnswerHits': len(no_answer['hits']), 'noAnswerTargetMet': len(no_answer['hits']) == 0}
    passed = all(targets_met.values()) and quality['targetMet'] and quality['noAnswerTargetMet']
    output = {'status': 'PASS' if passed else 'FAIL', 'database': DATABASE + ' (isolated fixture)', 'baseId': base_id,
              'documentId': document_id, 'activeIndexVersion': version, 'corpusKind': 'one real indexed source plus unrelated zero-vector scale rows',
              'supplierCalls': 0, 'measurements': measurements,
              'targetsMet': targets_met, 'quality': quality}
    path = DATA / f'retrieval-scale-result-{DATABASE}.json'
    path.write_text(json.dumps(output, indent=2) + '\n', encoding='utf-8')
    print('R63_RETRIEVAL_SCALE_AND_QUALITY_PASS' if passed else 'R63_RETRIEVAL_SCALE_OR_QUALITY_TARGET_MISSED')
    if not passed:
        raise SystemExit(2)


if __name__ == '__main__':
    main()
