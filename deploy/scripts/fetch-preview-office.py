"""Fetch the fixed LibreOffice binary distribution once; verify the upstream SHA before use."""
from pathlib import Path
import hashlib, json, time, urllib.request

ROOT = Path(__file__).resolve().parents[2]
VERSION = '26.2.6'
FILE = f'LibreOffice_{VERSION}_Linux_x86-64_deb.tar.gz'
SHA256 = 'fd0e8f8f2408dd2e5b90286e60f3f97cf566ba441cd48cfc5bcc68067303e0bc'
TARGET = ROOT / '.cache/foundation' / FILE
BASES = ['https://mirrors.ustc.edu.cn/tdf/libreoffice/stable/',
         'https://mirrors.nju.edu.cn/tdf/libreoffice/stable/',
         'https://download.documentfoundation.org/libreoffice/stable/']


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    if TARGET.exists():
        if sha(TARGET) != SHA256: raise SystemExit('Existing Office cache hash mismatch; preserve it for diagnosis')
        print(json.dumps({'status': 'PASS', 'cached': True, 'sha256': SHA256})); return
    pending = TARGET.with_suffix(TARGET.suffix + '.partial')
    for base in BASES:
        started = time.monotonic()
        url = f'{base}{VERSION}/deb/x86_64/{FILE}'
        try:
            total = 0
            with urllib.request.urlopen(url, timeout=20) as source, pending.open('wb') as output:
                while chunk := source.read(1024 * 1024):
                    total += len(chunk); output.write(chunk)
                    elapsed = time.monotonic() - started
                    if elapsed > 180 or elapsed > 20 and total / elapsed < 128 * 1024:
                        raise TimeoutError('Mirror too slow; using another carrier for the same fixed artifact')
            if sha(pending) != SHA256: raise ValueError('Office binary hash mismatch')
            pending.replace(TARGET)
            print(json.dumps({'status': 'PASS', 'bytes': total, 'sha256': SHA256, 'seconds': round(time.monotonic() - started, 3), 'url': url})); return
        except Exception as failed:
            print(json.dumps({'status': 'CARRIER_FAILED', 'url': url, 'error': type(failed).__name__}), flush=True)
    raise SystemExit('No verified Office binary fetched; build must remain blocked')


if __name__ == '__main__': main()
