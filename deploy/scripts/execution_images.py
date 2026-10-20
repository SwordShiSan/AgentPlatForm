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
    prefix = env.get('EXECUTION_IMAGE_PREFIX', 'R21')
    assert prefix in {'R21', 'R2C', 'R22'}, 'Unsupported acceptance image namespace'
    names = {'api': prefix + '_API_IMAGE', 'gateway': prefix + '_GATEWAY_IMAGE', 'provider-fixture': prefix + '_FIXTURE_IMAGE'}
    assert not required or set(names).issubset(by_component), 'Candidate export is incomplete'
    for name, variable in names.items():
        if name not in by_component:
            continue
        row = by_component[name]
        assert row['platform'] == 'linux/amd64'
        assert re.fullmatch(r'sha256:[0-9a-f]{64}', row['localId'])
        assert re.fullmatch(r'sha256:[0-9a-f]{64}', row['configId'])
        env[variable] = row['localId']
