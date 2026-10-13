#!/usr/bin/env bash
set -euo pipefail
root=/srv/agentplatform
rollback() {
 status=$?
 trap - ERR
 echo 'New acceptance startup failed; stopping only new projects and restoring legacy.' >&2
 bash "$root/deploy/scripts/cloud-acceptance.sh" stop || true
 bash "$root/deploy/scripts/legacy-window.sh" restore
 exit "$status"
}
trap rollback ERR
bash "$root/deploy/scripts/cloud-acceptance.sh" dry-run
bash "$root/deploy/scripts/legacy-window.sh" pause
bash "$root/deploy/scripts/cloud-acceptance.sh" start
echo 'New platform developer acceptance active; legacy restore required after tests.'
