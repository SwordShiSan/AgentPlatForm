#!/usr/bin/env bash
set -Eeuo pipefail
root=${1:?protected acceptance root required}
app_image=${2:?fixed application image config ID required}
renderer_image=${3:?fixed renderer config ID required}
bundle=${4:?fixed parser bundle required}
policy=${5:?fixed runtime policy hash required}
database=${6:?isolated R6.3 database required}
archive_sha=${7:?fixed archive hash required}
watchdog="$root/r63-cloud-r5-watchdog.sh"
runner_id=761e3c048ee0fb85591e8b63094466b6c85536ac23034ecd6e22d5881d80e10f
worker_id=10b6d7a35691d2eea0c3f3316b9d5d6baf27fc2b814d44b6a79260c54e3d7054
api_id=12723516b93121f83fe23ea331a5c60f6298b6810df4a45330cd4fe63ab103a1
gateway_id=2c13a3b30ea372c2694686ae00077515af62df33954ec2af39f3160ed0488c27

[[ "$root" =~ ^/srv/agentplatform/acceptance/r63-signed-[a-z0-9-]+$ ]]
candidate_name=7d2f6b70138a48228a7dc5bf6807b8ce2aa7c1d2c6f00f4d9b8a7f145b3e901a
[[ $(docker ps -q --filter "name=^/${candidate_name}$") == '' ]]
[[ $(docker ps -aq --filter 'name=^/r63-cloud-preview-runner$') == '' ]]
bash "$watchdog" arm "$root" "$runner_id" "$worker_id" "$api_id" "$gateway_id"
docker stop -t 12 "$gateway_id" >/dev/null
docker stop -t 12 "$api_id" >/dev/null
docker stop -t 12 "$worker_id" >/dev/null
docker stop -t 12 "$runner_id" >/dev/null
for id in "$gateway_id" "$api_id" "$worker_id" "$runner_id"; do
  [[ $(docker inspect --format '{{.State.Status}}' "$id") == exited ]]
done
bash "$root/r63-cloud-runner-start.sh" "$root" "$app_image" "$renderer_image" "$bundle" "$policy" "$database" "$archive_sha"
printf 'R63_SHORT_MAINTENANCE_ACTIVE watchdog=%s candidate_runner=%s\n' "$(cat "$root/r5-watchdog.pid")" "$(docker inspect --format '{{.Id}}' r63-cloud-preview-runner)"
