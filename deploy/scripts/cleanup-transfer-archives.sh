#!/usr/bin/env bash
set -euo pipefail
root=/srv/agentplatform
mode=${1:-dry-run}
[[ "$mode" == dry-run || "$mode" == apply ]] || exit 2
test -f "$root/reports/soak-summary.json"
test ! -f "$root/operations/legacy-paused"
# Verified artifact transfer copies only. Installed runtimes/images and backups remain.
records=(
 'r0.3-images.tgz|d0485a521760f6261047172c9e469ca6d2a745742cce8a09d7d0fd2a7dd603b4'
 'r0.3-update.tgz|094cb7852cf6df21b26fa53b3839cadb68477a2aa1316b32d15a803e8b5054b7'
 'r0.3-api-final.tgz|e9ca9ee202deb07370eafe207b2a353d5216c5f234e34491392e50e9f9c66918'
 'r0.3-observed.tgz|2e1651fa207cb95c2a8aea7dc2b361e3549ca24490d520a36dbe39e6757948c4'
)
for record in "${records[@]}"; do
 IFS='|' read -r name expected <<< "$record"
 path="$root/$name"
 [[ ! -L "$path" && -f "$path" ]] || continue
 [[ $(readlink -f "$path") == "$path" ]]
 actual=$(sha256sum "$path");actual=${actual%% *}
 [[ "$actual" == "$expected" ]] || { echo "Preserving mismatched $name"; continue; }
 echo "$mode verified transfer $name"
 if [[ "$mode" == apply ]]; then rm -- "$path"; fi
done
df -h /
