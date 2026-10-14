"""Preparation only: freeze Debian cross-compiler inputs from a saved official index.

Builds consume the resulting fixed archives; they never run a floating apt install.
Host library dependencies are checked by dpkg in the immutable builder image.
"""
import hashlib
import json
import lzma
import pathlib
import re
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
index_path = ROOT / '.cache/trixie-main-Packages.xz'
packages = {}
for paragraph in lzma.decompress(index_path.read_bytes()).decode().split('\n\n'):
    fields = dict(line.split(': ', 1) for line in paragraph.splitlines() if ': ' in line and not line.startswith(' '))
    if fields.get('Package') and fields.get('SHA256'):
        packages[fields['Package']] = fields
queue = ['gcc-aarch64-linux-gnu', 'g++-aarch64-linux-gnu', 'binutils-aarch64-linux-gnu',
         'clang', 'libbpf-dev']
# Native GCC/libc/binutils are supplied by the locked Go builder. Freeze the
# additional LLVM/BPF dependency closure required by upstream release packaging.
native_build_dependencies = {
    'clang', 'clang-19', 'libclang-cpp19', 'libllvm19', 'libobjc-14-dev',
    'libobjc4', 'libclang-common-19-dev', 'llvm-19-linker-tools', 'libclang1-19',
    'libedit2', 'libffi8', 'libxml2', 'libz3-4', 'libbpf-dev', 'libbpf1',
    'libelf-dev', 'libelf1t64', 'zlib1g-dev', 'zlib1g', 'libzstd-dev', 'libzstd1',
    'libbsd0', 'libmd0', 'libgc1',
}
selected = {}
while queue:
    name = queue.pop()
    if name in selected:
        continue
    item = packages[name]
    selected[name] = item
    for dependency in item.get('Depends', '').split(','):
        candidate = re.match(r'\s*([a-z0-9+.-]+)', dependency)
        if not candidate:
            continue
        dependency_name = candidate.group(1)
        if ('aarch64-linux-gnu' in dependency_name or dependency_name.endswith('-arm64-cross')
                or dependency_name in ['gcc-14-cross-base', 'binutils-common']
                or dependency_name in native_build_dependencies):
            queue.append(dependency_name)
lock_path = ROOT / 'deploy/foundation.lock.json'
lock = json.loads(lock_path.read_text(encoding='utf-8'))
for name, item in sorted(selected.items()):
    filename = 'cross-' + name + '.deb'
    url = 'https://deb.debian.org/debian/' + item['Filename']
    cached = ROOT / '.cache/foundation' / filename
    data = cached.read_bytes() if cached.exists() else urllib.request.urlopen(url, timeout=120).read()
    if hashlib.sha256(data).hexdigest() != item['SHA256']:
        raise SystemExit('Official index hash mismatch: ' + name)
    (ROOT / '.cache/foundation' / filename).write_bytes(data)
    lock['archives'][filename] = {'url': url, 'sha256': item['SHA256'], 'package': name, 'version': item['Version']}
lock['sources']['gvisor']['crossCompilerIndexSha256'] = hashlib.sha256(index_path.read_bytes()).hexdigest()
lock_path.write_text(json.dumps(lock, indent=2, sort_keys=True) + '\n', encoding='utf-8')
(ROOT / 'evidence/r0.4/cross-compiler-packages.json').write_text(json.dumps(selected, indent=2) + '\n', encoding='utf-8')
print('Frozen cross-compiler packages:', len(selected))
