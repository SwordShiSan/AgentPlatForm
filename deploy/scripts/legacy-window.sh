#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/srv/agentplatform/operations
mode=${1:-dry-run}
apps=(nginx ai-agent_big-market-front-app ai-agent-app big-market-app ai-agent-xxl-job-admin)
deps=(zookeeper mysql ai-agent-pgvector redis rabbitmq)
case "$mode" in
 dry-run) printf 'Pause: %s\n' "${apps[@]}" "${deps[@]}"; exit 0;;
 pause)
  test -s "$root/latest-inventory"
  snapshot=$(cat "$root/latest-inventory")
  [[ "$snapshot" == "$root"/inventory-* ]] && test -s "$snapshot/legacy-policies.txt"
  test -f "$root/backup-verified"
  test ! -e "$root/legacy-paused"
  # Identity and original running state must still match inventory.
  while IFS='|' read -r name id image policy running; do
   [[ "$name" =~ ^/[a-zA-Z0-9_-]+$ ]]
   [[ $(docker inspect --format '{{.Id}}' "${name#/}") == "$id" ]]
  done < "$snapshot/legacy-policies.txt"
  cp "$snapshot/legacy-policies.txt" "$root/restore-policies.txt"
  touch "$root/legacy-paused"
  docker update --restart=no "${apps[@]}" "${deps[@]}" >/dev/null
  docker stop -t 60 "${apps[@]}"
  docker stop -t 60 "${deps[@]}"
  free -m
  ;;
 restore)
  test -s "$root/restore-policies.txt"
  # New resources must have been stopped first; restore only original identities.
  if docker ps --format '{{.Names}}' | grep -q '^agentplatform-'; then
   echo 'Stop new platform services before restoring legacy' >&2; exit 1
  fi
  for name in "${deps[@]}" "${apps[@]}"; do
   line=$(grep -F "/$name|" "$root/restore-policies.txt")
   IFS='|' read -r original id image policy running <<< "$line"
   [[ $(docker inspect --format '{{.Id}}' "$name") == "$id" ]]
   if [[ "$running" == true ]]; then docker start "$name"; fi
   docker update --restart="$policy" "$name" >/dev/null
   if [[ "$running" == true && "$name" == mysql ]]; then
    ready=false
    for attempt in {1..60}; do
     if docker exec mysql sh -c 'mysqladmin ping -uroot -p"$MYSQL_ROOT_PASSWORD" --silent' >/dev/null 2>&1; then ready=true; break; fi
     sleep 2
    done
    [[ "$ready" == true ]] || { echo 'Legacy MySQL not ready' >&2; exit 1; }
   fi
   if [[ "$running" == true && "$name" == ai-agent-pgvector ]]; then
    ready=false
    for attempt in {1..60}; do
     if docker exec ai-agent-pgvector pg_isready >/dev/null 2>&1; then ready=true; break; fi
     sleep 2
    done
    [[ "$ready" == true ]] || { echo 'Legacy Postgres not ready' >&2; exit 1; }
   fi
  done
  # Only clear marker once original running states and policies match.
  while IFS='|' read -r name id image policy running; do
   [[ $(docker inspect --format '{{.State.Running}}' "${name#/}") == "$running" ]]
   [[ $(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "${name#/}") == "$policy" ]]
  done < "$root/restore-policies.txt"
  rm -- "$root/legacy-paused"
  echo 'Legacy running states and policies restored; HTTP/database health must also pass.'
  ;;
 *) echo 'Usage: legacy-window.sh [dry-run|pause|restore]' >&2; exit 2;;
esac
