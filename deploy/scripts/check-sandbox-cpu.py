"""Host-only CPU1 evidence for an exact R3.2 runsc container; never a guest self-attestation."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time


def integers(path):
    return {parts[0]: int(parts[1]) for line in path.read_text().splitlines() if len(parts := line.split()) == 2}


def snapshot(container, image):
    assert re.fullmatch(r'[0-9a-f]{64}', container)
    assert re.fullmatch(r'sha256:[0-9a-f]{64}', image)
    row = json.loads(subprocess.check_output(['docker', 'inspect', container]))[0]
    host = row['HostConfig']
    assert row['Id'] == container and row['Image'] == image and row['State']['Running']
    assert row['Config'].get('Labels', {}).get('ap.role') == 'sandbox'
    assert host['Runtime'] == 'runsc' and host['NanoCpus'] == 1_000_000_000
    pid = int(row['State']['Pid'])
    assert pid > 0
    groups = {}
    for line in Path('/proc', str(pid), 'cgroup').read_text().splitlines():
        _, controllers, relative = line.split(':', 2)
        for controller in controllers.split(','):
            groups[controller] = relative

    def directory(controller):
        relative = groups[controller]
        assert container in relative and '..' not in Path(relative).parts
        base = Path('/sys/fs/cgroup')
        if controller:
            base /= controller
        path = (base / relative.lstrip('/')).resolve(strict=True)
        assert path.is_relative_to(Path('/sys/fs/cgroup').resolve()) and container in str(path)
        return path

    if '' in groups:
        cpu = directory('')
        quota, period = (cpu / 'cpu.max').read_text().split()
        assert quota != 'max'
        counters = integers(cpu / 'cpu.stat')
        usage = counters['usage_usec'] * 1000
        mode = 'v2'
    else:
        cpu = directory('cpu')
        quota = (cpu / 'cpu.cfs_quota_us').read_text().strip()
        period = (cpu / 'cpu.cfs_period_us').read_text().strip()
        counters = integers(cpu / 'cpu.stat')
        usage = int((directory('cpuacct') / 'cpuacct.usage').read_text())
        mode = 'v1'
    quota, period = int(quota), int(period)
    assert quota > 0 and period > 0 and quota == period, 'CPU1 cgroup quota missing or changed'
    return {'containerId': container, 'imageId': image, 'startedAt': row['State']['StartedAt'],
            'hostPid': pid, 'mode': mode, 'cgroup': str(cpu), 'quotaUs': quota, 'periodUs': period,
            'usageNs': usage, 'nrThrottled': counters['nr_throttled'], 'monotonicNs': time.monotonic_ns()}


def verify(before, after):
    for field in ['containerId', 'imageId', 'startedAt', 'hostPid', 'mode', 'cgroup', 'quotaUs', 'periodUs']:
        assert before[field] == after[field], 'Container/cgroup changed during measurement'
    duration = (after['monotonicNs'] - before['monotonicNs']) / 1e9
    used = (after['usageNs'] - before['usageNs']) / 1e9
    throttled = after['nrThrottled'] - before['nrThrottled']
    assert 5 <= duration <= 180, 'Fixed workload measurement interval missing/out of bounds'
    assert 0.1 < used <= duration + 2 * before['periodUs'] / 1e6 + 0.1, 'Host CPU1 boundary exceeded'
    assert throttled > 0, 'Overloaded fixed workload produced no host throttling evidence'
    return {'status': 'PASS', 'scope': 'Exact host cgroup CPU1 enforcement; sampling is not throughput capacity',
            'before': before, 'after': after, 'sampleCpuSeconds': used, 'sampleWallSeconds': duration,
            'throttledPeriods': throttled}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['begin', 'end'])
    parser.add_argument('--container-id', required=True)
    parser.add_argument('--image-id', required=True)
    parser.add_argument('--before', type=Path, required=True)
    args = parser.parse_args()
    current = snapshot(args.container_id, args.image_id)
    if args.action == 'begin':
        with args.before.open('x', encoding='utf-8') as stream:
            json.dump(current, stream, indent=2)
        args.before.chmod(0o600)
        print(json.dumps({'status': 'RECORDED_NOT_VERIFIED', 'containerId': args.container_id}))
    else:
        before = json.loads(args.before.read_text(encoding='utf-8'))
        print(json.dumps(verify(before, current)))


if __name__ == '__main__':
    main()
