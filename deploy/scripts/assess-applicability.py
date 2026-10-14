"""Assess individually reviewed OpenVEX statements; retain every unmatched finding.

This does not generate NOT_AFFECTED conclusions. Reviews bind raw report, SBOM,
product and hashed source/evidence files; a changed input invalidates the review.
"""
import argparse
import hashlib
import json
import pathlib
import re
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[2]
JUSTIFICATIONS = {'component_not_present', 'vulnerable_code_not_present',
                  'vulnerable_code_not_in_execute_path', 'vulnerable_code_cannot_be_controlled_by_adversary',
                  'inline_mitigations_already_exist'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def assess(report_path, sbom_path, review_path, root=ROOT):
    raw = json.loads(report_path.read_text(encoding='utf-8'))
    if not isinstance(raw.get('matches'), list) or raw.get('ignoredMatches'):
        raise ValueError('Missing or prefiltered raw findings; assessment incomplete')
    sbom = json.loads(sbom_path.read_text(encoding='utf-8'))
    review = json.loads(review_path.read_text(encoding='utf-8'))
    binding = review['binding']
    if binding['reportSha256'] != digest(report_path) or binding['sbomSha256'] != digest(sbom_path):
        raise ValueError('Report/SBOM changed; review invalid')
    image_id = sbom.get('source', {}).get('metadata', {}).get('imageID')
    product = binding['product']
    if image_id:
        if not re.fullmatch(r'pkg:oci/[a-zA-Z0-9._/-]+@' + re.escape(image_id), product):
            raise ValueError('Review product does not match actual image config')
    elif product != 'urn:agentplatform:sbom:sha256:' + digest(sbom_path):
        raise ValueError('Mutable or unrelated product identity')
    for section in ('sources', 'evidence'):
        if not binding.get(section):
            raise ValueError('Missing current source/evidence bindings')
        for name, expected in binding[section].items():
            path = (root / name).resolve()
            if pathlib.Path(name).is_absolute() or not path.is_relative_to(root.resolve()):
                raise ValueError('Evidence must stay inside review root')
            if not path.is_file() or digest(path) != expected:
                raise ValueError('Source/evidence changed: ' + name)
    statements = {}
    for statement in review['statements']:
        if statement['status'] != 'not_affected' or statement['justification'] not in JUSTIFICATIONS:
            raise ValueError('Only evidenced NOT_AFFECTED conclusions are supported')
        if not statement.get('impact_statement') or not statement.get('provenance'):
            raise ValueError('Missing review rationale or primary provenance')
        if any(urllib.parse.urlparse(url).scheme != 'https' or not urllib.parse.urlparse(url).hostname for url in statement['provenance']):
            raise ValueError('Primary evidence needs an attributable HTTPS source')
        products = statement['products']
        if len(products) != 1 or products[0]['@id'] != product:
            raise ValueError('Unrelated or broad VEX product')
        components = products[0].get('subcomponents', [])
        if len(components) != 1:
            raise ValueError('Exactly one component per statement required')
        purl = components[0]['@id']
        advisory = statement['vulnerability']['name']
        if '@' not in purl or '*' in purl or not re.fullmatch(r'(CVE-\d{4}-\d+|GHSA-[a-z0-9-]+)', advisory):
            raise ValueError('Exact component version and advisory required')
        key = (advisory, purl)
        if key in statements:
            raise ValueError('Duplicate review statement')
        statements[key] = statement
    present = {(m['vulnerability']['id'], m['artifact'].get('purl')) for m in raw.get('matches', [])}
    if not set(statements).issubset(present):
        raise ValueError('Review includes a finding absent from this raw product report')
    findings = []
    for match in raw.get('matches', []):
        vulnerability = match['vulnerability']
        key = (vulnerability['id'], match['artifact'].get('purl'))
        findings.append({'advisory': key[0], 'purl': key[1], 'severity': vulnerability['severity'],
                         'disposition': 'NOT_AFFECTED' if key in statements else 'UNRESOLVED'})
    blocking = sum(f['disposition'] == 'UNRESOLVED' and f['severity'] in ('High', 'Critical') for f in findings)
    return {'product': product, 'binding': binding, 'findings': findings,
            'unresolvedHighCritical': blocking, 'status': 'FAIL' if blocking else 'PASS'}


def main():
    parser = argparse.ArgumentParser()
    for arg in ('report', 'sbom', 'review', 'output'):
        parser.add_argument('--' + arg, type=pathlib.Path, required=True)
    args = parser.parse_args()
    result = assess(args.report, args.sbom, args.review)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    raise SystemExit(1 if result['unresolvedHighCritical'] else 0)


if __name__ == '__main__':
    main()
