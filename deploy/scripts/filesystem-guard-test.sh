#!/usr/bin/env bash
set -euo pipefail
root=/srv/agentplatform
restore() { mountpoint -q "$root/workspaces" || mount -o loop,prjquota,noatime "$root/quota/workspaces.xfs" "$root/workspaces"; }
trap restore EXIT
python3 "$root/deploy/scripts/sandbox_probe.py" verify-mount
umount "$root/workspaces"
if python3 "$root/deploy/scripts/sandbox_probe.py" verify-mount > "$root/reports/missing-mount-denial.txt" 2>&1; then
 echo 'Missing mount incorrectly accepted' >&2;exit 1
fi
test -z "$(find "$root/workspaces" -mindepth 1 -maxdepth 1 -print -quit)"
restore
python3 "$root/deploy/scripts/sandbox_probe.py" verify-mount
printf '{"case":"missing-mount-denies-underlay","status":"PASS"}\n' > "$root/reports/mount-guard.json"
