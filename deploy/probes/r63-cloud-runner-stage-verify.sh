#!/usr/bin/env bash
set -euo pipefail
root=${1:?acceptance root required}
archive_sha=${2:?archive hash required}
[[ "$root" =~ ^/srv/agentplatform/acceptance/r63-signed-[a-z0-9-]+$ ]]
[[ "$archive_sha" =~ ^[a-f0-9]{64}$ ]]
[[ $(stat -c '%a' "$root") == 700 ]]
if [[ -f "$root/r63-api-runner.tar" ]]; then
  [[ $(sha256sum "$root/r63-api-runner.tar" | awk '{print $1}') == "$archive_sha" ]]
else
  [[ $(docker image inspect --format '{{.Id}}' sha256:7469b1ebd598dcda62bf1797165c4220614bb7e84ab798dae3363ac46bd1b2b8) == sha256:7469b1ebd598dcda62bf1797165c4220614bb7e84ab798dae3363ac46bd1b2b8 ]]
  [[ $(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' sha256:7469b1ebd598dcda62bf1797165c4220614bb7e84ab798dae3363ac46bd1b2b8) == abc8dd11f11cfd7fd7195cb9a2012d69975b1d77 ]]
fi
for name in r63-cloud-runner-start.sh r63-cloud-r5-watchdog.sh r63-cloud-runner-maintenance.sh r63-cloud-runner-preflight.sh; do
  [[ -f "$root/$name" && ! -L "$root/$name" ]]
  bash -n "$root/$name"
  chmod 500 "$root/$name"
done
[[ -f "$root/r63-cloud-tunnel-check.py" && ! -L "$root/r63-cloud-tunnel-check.py" ]]
python3 - "$root/r63-cloud-tunnel-check.py" <<'PY'
import ast, pathlib, sys
ast.parse(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
PY
chmod 500 "$root/r63-cloud-tunnel-check.py"
for name in r63-cloud-preview-runner 7d2f6b70138a48228a7dc5bf6807b8ce2aa7c1d2c6f00f4d9b8a7f145b3e901a; do
  if docker inspect "$name" >/dev/null 2>&1; then
    printf 'Unexpected pre-existing R63 candidate container: %s\n' "$name" >&2
    exit 1
  fi
done
for name in runner-db-password runner-private.pem control-public.pem s3-access s3-secret; do
  [[ -f "$root/$name" && ! -L "$root/$name" ]]
  chmod 600 "$root/$name"
done
if [[ -f "$root/r63-api-runner.tar" ]]; then
  [[ ! -L "$root/r63-api-runner.tar" ]]
  chmod 600 "$root/r63-api-runner.tar"
fi
for pair in \
  'agentplatform-r5-app-runner-1:761e3c048ee0fb85591e8b63094466b6c85536ac23034ecd6e22d5881d80e10f' \
  'agentplatform-r5-app-worker-1:10b6d7a35691d2eea0c3f3316b9d5d6baf27fc2b814d44b6a79260c54e3d7054' \
  'agentplatform-r5-app-api-1:12723516b93121f83fe23ea331a5c60f6298b6810df4a45330cd4fe63ab103a1' \
  'agentplatform-r5-app-gateway-1:2c13a3b30ea372c2694686ae00077515af62df33954ec2af39f3160ed0488c27'; do
  name=${pair%%:*}; expected=${pair#*:}
  [[ $(docker inspect --format '{{.Id}}' "$name") == "$expected" ]]
  [[ $(docker inspect --format '{{.State.Status}}' "$name") == running ]]
done
printf 'R63_CLOUD_STAGE_VERIFIED root=%s archive_sha256=%s R5=UNCHANGED\n' "$root" "$archive_sha"
