#!/usr/bin/env bash
set -Eeuo pipefail

mode=${1:?mode required}
root=${2:?protected acceptance root required}
runner_id=${3:?exact R5 runner container id required}
worker_id=${4:?exact R5 worker container id required}
api_id=${5:?exact R5 API container id required}
gateway_id=${6:?exact R5 gateway container id required}

[[ "$root" =~ ^/srv/agentplatform/acceptance/r63-signed-[a-z0-9-]+$ ]]
[[ "$runner_id" =~ ^[0-9a-f]{64}$ && "$worker_id" =~ ^[0-9a-f]{64}$ && "$api_id" =~ ^[0-9a-f]{64}$ && "$gateway_id" =~ ^[0-9a-f]{64}$ ]]
deadline_seconds=900
log="$root/r5-watchdog.log"
secret_source=/srv/agentplatform/operations/r5-release-4bc12e3c33de-20261106/backup-r5/r5-secrets
secret_target=/srv/agentplatform/operations/r3-release-8edf7c27cd17-20261025/secrets

restore_secret_mount_sources() {
  mkdir -p "$secret_target"
  chmod 700 "$secret_target"
  python3 - "$secret_source" "$secret_target" <<'PY'
import hashlib, json, os, pathlib, shutil, sys
source=pathlib.Path(sys.argv[1])
target=pathlib.Path(sys.argv[2])
manifest_path=source.parent/'r5-key-hashes.json'
assert source.is_dir() and not source.is_symlink()
assert manifest_path.is_file() and not manifest_path.is_symlink()
expected=json.loads(manifest_path.read_text(encoding='utf-8'))
required=('runner-private.pem','runner-password','control-public.pem',
          'file-runner-access','file-runner-secret','control-private.pem',
          'runner-public.pem','file-worker-access','file-worker-secret',
          'file-api-access','file-api-secret')
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()
for name in required:
    src=source/name
    dst=target/name
    assert src.is_file() and not src.is_symlink(), f'missing protected backup item: {name}'
    wanted=expected.get(name)
    assert wanted and sha(src)==wanted, f'protected backup hash mismatch: {name}'
    if dst.exists():
        assert dst.is_file() and not dst.is_symlink() and sha(dst)==wanted, f'existing bind source differs: {name}'
    else:
        shutil.copyfile(src,dst)
        os.chown(dst,10001,10001)
        os.chmod(dst,0o400)
    assert sha(dst)==wanted, f'restored bind source mismatch: {name}'
    os.chown(dst,10001,10001)
    os.chmod(dst,0o400)
os.chown(target,0,0)
os.chmod(target,0o700)
print('R5_SECRET_BIND_SOURCES=RESTORED_AND_HASH_VERIFIED count='+str(len(required)))
PY
}

check_identity() {
  local name=$1 expected=$2 actual
  actual=$(docker inspect --format '{{.Id}}' "$name")
  [[ "$actual" == "$expected" ]]
}

restore_r5() {
  docker stop -t 8 7d2f6b70138a48228a7dc5bf6807b8ce2aa7c1d2c6f00f4d9b8a7f145b3e901a >/dev/null 2>&1 || true
  restore_secret_mount_sources
  check_identity agentplatform-r5-app-runner-1 "$runner_id"
  check_identity agentplatform-r5-app-worker-1 "$worker_id"
  check_identity agentplatform-r5-app-api-1 "$api_id"
  check_identity agentplatform-r5-app-gateway-1 "$gateway_id"
  docker start "$runner_id" >/dev/null
  docker start "$worker_id" >/dev/null
  docker start "$api_id" >/dev/null
  docker start "$gateway_id" >/dev/null
  local end=$((SECONDS+240))
  while (( SECONDS < end )); do
    if curl --noproxy '*' -fsS --max-time 4 -H 'Host: agentplatform.lailihao.cloud' http://127.0.0.1/api/v1/readiness -o /dev/null; then
      printf '%s R5_READY\n' "$(date -u +%FT%TZ)" >> "$log"
      return 0
    fi
    sleep 3
  done
  printf '%s R5_READINESS_TIMEOUT\n' "$(date -u +%FT%TZ)" >> "$log"
  return 1
}

case "$mode" in
  arm)
    restore_secret_mount_sources
    for pair in \
      "agentplatform-r5-app-runner-1:$runner_id" \
      "agentplatform-r5-app-worker-1:$worker_id" \
      "agentplatform-r5-app-api-1:$api_id" \
      "agentplatform-r5-app-gateway-1:$gateway_id"; do
      name=${pair%%:*}; id=${pair#*:}; check_identity "$name" "$id"
      [[ $(docker inspect --format '{{.State.Status}}' "$id") == running ]]
      [[ $(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$id") == no ]]
    done
    nohup bash "$0" restore-after "$root" "$runner_id" "$worker_id" "$api_id" "$gateway_id" \
      >> "$log" 2>&1 </dev/null &
    echo $! > "$root/r5-watchdog.pid"
    chmod 600 "$root/r5-watchdog.pid"
    printf 'R5_WATCHDOG_ARMED pid=%s deadline_seconds=%s\n' "$(cat "$root/r5-watchdog.pid")" "$deadline_seconds"
    ;;
  restore)
    restore_r5
    touch "$root/r5-maintenance-complete"
    printf 'R5_RESTORED_AND_READY\n'
    ;;
  restore-after)
    end=$((SECONDS+deadline_seconds))
    while (( SECONDS < end )); do
      [[ -e "$root/r5-maintenance-complete" ]] && exit 0
      sleep 5
    done
    printf '%s WATCHDOG_RESTORE_STARTED\n' "$(date -u +%FT%TZ)" >> "$log"
    restore_r5
    ;;
  *) exit 2 ;;
esac
