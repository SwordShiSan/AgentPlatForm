#!/usr/bin/env bash
set -euo pipefail
umask 077
mode=${1:-dry-run}
[[ "$mode" == dry-run || "$mode" == apply ]] || exit 2
root=/srv/agentplatform/operations
test -s "$root/latest-inventory"
snapshot=$(cat "$root/latest-inventory")
[[ "$snapshot" == "$root"/inventory-* ]] && test -d "$snapshot"
keep=(swordshisan/ai-agent-app:6.133 swordshisan/ai-agent-app:6.132 swordshisan/ai-agent_big-market-front-app:4.79 swordshisan/ai-agent_big-market-front-app:4.78)
for image in "${keep[@]}"; do docker image inspect "$image" --format '{{.Id}}' >/dev/null; done
obsolete=(swordshisan/ai-agent-app:6.131 swordshisan/ai-agent-app:6.130 swordshisan/ai-agent-app:6.129 swordshisan/ai-agent-app:6.128 swordshisan/ai-agent-app:6.127 swordshisan/ai-agent-app:6.126 swordshisan/ai-agent-app:6.125 swordshisan/ai-agent-app:6.124 swordshisan/ai-agent-app:6.110 swordshisan/ai-agent_big-market-front-app:4.77 swordshisan/ai-agent_big-market-front-app:4.76 swordshisan/ai-agent_big-market-front-app:4.75 swordshisan/ai-agent_big-market-front-app:4.74)
before=$(df -B1 --output=avail / | tail -1)
for image in "${obsolete[@]}"; do
 if ! id=$(docker image inspect "$image" --format '{{.Id}}' 2>/dev/null); then continue; fi
 if [[ -n $(docker ps -aq --filter "ancestor=$id") ]]; then echo "Preserved referenced $image"; continue; fi
 echo "$mode image $image $id"
 if [[ "$mode" == apply ]]; then docker image rm -- "$image"; fi
done
# Dangling means no tag; still guard all stopped/running containers.
while read -r id; do
 [[ -n "$id" ]] || continue
 [[ -z $(docker ps -aq --filter "ancestor=$id") ]] || continue
 echo "$mode dangling $id"
 if [[ "$mode" == apply ]]; then docker image rm -- "$id"; fi
done < <(docker image ls --filter dangling=true --quiet --no-trunc)
if [[ "$mode" == apply ]]; then
 mkdir -p /etc/systemd/journald.conf.d
 if [[ -f /etc/systemd/journald.conf.d/agentplatform-retention.conf ]]; then cp /etc/systemd/journald.conf.d/agentplatform-retention.conf "$snapshot/journal-retention.previous"; fi
 cat > /etc/systemd/journald.conf.d/agentplatform-retention.conf <<'EOF'
[Journal]
SystemMaxUse=256M
RuntimeMaxUse=64M
MaxRetentionSec=7day
EOF
 journalctl --rotate
 journalctl --vacuum-size=256M --vacuum-time=7d
 systemctl restart systemd-journald
 # /etc/logrotate.d/syslog absent was verified; refuse overlapping future rules.
 if grep -rlE --exclude=agentplatform-messages '(^|[[:space:]])/var/log/messages([[:space:]]|$)' /etc/logrotate.d >/dev/null; then
  echo 'messages already has rotation; inspect existing rule before replacing' >&2; exit 1
 fi
 cat > /etc/logrotate.d/agentplatform-messages <<'EOF'
/var/log/messages {
    daily
    maxsize 64M
    rotate 4
    missingok
    notifempty
    compress
    dateext
    create 0600 root root
    sharedscripts
    postrotate
        /usr/bin/systemctl kill -s HUP rsyslog.service
    endscript
}
EOF
 logrotate --debug /etc/logrotate.d/agentplatform-messages > "$snapshot/logrotate-debug.txt" 2>&1
 logrotate /etc/logrotate.d/agentplatform-messages
 # Compression and reopening preserve active diagnostic output; no truncate/rm.
 after=$(df -B1 --output=avail / | tail -1)
 printf 'root_available_before=%s\nroot_available_after=%s\nreclaimed=%s\n' "$before" "$after" "$((after-before))" | tee "$snapshot/cleanup-result.txt"
fi
echo 'Volumes, containers, deployment archives, databases and user files preserved.'
