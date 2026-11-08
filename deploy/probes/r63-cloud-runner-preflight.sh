#!/usr/bin/env bash
set -euo pipefail

printf 'UTC='; date -u +%FT%TZ
printf 'MEMINFO='; awk '/MemAvailable:|SwapTotal:|SwapFree:/ {printf "%s=%s%s ", $1, $2, $3} END {print ""}' /proc/meminfo
printf 'ROOT='; df -B1 --output=size,used,avail,pcent / | tail -n 1
printf 'RUNTIMES='; docker info --format '{{range $name, $runtime := .Runtimes}}{{printf "%s=%s " $name $runtime.Path}}{{end}}'; printf '\n'
secret_dir=/srv/agentplatform/operations/r3-release-8edf7c27cd17-20261025/secrets
[[ -d "$secret_dir" && ! -L "$secret_dir" ]]
[[ $(stat -c '%a' "$secret_dir") == 700 ]]
for secret in runner-private.pem runner-password control-public.pem file-runner-access file-runner-secret \
              control-private.pem runner-public.pem file-worker-access file-worker-secret \
              file-api-access file-api-secret; do
  [[ -f "$secret_dir/$secret" && ! -L "$secret_dir/$secret" ]]
  [[ $(stat -c '%u:%g:%a' "$secret_dir/$secret") == 10001:10001:400 ]]
done
printf 'R5_SECRET_BIND_SOURCES=READY\n'
printf 'CONTAINERS\n'
docker ps --format '{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}'
printf 'R5_APP_INSPECT\n'
for name in agentplatform-r5-app-runner-1 agentplatform-r5-app-worker-1 agentplatform-r5-app-api-1 agentplatform-r5-app-gateway-1; do
  docker inspect --format '{{.Id}}|{{.Name}}|{{.State.Status}}|{{.HostConfig.RestartPolicy.Name}}|{{.Config.Image}}' "$name"
done
printf 'PREFLIGHT=PASS\n'
