"""Pin local runtime to the exact exported candidate, never to a mutable acceptance tag."""
import json
import re


def pin_exported(env, data, required=False):
    path = data / 'artifacts/images.json'
    if not path.exists():
        assert not required, 'Export and verify candidate image identities before acceptance'
        return
    records = json.loads(path.read_text(encoding='utf-8'))
    by_component = {row['component']: row for row in records}
    assert len(by_component) == len(records), 'Duplicate image identity'
    names = {'api': 'R21_API_IMAGE', 'gateway': 'R21_GATEWAY_IMAGE', 'provider-fixture': 'R21_FIXTURE_IMAGE'}
    assert not required or set(names).issubset(by_component), 'Candidate export is incomplete'
    for name, variable in names.items():
        if name not in by_component:
            continue
        row = by_component[name]
        assert row['platform'] == 'linux/amd64'
        assert re.fullmatch(r'sha256:[0-9a-f]{64}', row['localId'])
        assert re.fullmatch(r'sha256:[0-9a-f]{64}', row['configId'])
        env[variable] = row['localId']
