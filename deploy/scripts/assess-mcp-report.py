"""Assess one exact raw report using a source/SBOM-bound VEX, never a global ignore."""
import argparse
import hashlib
import json
import pathlib
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--report', type=pathlib.Path, required=True)
parser.add_argument('--sbom', type=pathlib.Path, required=True)
parser.add_argument('--vex', type=pathlib.Path, required=True)
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
proof = json.loads(args.vex.with_suffix('.proof.json').read_text())
if proof['sbomSha256'] != hashlib.sha256(args.sbom.read_bytes()).hexdigest():
    raise SystemExit('VEX invalidated: SBOM changed')
vex = json.loads(args.vex.read_text())
statement = vex['statements'][0]
if statement['products'][0]['@id'] != proof['product'] or statement['products'][0]['subcomponents'][0]['@id'] != 'pkg:maven/io.modelcontextprotocol.sdk/mcp-core@0.17.2?type=jar' or statement['vulnerability']['name'] != 'GHSA-8jxr-pr72-r468' or statement['status'] != 'not_affected':
    raise SystemExit('Invalid applicability statement')
root = pathlib.Path(__file__).resolve().parents[2]
files = sorted(p for module in (root / 'backend').glob('platform-*') for source in ['src/main/java', 'src/test/java'] for p in (module / source).rglob('*.java'))
current = hashlib.sha256(b''.join(p.relative_to(root).as_posix().encode() + p.read_bytes() for p in files)).hexdigest()
if current != proof['sourceSha256']:
    raise SystemExit('VEX invalidated: sources changed')
reports = list((root / 'backend/platform-app/target/surefire-reports').glob('TEST-*HealthHttpTest.xml'))
if len(reports) != 1 or hashlib.sha256(reports[0].read_bytes()).hexdigest() != proof['httpReportSha256']:
    raise SystemExit('VEX invalidated: runtime evidence changed')
cases = [t for t in ET.parse(reports[0]).getroot().findall('testcase') if t.attrib['name'] == 'platformDoesNotExposeMcpServerTransports']
if len(cases) != 1 or any(cases[0].find(tag) is not None for tag in ['failure', 'error', 'skipped']):
    raise SystemExit('HTTP applicability proof no longer passes')
raw = json.loads(args.report.read_text(encoding='utf-8'))
matches = raw['matches']
assessments = []
for match in matches:
    vulnerability, artifact = match['vulnerability'], match['artifact']
    exact = vulnerability['id'] == 'GHSA-8jxr-pr72-r468' and artifact.get('purl') == 'pkg:maven/io.modelcontextprotocol.sdk/mcp-core@0.17.2?type=jar'
    assessments.append({'advisory': vulnerability['id'], 'purl': artifact.get('purl'),
                        'severity': vulnerability['severity'], 'status': 'NOT_AFFECTED' if exact else 'MATCHED'})
blocking = [a for a in assessments if a['severity'] in ['High', 'Critical'] and a['status'] != 'NOT_AFFECTED']
result = {'rawReportSha256': hashlib.sha256(args.report.read_bytes()).hexdigest(), 'sbomSha256': proof['sbomSha256'],
          'product': proof['product'], 'vexSha256': hashlib.sha256(args.vex.read_bytes()).hexdigest(),
          'assessments': assessments, 'unresolvedHighCritical': len(blocking), 'status': 'PASS' if not blocking else 'FAIL'}
args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print('Assessment:', result['status'], 'unresolved high/critical:', len(blocking))
if blocking:
    raise SystemExit(1)
