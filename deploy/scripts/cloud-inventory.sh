#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/srv/agentplatform/operations
mkdir -p "$root"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
snapshot="$root/inventory-$stamp"
mkdir "$snapshot"
containers=(ai-agent-app ai-agent_big-market-front-app big-market-app ai-agent-xxl-job-admin ai-agent-pgvector zookeeper redis nginx mysql rabbitmq)
docker inspect --format '{{.Name}}|{{.Id}}|{{.Config.Image}}|{{.HostConfig.RestartPolicy.Name}}|{{.State.Running}}' "${containers[@]}" > "$snapshot/legacy-policies.txt"
docker inspect --format '{{.Name}} {{json .Mounts}} {{.Image}} {{.LogPath}}' "${containers[@]}" > "$snapshot/mounts.txt"
docker image ls --no-trunc > "$snapshot/images.txt"
docker system df -v > "$snapshot/docker-disk.txt"
df -hT > "$snapshot/filesystems.txt"
free -m > "$snapshot/memory.txt"
findmnt -no SOURCE,FSTYPE,OPTIONS / > "$snapshot/root-mount.txt"
docker stats --no-stream > "$snapshot/container-memory.txt"
du -xhd1 /var/lib/docker /var/log /home /tmp /srv > "$snapshot/directory-sizes.txt"
# Local protected diagnostic records; never printed or copied into Git artifacts.
journalctl --since '-1 hour' --no-pager -n 2000 > "$snapshot/recent-journal.txt"
tail -n 2000 /var/log/messages > "$snapshot/recent-messages.txt"
sha256sum "$snapshot"/*.txt > "$snapshot/SHA256SUMS"
printf '%s\n' "$snapshot" > "$root/latest-inventory"
printf 'Inventory saved: %s\n' "$snapshot"
