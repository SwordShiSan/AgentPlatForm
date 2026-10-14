"""Preparation only: freeze already verified official audit source candidates."""
import hashlib
import json
import pathlib
import shutil

ROOT = pathlib.Path(__file__).resolve().parents[2]
lock_path = ROOT / 'deploy/foundation.lock.json'
lock = json.loads(lock_path.read_text(encoding='utf-8'))
commits = {'syft': '749cd89ef26db65d5102d879f5dd63a174312b1e',
           'grype': '0d30618ef52e666f1c626f4cc463eb940cb6e498'}
versions = {'syft': '1.54.1', 'grype': '0.120.1'}
for tool, commit in commits.items():
    source = ROOT / '.cache' / (tool + '-' + commit[:7] + '.tar.gz')
    filename = tool + '-source.tar.gz'
    shutil.copyfile(source, ROOT / '.cache/foundation' / filename)
    lock['archives'][filename] = {
        'url': 'https://codeload.github.com/anchore/' + tool + '/tar.gz/' + commit,
        'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}
    patches = {}
    for module_file in ['go.mod', 'go.sum']:
        patch = ROOT / 'deploy/security' / (tool + '.' + module_file)
        shutil.copyfile(ROOT / '.cache/r04-audit-mods' / tool / module_file, patch)
        patches[patch.relative_to(ROOT).as_posix()] = hashlib.sha256(patch.read_bytes()).hexdigest()
    lock['sources'][tool] = {'commit': commit, 'version': versions[tool],
                            'patchFiles': patches, 'status': 'CANDIDATE_NOT_ACCEPTED',
                            'license': 'Apache-2.0', 'compiler': 'Go1.27.2'}
lock_path.write_text(json.dumps(lock, indent=2, sort_keys=True) + '\n', encoding='utf-8')
print('Frozen Syft/Grype source candidates; accepted tool lock unchanged')
