"""JSON file hashes for quiescent workspaces; paths never escape the verified root."""
import argparse
import hashlib
import json
import pathlib


def collect(root):
    rows = []
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise SystemExit('Symlink requires separate reviewed backup handling')
        relative = path.relative_to(root).as_posix()
        if path.is_dir():
            rows.append({'path': relative, 'kind': 'directory'})
        elif path.is_file():
            digest = hashlib.sha256()
            with path.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            rows.append({'path': relative, 'kind': 'file', 'bytes': path.stat().st_size, 'sha256': digest.hexdigest()})
        else:
            raise SystemExit('Unsupported special file in workspace')
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['write', 'verify'])
    parser.add_argument('root', type=pathlib.Path)
    parser.add_argument('manifest', type=pathlib.Path)
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    rows = collect(root)
    if args.mode == 'write':
        args.manifest.write_text(json.dumps({'schemaVersion': 1, 'entries': rows}, indent=2) + '\n', encoding='utf-8')
    elif json.loads(args.manifest.read_text())['entries'] != rows:
        raise SystemExit('Restored workspace mismatch')
    print('Workspace manifest:', len(rows), 'entries verified')


if __name__ == '__main__':
    main()
