"""Test and build exact audit source candidates as the non-root Linux CI runner."""
import argparse
import hashlib
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--tool', choices=['syft', 'grype'], required=True)
parser.add_argument('--go', type=pathlib.Path, required=True)
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
if os.environ.get('GITHUB_ACTIONS') != 'true' or os.geteuid() == 0:
    raise SystemExit('Disposable non-root Linux CI acceptance only')
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
lock = json.loads((ROOT / 'deploy/foundation.lock.json').read_text())
source = lock['sources'][args.tool]
archive = ROOT / '.cache/foundation' / (args.tool + '-source.tar.gz')
if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['archives'][archive.name]['sha256']:
    raise SystemExit('Source archive changed')
work = ROOT / '.cache' / ('audit-source-' + args.tool)
work.mkdir(exist_ok=False)
with tarfile.open(archive) as bundle:
    prefix = args.tool + '-' + source['commit']
    spec = importlib.util.spec_from_file_location('fixtures', ROOT / 'deploy/scripts/audit-fixture-cache.py')
    fixtures = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixtures)
    fixtures.extract_source(bundle, work, prefix, source['fixtureSymlinks'])
directory = work / prefix
environment = dict(os.environ, PATH=str(args.go.resolve().parent) + os.pathsep + os.environ['PATH'],
                   GOTOOLCHAIN='local', CGO_ENABLED='0', GOMAXPROCS='2')
tools = {}
for name, command in [('docker', ['version']), ('zip', ['-v']), ('zstd', ['--version']), ('git', ['--version'])]:
    path = shutil.which(name)
    if not path:
        raise SystemExit('Missing CI test dependency: ' + name)
    tools[name] = {'binarySha256': hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest(),
                   'version': subprocess.check_output([path] + command, text=True)}
go = str(args.go.resolve())
version = subprocess.check_output([go, 'version'], text=True)
if 'go1.27.2 linux/amd64' not in version:
    raise SystemExit('Compiler patch/platform mismatch')
tools['go'] = {'version': version, 'binarySha256': hashlib.sha256(pathlib.Path(go).read_bytes()).hexdigest()}
(output / 'test-tools.json').write_text(json.dumps(tools, indent=2) + '\n')
subprocess.run(['git', 'init', '--quiet'], cwd=directory, check=True)
if args.tool == 'syft':
    fixtures.restore(directory, output / 'fixture-identities.json')
for name in ['go.mod', 'go.sum']:
    patch = ROOT / 'deploy/security' / (args.tool + '.' + name)
    if hashlib.sha256(patch.read_bytes()).hexdigest() != source['patchFiles'][patch.relative_to(ROOT).as_posix()]:
        raise SystemExit('Audit module patch changed')
    shutil.copyfile(patch, directory / name)
commands = [[go, 'mod', 'download'], [go, 'mod', 'verify'],
            [go, 'test', '-mod=readonly', '-p', '2', '-timeout', '20m',
             '-tags=containers_image_openpgp,exclude_graphdriver_btrfs', './' + args.tool + '/...', './internal/...']]
for index, command in enumerate(commands):
    with (output / ('test-' + str(index) + '.log')).open('w') as log:
        result = subprocess.run(command, cwd=directory, env=environment, stdout=log, stderr=subprocess.STDOUT)
    print('Audit source test step', index, 'exit', result.returncode, flush=True)
    if result.returncode:
        subprocess.run(['tail', '-n', '80', str(output / ('test-' + str(index) + '.log'))], check=True)
        raise SystemExit(result.returncode)
flags = '-w -s -X main.version=' + source['version'] + '+platform-r04 -X main.gitCommit=' + source['commit'] + ' -X main.gitDescription=platform-security-rebuild'
(output / 'imports-linux.txt').write_text(subprocess.check_output(
    [go, 'list', '-mod=readonly', '-deps', '-tags=containers_image_openpgp,exclude_graphdriver_btrfs',
     '-f={{.ImportPath}}', './cmd/' + args.tool], cwd=directory, env=environment, text=True))
subprocess.run([go, 'build', '-mod=readonly', '-trimpath', '-tags=containers_image_openpgp,exclude_graphdriver_btrfs',
                '-ldflags=' + flags, '-o', str(output / args.tool), './cmd/' + args.tool], cwd=directory, env=environment, check=True)
(output / 'version.txt').write_text(subprocess.check_output([str(output / args.tool), 'version'], text=True))
(output / 'build-info.txt').write_text(subprocess.check_output([go, 'version', '-m', str(output / args.tool)], text=True))
windows_environment = dict(environment, GOOS='windows', GOARCH='amd64')
(output / 'imports-windows.txt').write_text(subprocess.check_output(
    [go, 'list', '-mod=readonly', '-deps', '-f={{.ImportPath}}', './cmd/' + args.tool],
    cwd=directory, env=windows_environment, text=True))
subprocess.run([go, 'build', '-mod=readonly', '-trimpath', '-ldflags=' + flags,
                '-o', str(output / (args.tool + '.exe')), './cmd/' + args.tool],
               cwd=directory, env=windows_environment, check=True)
(output / 'windows-build-info.txt').write_text(subprocess.check_output([go, 'version', '-m', str(output / (args.tool + '.exe'))], text=True))
for name in ['go.mod', 'go.sum', 'LICENSE']:
    shutil.copyfile(directory / name, output / name)
records = {name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in [args.tool, args.tool + '.exe', 'go.mod', 'go.sum', 'LICENSE', 'imports-linux.txt', 'imports-windows.txt']}
(output / 'binary-identities.json').write_text(json.dumps({'status': 'CANDIDATE_NOT_ACCEPTED', 'officialSourceCommit': source['commit'],
                                                         'note': 'Synthetic Git root only supports test discovery; provenance uses archive/patch hashes.',
                                                         'files': records}, indent=2) + '\n')
