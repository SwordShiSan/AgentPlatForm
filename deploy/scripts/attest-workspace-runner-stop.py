"""Host-only formatter for the existing signed runner-stop proof. Never stops a container or changes a DB."""
import argparse
import datetime
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import uuid


def canonical_instant(value):
    match = re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z', value)
    if not match:
        raise ValueError('UTC nanosecond identity required')
    datetime.datetime.strptime(match[1], '%Y-%m-%dT%H:%M:%S')
    fraction = (match[2] or '').ljust(9, '0')
    digits = 0 if fraction == '000000000' else 3 if fraction[3:] == '000000' else 6 if fraction[6:] == '000' else 9
    return match[1] + ('.' + fraction[:digits] if digits else '') + 'Z'


def make_proof(metadata, inspected, key, observed_at):
    if set(metadata) != {'runnerInstanceId', 'containerId', 'imageId', 'startedAt'} or len(key) != 32:
        raise ValueError('Exact runner metadata and independent32-byte host key required')
    if str(uuid.UUID(metadata['runnerInstanceId'])) != metadata['runnerInstanceId']:
        raise ValueError('Canonical runner identity required')
    if not re.fullmatch(r'[0-9a-f]{64}', metadata['containerId']) or not re.fullmatch(r'sha256:[0-9a-f]{64}', metadata['imageId']):
        raise ValueError('Pinned container/config identity required')
    state = inspected['State']
    if inspected['Id'] != metadata['containerId'] or inspected['Image'] != metadata['imageId']:
        raise ValueError('Runner identity changed')
    if inspected['Config'].get('Labels', {}).get('ap.role') != 'runner':
        raise ValueError('This proof is for a runner, never sandbox termination')
    if state['Running'] or state.get('Paused') or state['Pid'] != 0:
        raise ValueError('Runner is not confirmed stopped')
    started = canonical_instant(metadata['startedAt'])
    if canonical_instant(state['StartedAt']) != started:
        raise ValueError('Runner start changed')
    stopped = canonical_instant(state['FinishedAt'])
    observed = canonical_instant(observed_at)
    # Keep the original nanoseconds; datetime.fromisoformat truncates to microseconds.
    def nanos(value):
        base = datetime.datetime.strptime(value[:19], '%Y-%m-%dT%H:%M:%S').replace(tzinfo=datetime.timezone.utc)
        delta = base - datetime.datetime(1970, 1, 1, tzinfo=datetime.timezone.utc)
        fraction = value[20:-1] if len(value) > 20 else ''
        return (delta.days * 86400 + delta.seconds) * 1_000_000_000 + int(fraction.ljust(9, '0'))
    if nanos(stopped) <= nanos(started):
        raise ValueError('No finished timestamp after this runner start')
    if nanos(stopped) > nanos(observed):
        raise ValueError('Observation predates stop')
    payload = json.dumps({'scope': 'WORKSPACE_ENVIRONMENT_V2', 'runnerInstanceId': metadata['runnerInstanceId'],
                         'containerId': metadata['containerId'], 'imageId': metadata['imageId'],
                         'startedAt': started, 'stoppedAt': stopped, 'observedAt': observed}, separators=(',', ':'))
    return {'payload': payload, 'signature': hmac.new(key, payload.encode('utf-8'), hashlib.sha256).hexdigest()}


def private_file(path, maximum):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > maximum or stat.S_IMODE(info.st_mode) & 0o077:
        raise ValueError('Protected regular file required')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        current = os.fstat(stream.fileno())
        if (current.st_ino, current.st_dev) != (info.st_ino, info.st_dev):
            raise ValueError('Input identity changed')
        data = stream.read(maximum + 1)
        if len(data) > maximum:
            raise ValueError('Input grew beyond limit')
        return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata-file', type=Path, required=True)
    parser.add_argument('--host-key-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    metadata = json.loads(private_file(args.metadata_file, 4096))
    if not re.fullmatch(r'[0-9a-f]{64}', metadata.get('containerId', '')):
        raise ValueError('Exact container identity required before inspect')
    row = json.loads(subprocess.check_output(['docker', 'inspect', metadata['containerId']], timeout=15))[0]
    key = bytearray(private_file(args.host_key_file, 32))
    try:
        now = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')
        proof = make_proof(metadata, row, key, now)
    finally:
        key[:] = b'\0' * len(key)
    raw = (json.dumps(proof, separators=(',', ':')) + '\n').encode('utf-8')
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    print(json.dumps({'runnerInstanceId': metadata['runnerInstanceId'], 'proofSha256': hashlib.sha256(raw).hexdigest()}))


if __name__ == '__main__':
    main()
