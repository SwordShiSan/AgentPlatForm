"""Run a bounded renderer-format experiment. Production admission/signatures are tested separately."""
from pathlib import Path
import argparse, hashlib, json, subprocess, time, uuid

ROOT = Path(__file__).resolve().parents[2]


def run(command, required=True):
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if required and result.returncode: raise RuntimeError(f'Command failed ({result.returncode}): {result.stderr[-1500:]}')
    return result


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8').splitlines() if '=' in line)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='agentplatform-preview:r53-candidate')
    parser.add_argument('--context', default='desktop-linux')
    parser.add_argument('--runtime', choices=['runsc', 'runc'], default='runsc')
    parser.add_argument('--functional-only', action='store_true', help='Required for a Desktop runc parser experiment; never reports sandbox acceptance')
    parser.add_argument('--fixtures', type=Path, default=ROOT / '.cache/r53-local/preview-fixtures')
    parser.add_argument('--output', type=Path, default=ROOT / '.cache/r53-local/renderer-smoke')
    args = parser.parse_args()
    if args.runtime == 'runc' and not args.functional_only: raise SystemExit('Desktop parsing experiment requires --functional-only; no silent runtime fallback')
    args.output.mkdir(parents=True, exist_ok=True)
    docker = ['docker', '--context', args.context]
    samples = [('sample.docx', 'AUTO', True), ('sample.xlsx', 'AUTO', True), ('sample.pptx', 'AUTO', True),
               ('sample.pdf', 'AUTO', True), ('sample.png', 'AUTO', True), ('sample.webp', 'AUTO', True), ('sample.gif', 'AUTO', True),
               ('empty.txt', 'AUTO', True), ('sample.txt', 'AUTO', True), ('sample.html', 'HTML_STATIC', True), ('corrupt.docx', 'AUTO', False)]
    records = []
    for filename, requested, success in samples:
        source = args.fixtures.resolve() / filename
        output = args.output / (filename.replace('.', '-') + '-' + uuid.uuid4().hex[:8]); output.mkdir()
        name = 'ap-preview-smoke-' + uuid.uuid4().hex
        created = False; observed = None; started = time.monotonic()
        try:
            digest = hashlib.sha256(source.read_bytes()).hexdigest(); extension = source.suffix.removeprefix('.')
            identifier = run(docker + ['create', '--name', name, '--runtime', args.runtime, '--user', '10001:10001', '--network', 'none',
                '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '512m', '--memory-swap', '512m', '--cpus', '1', '--pids-limit', '128',
                '--tmpfs', '/tmp:rw,noexec,nosuid,size=256m,uid=10001,gid=10001,mode=700', '--mount', f'type=bind,source={source},target=/input/source.{extension},readonly',
                '--label', 'ap.role=preview-functional-smoke', args.image, extension, requested, digest]).stdout.strip()
            created = True;run(docker + ['start', identifier])
            until = time.monotonic() + 120
            while time.monotonic() < until:
                observed = json.loads(run(docker + ['inspect', identifier]).stdout)[0]
                pending_manifest = run(docker + ['exec', identifier, '/usr/bin/cat', '/tmp/preview/manifest.properties'], False)
                if pending_manifest.returncode == 0:
                    (output / 'manifest.properties').write_text(pending_manifest.stdout, encoding='utf-8'); break
                if not observed['State']['Running']: raise RuntimeError('Renderer exited before manifest: ' + json.dumps(observed['State']))
                time.sleep(.25)
            else: raise TimeoutError('Renderer did not produce a manifest within120seconds')
            manifest = properties(output / 'manifest.properties')
            if manifest.get('state') != ('READY' if success else 'FAILED'): raise RuntimeError('Unexpected renderer result: ' + json.dumps(manifest))
            if manifest['state'] == 'READY' and manifest.get('sourceReferenced') != 'true':
                filename_out = manifest.get('file')
                if filename_out not in ['content.pdf', 'content.png', 'content.html']: raise RuntimeError('Unexpected result path')
                target = output / filename_out
                with target.open('wb') as stream:
                    transfer = subprocess.run(docker + ['exec', identifier, '/usr/bin/cat', '/tmp/preview/' + filename_out], stdout=stream, stderr=subprocess.PIPE)
                if transfer.returncode: raise RuntimeError('Fixed preview output read failed')
                if target.stat().st_size != int(manifest['bytes']) or hashlib.sha256(target.read_bytes()).hexdigest() != manifest['sha256']: raise RuntimeError('Preview content identity mismatch')
                if filename == 'sample.docx':
                    # Validate the native-PDF source-reference path using an actually converted document next.
                    (args.fixtures / 'sample.pdf').write_bytes(target.read_bytes())
            run(docker + ['stop', '--time', '5', identifier]); after = json.loads(run(docker + ['inspect', identifier]).stdout)[0]
            if after['State']['Running'] or after['State']['Pid'] != 0 or after['State']['OOMKilled']: raise RuntimeError('Renderer stop/resource failure')
            records.append({'file': filename, 'status': 'PASS', 'manifest': manifest, 'seconds': round(time.monotonic() - started, 3),
                'containerId': after['Id'], 'imageId': after['Image'], 'runtime': after['HostConfig']['Runtime'], 'state': after['State']})
        except BaseException as error:
            (output / 'failure.json').write_text(json.dumps({'type': type(error).__name__, 'error': str(error)}), encoding='utf-8')
            if created:
                (output / 'container.private.log').write_text(run(docker + ['logs', name], False).stdout + run(docker + ['logs', name], False).stderr, encoding='utf-8')
                (output / 'container.private.json').write_text(run(docker + ['inspect', name], False).stdout, encoding='utf-8')
            raise
        finally:
            if created:
                run(docker + ['stop', '--time', '5', name], False)
                state = run(docker + ['inspect', name], False)
                if state.returncode == 0 and not json.loads(state.stdout)[0]['State']['Running']: run(docker + ['rm', name])
            result = {'status': 'PASS' if len(records) == len(samples) else 'INCOMPLETE', 'scope': 'FUNCTION_ONLY' if args.functional_only else 'RENDERER_PHYSICAL_ONLY',
                'durableSignedPipelineVerified': False, 'runtime': args.runtime, 'samples': records}
            (args.output / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': 'PASS', 'samples': len(records), 'runtime': args.runtime, 'scope': result['scope']}))


if __name__ == '__main__': main()
