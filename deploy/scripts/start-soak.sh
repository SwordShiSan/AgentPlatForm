#!/usr/bin/env bash
set -euo pipefail
root=/srv/agentplatform
test -f "$root/operations/legacy-paused"
test ! -f "$root/reports/soak-summary.json"
if [[ -f "$root/reports/soak.pid" ]] && kill -0 "$(cat "$root/reports/soak.pid")" 2>/dev/null; then echo 'Soak already running'; exit 0; fi
# Dedicated protected log, no credentials or task contents are sampled.
nohup python3 "$root/deploy/probes/soak_probe.py" > "$root/reports/soak.log" 2>&1 < /dev/null &
printf '%s\n' "$!" > "$root/reports/soak.pid"
echo '30-minute fixed candidate observation launched.'
