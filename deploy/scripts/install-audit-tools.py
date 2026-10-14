"""Install already locked audit tools, verifying official archive and binary hashes."""
import hashlib
import io
import json
import os
import pathlib
import tarfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
destination = ROOT / '.tools' / 'audit'
destination.mkdir(parents=True, exist_ok=True)
lock = json.loads((ROOT / 'deploy' / 'audit-tools.lock.json').read_text())
platform_suffix = 'windows_amd64.zip' if os.name == 'nt' else 'linux_amd64.tar.gz'
for name in ['syft', 'grype']:
    asset = next(a for a in lock['tools'][name]['archives'] if a['name'].endswith(platform_suffix))
    archive_path = destination / asset['name']
    if archive_path.exists():
        data = archive_path.read_bytes()
    else:
        with urllib.request.urlopen(asset['url'], timeout=120) as response:
            data = response.read()
    if hashlib.sha256(data).hexdigest() != asset['sha256']:
        raise SystemExit(f'Archive hash mismatch: {name}')
    archive_path.write_bytes(data)
    binary_name = name + ('.exe' if os.name == 'nt' else '')
    if os.name == 'nt':
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            binary = archive.read(binary_name)
    else:
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
            member = archive.getmember(binary_name)
            if not member.isfile():
                raise SystemExit('Expected regular executable')
            binary = archive.extractfile(member).read()
    target = destination / binary_name
    binary_hash_key = 'windowsBinarySha256' if os.name == 'nt' else 'linuxBinarySha256'
    if binary_hash_key in lock['tools'][name] and hashlib.sha256(binary).hexdigest() != lock['tools'][name][binary_hash_key]:
        raise SystemExit(f'Executable hash mismatch: {name}')
    target.write_bytes(binary)
    target.chmod(0o755)
    print(f'{name}: {lock["tools"][name]["version"]} verified')
