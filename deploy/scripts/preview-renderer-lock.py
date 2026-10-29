"""Check the exact isolated renderer classpath, independent of the ordinary app runtime."""
from pathlib import Path
import argparse, hashlib, json, re

ROOT = Path(__file__).resolve().parents[2]
LIBS = ROOT / 'backend/platform-app/target/renderer-libs'
LOCK = ROOT / 'deploy/preview-renderer.lock.json'


def group(artifact):
    if artifact.startswith('jodconverter-'): return 'org.jodconverter'
    if artifact == 'libreoffice': return 'org.libreoffice'
    if artifact in ['pdfbox', 'pdfbox-io', 'fontbox']: return 'org.apache.pdfbox'
    if artifact.startswith('imageio-'): return 'com.twelvemonkeys.imageio'
    if artifact in ['common-image', 'common-io', 'common-lang']: return 'com.twelvemonkeys.common'
    return {'commons-io': 'commons-io', 'commons-logging': 'commons-logging', 'commons-lang3': 'org.apache.commons',
            'gson': 'com.google.code.gson', 'slf4j-api': 'org.slf4j', 'jsoup': 'org.jsoup'}[artifact]


def actual():
    if not LIBS.is_dir(): raise SystemExit('Renderer libraries not prepared')
    records = []
    for path in sorted(LIBS.glob('*.jar')):
        match = re.fullmatch(r'(.+?)-(\d[\w.\-]*)\.jar', path.name)
        if not match: raise SystemExit('Unrecognized renderer artifact')
        artifact, version = match.groups()
        records.append({'coordinate': f'{group(artifact)}:{artifact}:{version}', 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    if not records: raise SystemExit('Empty renderer classpath')
    return {'schemaVersion': 1, 'scope': 'Isolated renderer classifier only; does not authorize ordinary app classpath drift',
            'libreOffice': {'version': '26.2.6', 'url': 'https://download.documentfoundation.org/libreoffice/stable/26.2.6/deb/x86_64/LibreOffice_26.2.6_Linux_x86-64_deb.tar.gz',
                           'sha256': 'fd0e8f8f2408dd2e5b90286e60f3f97cf566ba441cd48cfc5bcc68067303e0bc'}, 'artifacts': records}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    resolved = actual()
    if args.write: LOCK.write_text(json.dumps(resolved, indent=2) + '\n', encoding='utf-8', newline='\n')
    elif not LOCK.is_file() or json.loads(LOCK.read_text(encoding='utf-8')) != resolved:
        raise SystemExit('Renderer artifact drift; do not silently overwrite the accepted lock')
    print(json.dumps({'status': 'PASS', 'artifacts': len(resolved['artifacts'])}))
