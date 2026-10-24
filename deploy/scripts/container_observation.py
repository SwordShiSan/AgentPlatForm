"""Read-only Linux container sampling; optional short-lived helpers may retire mid-snapshot.

Required service loss and Docker/host inspection failures remain errors. Never emit
Docker Env, unfiltered labels, logs, command arguments or substituted zero metrics.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess


class ObservationError(RuntimeError):
    pass


def inspect_container(identity, required=False, run=subprocess.run):
    if not re.fullmatch(r'[a-f0-9]{12,64}', identity):
        raise ObservationError('INVALID_CONTAINER_IDENTITY')
    result = run(['docker', 'inspect', identity], capture_output=True, text=True, timeout=15)
    if result.returncode:
        absent = 'No such object' in result.stderr or 'No such container' in result.stderr
        if absent and not required:
            return None
        raise ObservationError('REQUIRED_CONTAINER_MISSING' if absent else 'CONTAINER_INSPECTION_UNAVAILABLE')
    rows = json.loads(result.stdout)
    if len(rows) != 1 or not rows[0]['Id'].startswith(identity):
        raise ObservationError('CONTAINER_IDENTITY_MISMATCH')
    row = rows[0]
    if row['State'].get('OOMKilled'):
        raise ObservationError('CONTAINER_OOM_RECORDED')
    if required and not row['State']['Running']:
        raise ObservationError('REQUIRED_CONTAINER_STOPPED')
    return row


def process_sample(row, required=False, read=lambda path: path.read_text()):
    state = row['State']
    if not state['Running']:
        if required:
            raise ObservationError('REQUIRED_CONTAINER_STOPPED')
        return {'threads': None, 'availability': 'RETIRED_DURING_SNAPSHOT'}
    if state['Pid'] <= 0:
        raise ObservationError('RUNNING_CONTAINER_PID_UNAVAILABLE')
    try:
        text = read(Path('/proc') / str(state['Pid']) / 'status')
    except FileNotFoundError:
        if required:
            raise ObservationError('REQUIRED_PROCESS_METRICS_UNAVAILABLE') from None
        return {'threads': None, 'availability': 'PROCESS_RETIRED_OR_UNAVAILABLE_DURING_SNAPSHOT'}
    except OSError:
        raise ObservationError('HOST_PROCESS_METRICS_UNAVAILABLE') from None
    count = next((line.split()[1] for line in text.splitlines() if line.startswith('Threads:')), None)
    if count is None or not count.isdigit():
        raise ObservationError('PROCESS_THREAD_METRIC_INVALID')
    return {'threads': int(count), 'availability': 'OBSERVED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container-id', required=True)
    parser.add_argument('--required', action='store_true')
    args = parser.parse_args()
    row = inspect_container(args.container_id, args.required)
    if row is None:
        print(json.dumps({'containerId': args.container_id, 'availability': 'RETIRED_DURING_SNAPSHOT', 'threads': None}))
    else:
        print(json.dumps({'containerId': row['Id'], 'imageId': row['Image'], **process_sample(row, args.required)}))


if __name__ == '__main__':
    main()
