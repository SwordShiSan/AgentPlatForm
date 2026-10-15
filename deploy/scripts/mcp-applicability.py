"""Produce product-bound MCP VEX only after source + actual HTTP proof passes."""
import argparse
import hashlib
import json
import pathlib
import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[2]
PURL = 'pkg:maven/io.modelcontextprotocol.sdk/mcp-core@0.17.2?type=jar'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sbom', type=pathlib.Path, required=True)
    parser.add_argument('--product', required=True, help='immutable image PURL or hashed artifact identity')
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not re.search(r'sha256:[0-9a-f]{64}(?:[?&]|$)', args.product):
        raise SystemExit('Mutable or unbound product identity')
    sbom_bytes = args.sbom.read_bytes()
    sbom_hash = hashlib.sha256(sbom_bytes).hexdigest()
    sbom = json.loads(sbom_bytes)
    image_id = sbom.get('source', {}).get('metadata', {}).get('imageID')
    if image_id:
        if image_id not in args.product:
            raise SystemExit('Product does not match actual Syft image identity')
    elif args.product != 'urn:agentplatform:sbom:sha256:' + sbom_hash:
        raise SystemExit('Non-image product must bind exact SBOM bytes')
    files = []
    for module in (ROOT / 'backend').glob('platform-*'):
        for source in ['src/main/java', 'src/test/java']:
            files.extend((module / source).rglob('*.java'))
    prohibited = re.compile(r'(?:import\s+[^;]*(?:McpServer|ServerTransport)|new\s+(?:McpServer|\w*ServerTransport)|McpServer\.(?:sync|async)|Class\.forName\([^\n]*McpServer)')
    for file in files:
        if prohibited.search(file.read_text(encoding='utf-8')):
            raise SystemExit('MCP server path present: ' + file.relative_to(ROOT).as_posix())
    reports = list((ROOT / 'backend/platform-app/target/surefire-reports').glob('TEST-*HealthHttpTest.xml'))
    if len(reports) != 1:
        raise SystemExit('Missing real HTTP proof')
    report = ET.parse(reports[0]).getroot()
    cases = [t for t in report.findall('testcase') if t.attrib['name'] == 'platformDoesNotExposeMcpServerTransports']
    if len(cases) != 1 or any(cases[0].find(tag) is not None for tag in ['failure', 'error', 'skipped']):
        raise SystemExit('HTTP MCP non-exposure was not PASS')
    source_digest = hashlib.sha256(b''.join(file.relative_to(ROOT).as_posix().encode() + file.read_bytes() for file in sorted(files))).hexdigest()
    now = datetime.now(timezone.utc).isoformat()
    statement = {'vulnerability': {'name': 'GHSA-8jxr-pr72-r468'},
                 'products': [{'@id': args.product, 'subcomponents': [{'@id': PURL}]}],
                 'status': 'not_affected', 'justification': 'vulnerable_code_not_in_execute_path',
                 'impact_statement': 'MCP library is retained for SDK client compatibility. No MCP server or server transport is instantiated by platform/probe sources; real HTTP GET/POST initialize paths and beans prove no server entry. Revalidate on source/dependency/entry change.', 'timestamp': now}
    vex = {'@context': 'https://openvex.dev/ns/v0.2.0', '@id': 'urn:agentplatform:vex:sha256:' + sbom_hash,
           'author': 'AgentPlatForm R0.4 review', 'version': 1, 'timestamp': now, 'statements': [statement]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(vex, indent=2) + '\n', encoding='utf-8')
    proof = {'product': args.product, 'sbomSha256': sbom_hash, 'sourceSha256': source_digest,
             'httpReportSha256': hashlib.sha256(reports[0].read_bytes()).hexdigest(),
             'status': 'NOT_AFFECTED', 'advisory': 'GHSA-8jxr-pr72-r468'}
    args.output.with_suffix('.proof.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    print('Exact product/source/SBOM/HTTP proof recorded; no global ignore')


if __name__ == '__main__':
    main()
