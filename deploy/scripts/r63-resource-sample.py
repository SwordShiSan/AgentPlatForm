"""Bounded read-only resource sampling for the isolated R6.3 local profile."""
from pathlib import Path
import argparse
import json
import re
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache' / 'r63-local'
PROJECT = 'agentplatform-r63-local'
EXPECTED_SERVICES = {'postgres', 'rabbitmq', 'seaweedfs', 'mcp-proxy', 'mcp-fixture', 'api', 'worker', 'fixture', 'gateway'}
EXTERNAL_RESERVE_MIB = 192  # shared runtime and helper reserve; local sandbox remains cloud-only


def call(args, timeout=20):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('Read-only R63 resource probe failed: ' + ' '.join(args[:3]))
    return result.stdout


def bytes_quantity(value):
    value = value.strip()
    units = {'B': 1, 'kB': 1000, 'MB': 1000**2, 'GB': 1000**3,
             'KiB': 1024, 'MiB': 1024**2, 'GiB': 1024**3}
    amount, unit = value.split(' ', 1)
    return int(float(amount) * units[unit])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds', type=int, default=180)
    parser.add_argument('--interval', type=int, default=5)
    parser.add_argument('--run-id', default='01')
    args = parser.parse_args()
    if args.seconds < 180 or args.seconds > 300 or args.interval < 5 or not re.fullmatch(r'[a-zA-Z0-9_-]{1,24}', args.run_id):
        raise ValueError('Use a bounded 180-300 second observation and >=5 second sampling interval')

    manifest = json.loads((DATA / 'fixture' / 'manifest.json').read_text(encoding='utf-8'))
    database = manifest['database']
    output = DATA / f'resource-observation-{database}-{args.run_id}.jsonl'
    summary = DATA / f'resource-observation-{database}-{args.run_id}.json'
    if output.exists() or summary.exists():
        raise RuntimeError('Resource observation is immutable once written')

    image_ids = {}
    violations = 0
    samples = 0
    started = time.monotonic()
    initial_source = call(['docker', 'ps', '-q', '--no-trunc', '--filter', 'label=com.docker.compose.project=' + PROJECT]).split()
    if not initial_source:
        raise RuntimeError('R63 isolated services are not running')

    with output.open('x', encoding='utf-8', newline='\n') as log:
        while time.monotonic() - started < args.seconds:
            ids = call(['docker', 'ps', '-q', '--no-trunc', '--filter', 'label=com.docker.compose.project=' + PROJECT]).split()
            inspected = json.loads(call(['docker', 'inspect', *ids])) if ids else []
            services = {}
            rows = []
            for row in inspected:
                labels = row['Config'].get('Labels') or {}
                if labels.get('com.docker.compose.project') != PROJECT:
                    continue
                service = labels.get('com.docker.compose.service')
                if service in services:
                    raise RuntimeError('Duplicate fixture service container observed')
                services[service] = row
                identity = {'id': row['Id'], 'image': row['Image']}
                previous = image_ids.setdefault(service, identity)
                if previous != identity:
                    violations += 1
                if row['RestartCount'] != 0 or row['State']['OOMKilled']:
                    violations += 1
                rows.append({'service': service, 'id': row['Id'], 'image': row['Image'],
                             'memoryCapBytes': row['HostConfig']['Memory'],
                             'swapCapBytes': row['HostConfig']['MemorySwap'],
                             'restartCount': row['RestartCount'], 'oomKilled': row['State']['OOMKilled'],
                             'health': (row.get('State', {}).get('Health') or {}).get('Status')})

            if set(services) != EXPECTED_SERVICES:
                violations += 1
            declared_cap = sum(item['HostConfig']['Memory'] for item in services.values())
            no_swap_expansion = all(row['HostConfig']['Memory'] == row['HostConfig']['MemorySwap'] for row in services.values())
            total_with_reserve = declared_cap + EXTERNAL_RESERVE_MIB * 1024**2
            host_meminfo = call(['docker', 'exec', 'agentplatform-r63-local-postgres-1', 'sh', '-c',
                                 "awk '/^(MemAvailable|SwapTotal|SwapFree):/{print $1,$2}' /proc/meminfo"])
            host_values = {parts[0].rstrip(':'): int(parts[1]) for line in host_meminfo.splitlines()
                           if len(parts := line.split()) == 2}
            host_available_mib = round(host_values.get('MemAvailable', 0) / 1024, 1)
            host_swap_used_mib = round((host_values.get('SwapTotal', 0) - host_values.get('SwapFree', 0)) / 1024, 1)

            stats = call(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids]).splitlines()
            by_id = {row['ID']: row for row in (json.loads(line) for line in stats if line.strip())}
            for row in rows:
                stat = by_id.get(row['id'][:12])
                if stat is None:
                    # Docker Desktop may format ID as the full short container name.
                    stat = next((value for value in by_id.values() if value.get('Name') == services[row['service']]['Name'].lstrip('/')), None)
                row['memoryUsage'] = stat.get('MemUsage') if stat else None
                row['memoryPercent'] = stat.get('MemPerc') if stat else None
                row['cpuPercent'] = stat.get('CPUPerc') if stat else None
                row['pids'] = stat.get('PIDs') if stat else None
                if stat is None:
                    violations += 1

            readiness = False
            try:
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(
                        'http://127.0.0.1:' + str(manifest['gatewayPort']) + '/api/v1/readiness', timeout=5) as response:
                    readiness = response.status == 200 and json.loads(response.read()).get('status') == 'READY'
            except Exception:
                readiness = False

            local_free = shutil.disk_usage(ROOT).free
            sample_pass = (set(services) == EXPECTED_SERVICES and no_swap_expansion and
                           total_with_reserve <= 2887 * 1024**2 and host_available_mib >= 768 and
                           local_free >= 5 * 1024**3 and readiness and
                           all(row['restartCount'] == 0 and not row['oomKilled'] for row in rows))
            if not sample_pass:
                violations += 1
            log.write(json.dumps({'time': time.time(), 'database': database, 'readiness': readiness,
                                  'rootFreeBytes': local_free, 'declaredCapBytes': declared_cap,
                                  'hostMemAvailableMiB': host_available_mib, 'hostSwapUsedMiB': host_swap_used_mib,
                                  'sharedRuntimeHelperReserveMiB': EXTERNAL_RESERVE_MIB,
                                  'totalWithReserveMiB': round(total_with_reserve / 1024**2, 2),
                                  'samplePass': sample_pass, 'containers': sorted(rows, key=lambda item: item['service'])}) + '\n')
            log.flush()
            samples += 1
            time.sleep(args.interval)

    # Docker Desktop's inspect/stats/API calls take several seconds per sample.
    # Require coverage at <=10s cadence instead of demanding the requested
    # sleep interval as the wall-clock sampling cadence.
    minimum_samples = (args.seconds + 9) // 10
    result = {'status': 'PASS' if violations == 0 and samples >= minimum_samples else 'FAIL', 'database': database,
              'seconds': round(time.monotonic() - started, 1), 'samples': samples,
              'minimumSamples': minimum_samples, 'maximumAcceptedSampleGapSeconds': 10,
              'violations': violations, 'sampledNotAbsolutePeaks': True, 'supplierCalls': 0,
              'sandboxStartedLocally': False, 'appsAndDependenciesOnly': True,
              'logFile': output.name}
    summary.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print('R63_RESOURCE_OBSERVATION_PASS' if result['status'] == 'PASS' else 'R63_RESOURCE_OBSERVATION_FAIL')
    if result['status'] != 'PASS':
        raise SystemExit(2)


if __name__ == '__main__':
    main()
