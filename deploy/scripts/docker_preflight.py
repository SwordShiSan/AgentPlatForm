"""Read-only Docker Desktop preflight. Never starts/restarts Desktop or changes context."""
import argparse
import json
import subprocess
import sys
import time


class DockerUnavailable(RuntimeError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def invoke(*args):
    try:
        return subprocess.run(['docker', *args], capture_output=True, text=True,
                              encoding='utf-8', errors='replace', timeout=12)
    except FileNotFoundError:
        raise DockerUnavailable('CLI_MISSING', 'Docker CLI is not available in this process PATH.') from None
    except subprocess.TimeoutExpired:
        raise DockerUnavailable('ENGINE_TIMEOUT', 'Docker CLI did not respond within 12 seconds; inspect Desktop/backend state.') from None


def require_linux(wait_seconds=0):
    if not 0 <= wait_seconds <= 120:
        raise ValueError('wait_seconds must be between 0 and 120')
    deadline = time.monotonic() + wait_seconds
    while True:
        result = invoke('info', '--format', '{{json .}}')
        if result.returncode == 0:
            try:
                info = json.loads(result.stdout)
            except (json.JSONDecodeError, TypeError):
                raise DockerUnavailable('INVALID_ENGINE_RESPONSE', 'Docker engine returned an invalid info response.') from None
            if info.get('OSType') != 'linux':
                raise DockerUnavailable('WRONG_ENGINE', 'A Linux Docker engine is required; no automatic context switch was made.')
            context = invoke('context', 'show')
            return {'os': 'linux', 'server_version': info.get('ServerVersion'),
                    'memory_bytes': info.get('MemTotal'),
                    'context': context.stdout.strip() if context.returncode == 0 else 'unknown'}
        error = (result.stderr + result.stdout).lower()
        if any(marker in error for marker in ('access is denied', 'permission denied', '拒绝访问', '访问被拒绝')):
            raise DockerUnavailable('ENGINE_ACCESS_DENIED',
                                    'This process cannot access the Docker engine pipe/socket. Retry with authorized host execution; do not restart Desktop.')
        status = invoke('desktop', 'status')
        desktop = (status.stdout + status.stderr).lower()
        # Desktop CLI is optional on Linux. Never infer stopped solely from a missing engine pipe.
        running = status.returncode == 0 and ('running' in desktop or 'starting' in desktop)
        if running and time.monotonic() < deadline:
            time.sleep(min(2, max(0, deadline - time.monotonic())))
            continue
        if running:
            raise DockerUnavailable('BACKEND_NOT_READY',
                                    'Desktop reports running but its engine is not reachable. Inspect the selected context and Desktop backend diagnostics; no restart was attempted.')
        raise DockerUnavailable('ENGINE_UNREACHABLE',
                                'Docker engine is unreachable. Inspect Desktop status/context; this alone does not prove Desktop is stopped.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wait-seconds', type=int, default=0)
    args = parser.parse_args()
    try:
        print(json.dumps(require_linux(args.wait_seconds)))
    except DockerUnavailable as error:
        print(json.dumps({'status': error.code, 'message': str(error)}), file=sys.stderr)
        raise SystemExit(2)


if __name__ == '__main__':
    main()
