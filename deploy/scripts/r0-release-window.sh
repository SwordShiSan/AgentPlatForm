#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/srv/agentplatform
run=${1:?Protected release run directory required}
[[ "$run" =~ ^/srv/agentplatform/operations/r0-http-release-[a-f0-9]{12}-[0-9]{8}T[0-9]{6}Z$ ]]
test -d "$run"
exec 9>"$root/operations/r0-release.lock"
flock -n 9
accepted=false
cleanup() {
 status=$?
 trap - EXIT TERM INT
 set +e
 if [[ "$accepted" != true ]]; then
  systemctl stop agentplatform-r0.service >/dev/null 2>&1
  systemctl disable agentplatform-r0.service >/dev/null 2>&1
  for name in agentplatform-r03-soak-sandbox agentplatform-r03-sandbox-probe; do
   id=$(docker ps -aq --filter name="^/$name$" --filter label=project=agentplatform)
   [[ -z "$id" ]] || docker rm -f "$id" >/dev/null
  done
  for project in agentplatform-r04-restore-app agentplatform-r04-restore-env; do
   ids=$(docker ps -q --filter label=com.docker.compose.project="$project")
   [[ -z "$ids" ]] || docker stop -t60 $ids >/dev/null
  done
  if [[ -f "$root/operations/legacy-paused" ]]; then
   bash "$root/deploy/scripts/production-stack.sh" stop >> "$run/rollback.log" 2>&1 || status=1
   bash "$root/deploy/scripts/legacy-window.sh" restore >> "$run/rollback.log" 2>&1 || status=1
   python3 "$root/deploy/scripts/legacy-health.py" "$run/legacy-restored.json" >> "$run/rollback.log" 2>&1 || status=1
  fi
  echo 'FAILED_OR_ROLLED_BACK' > "$run/state"
 fi
 printf '%s\n' "$status" > "$run/exit-code"
 exit "$status"
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
test ! -e "$root/operations/legacy-paused"
test "$(df -B1 --output=avail "$root" | tail -1)" -gt 10737418240
bash "$root/deploy/scripts/cloud-inventory.sh" > "$run/inventory.log" 2>&1
snapshot=$(cat "$root/operations/latest-inventory")
python3 "$root/deploy/scripts/legacy-health.py" "$run/legacy-before.json" "$snapshot/legacy-policies.txt" > "$run/legacy-before.log"
bash "$root/deploy/scripts/legacy-backup.sh" > "$run/legacy-backup.log" 2>&1
cmp "$root/tools/gvisor-20261005.0/unpacked/runsc" /usr/local/lib/agentplatform-gvisor/20261005.0/runsc
bash "$root/deploy/scripts/legacy-window.sh" pause > "$run/legacy-pause.log" 2>&1
echo LEGACY_PAUSED > "$run/state"
export PLATFORM_ROOT="$root"
unset PLATFORM_API_IMAGE PLATFORM_GATEWAY_IMAGE POSTGRES_IMAGE REDIS_IMAGE RABBITMQ_IMAGE SEAWEEDFS_IMAGE
env_compose=(docker compose --env-file "$root/deploy/images.env" -p agentplatform-env -f "$root/deploy/compose.env.yaml")
app_compose=(docker compose --env-file "$root/deploy/images.env" -p agentplatform-app -f "$root/deploy/compose.app.yaml")
python3 "$root/deploy/scripts/verify-imported-images.py" > "$run/imported-images.log"
bash "$root/deploy/scripts/sandbox-host.sh" quota > "$run/quota.log" 2>&1
"${env_compose[@]}" up -d --wait --wait-timeout 240 > "$run/start-environment.log" 2>&1
"${app_compose[@]}" --profile migration run --rm migrate > "$run/migrate.log" 2>&1
"${app_compose[@]}" --profile worker --profile runner --profile egress up -d --wait --wait-timeout 240 > "$run/start-app.log" 2>&1
for mode in storage-bootstrap storage java-storage restart storage-restart; do
 bash "$root/deploy/scripts/cloud-acceptance.sh" "$mode" > "$run/$mode.log" 2>&1
done
python3 "$root/deploy/probes/sandbox_probe.py" > "$run/sandbox.log" 2>&1
bash "$root/deploy/scripts/filesystem-guard-test.sh" > "$run/mount-guard.log" 2>&1
bash "$root/deploy/scripts/seed-release-recovery.sh" > "$run/recovery-seed.log" 2>&1
bash "$root/deploy/scripts/cloud-consistent-backup.sh" > "$run/backup.log" 2>&1
bash "$root/deploy/scripts/cloud-isolated-restore.sh" > "$run/restore.log" 2>&1
python3 "$root/deploy/scripts/production-guard-tests.py" > "$run/production-guards.log" 2>&1
install -m644 "$root/deploy/agentplatform-r0.service" /etc/systemd/system/agentplatform-r0.service
install -m644 "$root/deploy/srv-agentplatform-workspaces.mount" /etc/systemd/system/srv-agentplatform-workspaces.mount
systemctl daemon-reload
systemctl start agentplatform-r0.service > "$run/production-start.log" 2>&1
systemctl restart agentplatform-r0.service > "$run/production-restart.log" 2>&1
python3 "$root/deploy/scripts/production-http-check.py" > "$run/http.log" 2>&1
echo WAITING_FOR_BROWSER > "$run/state"
for attempt in {1..180}; do
 [[ ! -e "$run/browser-passed" ]] || break
 sleep 5
done
test -s "$run/browser-passed"
cmp "$run/browser-passed" "$run/source-revision.txt"
echo OBSERVING > "$run/state"
PLATFORM_SOAK_PRODUCTION=1 python3 "$root/deploy/probes/soak_probe.py" > "$run/observation.log" 2>&1
python3 "$root/deploy/scripts/production-http-check.py" > "$run/http-final.log" 2>&1
systemctl enable agentplatform-r0.service > "$run/enable.log" 2>&1
printf '{"state":"DEPLOYED_PENDING_RELEASE","legacySuspended":true,"httpOnly":true}\n' > "$root/operations/r0-production.json"
echo DEPLOYED_PENDING_RELEASE > "$run/state"
accepted=true
echo 'All production gates PASS; new stack remains online, legacy retained stopped.'
