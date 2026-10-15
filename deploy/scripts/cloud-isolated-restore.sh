#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/srv/agentplatform
test -f "$root/operations/legacy-paused"
backup=$(cat "$root/operations/r04-latest-backup")
[[ "$backup" == "$root/operations/r04-backup-"* ]] && test -s "$backup/checksums.sha256"
(cd "$backup" && sha256sum -c checksums.sha256)
# Original platform must be completely stopped. Never restore over its paths.
test -z "$(docker ps -q --filter label=com.docker.compose.project=agentplatform-env)"
test -z "$(docker ps -q --filter label=com.docker.compose.project=agentplatform-app)"
available=$(df --output=avail -B1 "$root" | tail -1 | tr -d ' ')
required=$(( $(du -sb "$root/data" | cut -f1) + 4294967296 + 5368709120 ))
(( available >= required )) || { echo 'Restore would consume root safety reserve' >&2; exit 1; }
restore="$root/operations/r04-restore-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m700 "$restore"
env_compose=()
app_compose=()
mounted=false
cleanup() {
 status=$?
 set +e
 failed=0
 if (( ${#app_compose[@]} )); then "${app_compose[@]}" --profile worker --profile runner --profile egress stop -t60; (( $? == 0 )) || failed=1; fi
 if (( ${#env_compose[@]} )); then "${env_compose[@]}" stop -t60; (( $? == 0 )) || failed=1; fi
 if [[ "$mounted" == true ]]; then umount "$restore/workspaces"; (( $? == 0 )) || failed=1; fi
 if (( failed )); then echo 'Restore cleanup failed; retain fixture and block acceptance' >&2; status=1; fi
 trap - EXIT
 exit "$status"
}
trap cleanup EXIT
tar -xzf "$backup/data.tgz" -C "$restore"
cp -a "$backup/protected-keys/secrets" "$restore/"
cp -a "$backup/protected-keys/config" "$restore/"
mkdir -m700 "$restore/quota" "$restore/workspaces" "$restore/reports" "$restore/operations"
python3 - "$backup/protected-keys" "$restore" <<'PY'
import hashlib,pathlib,sys
before,after=map(pathlib.Path,sys.argv[1:])
def manifest(root):
    return {str(path.relative_to(root)):hashlib.sha256(path.read_bytes()).hexdigest()
            for folder in ('secrets','config') for path in (root/folder).rglob('*') if path.is_file()}
assert manifest(before)==manifest(after),'Restored key/config identity differs'
print('Protected key/config identities:PASS')
PY
cp --reflink=auto "$backup/workspaces.xfs" "$restore/quota/workspaces.xfs"
# Frozen snapshots can contain an unreplayed log. Replay only the private copy
# with duplicate-UUID admission, then unmount before assigning its new identity.
# No service starts during replay; normal mounts below never use nouuid.
original_uuid=$(cat "$root/quota/filesystem.uuid")
backup_uuid=$(blkid -p -s UUID -o value "$backup/workspaces.xfs")
mount -o loop,nouuid,prjquota "$restore/quota/workspaces.xfs" "$restore/workspaces"
mounted=true
umount "$restore/workspaces"
mounted=false
xfs_admin -U generate "$restore/quota/workspaces.xfs" > "$restore/reports/uuid-change.txt" 2>&1
blkid -p -s UUID -o value "$restore/quota/workspaces.xfs" > "$restore/quota/filesystem.uuid"
restored_uuid=$(cat "$restore/quota/filesystem.uuid")
[[ "$restored_uuid" != "$original_uuid" && "$restored_uuid" != "$backup_uuid" ]]
[[ $(blkid -p -s UUID -o value "$root/quota/workspaces.xfs") == "$original_uuid" ]]
mount -o loop,prjquota "$restore/quota/workspaces.xfs" "$restore/workspaces"
mounted=true
findmnt -n -t xfs -o OPTIONS --target "$restore/workspaces" | grep -q prjquota
export PLATFORM_ROOT="$restore" PLATFORM_BACKPLANE=agentplatform-r04-restore-backplane
export PLATFORM_INGRESS=agentplatform-r04-restore-ingress PLATFORM_EGRESS_CONTROL=agentplatform-r04-restore-egress GATEWAY_PORT=18082
env_compose=(docker compose --env-file "$restore/deploy/images.env" -p agentplatform-r04-restore-env -f "$restore/deploy/compose.env.yaml")
app_compose=(docker compose --env-file "$restore/deploy/images.env" -p agentplatform-r04-restore-app -f "$restore/deploy/compose.app.yaml")
python3 "$root/deploy/scripts/workspace-manifest.py" verify "$restore/workspaces" "$backup/workspace-files.json"
xfs_quota -x -c 'report -p -b -i' "$restore/workspaces" > "$restore/reports/quota-report.txt"
diff -u <(tail -n +2 "$backup/quota-report.txt") <(tail -n +2 "$restore/reports/quota-report.txt")
PLATFORM_ROOT="$restore" python3 "$root/deploy/probes/sandbox_probe.py" verify-mount
PLATFORM_ROOT="$restore" python3 "$root/deploy/probes/sandbox_probe.py"
# The trusted sandbox probe temporarily changes and then clears project100001.
# Restore the persisted workspace budget before checking data/remount again.
xfs_quota -x -c 'limit -p bhard=2g ihard=100000 100001' "$restore/workspaces"
python3 - "$restore/workspaces/r04-recovery-fixture" <<'PY'
import errno,os,pathlib,shutil,sys
root=pathlib.Path(sys.argv[1]);assert root.is_dir()
# XFS statfs on a project directory is quota-aware. Measure global free space
# on the unassigned mount root, not the already limited workspace itself.
assert shutil.disk_usage(root.parent).free>2*1024**3
probe=root/'quota-denial.tmp'
try:
    with probe.open('wb') as stream:
        try:os.posix_fallocate(stream.fileno(),0,2*1024**3)
        except OSError as error:assert error.errno==errno.ENOSPC
        else:raise AssertionError('Restored project quota did not refuse allocation')
finally:
    if probe.exists():probe.unlink()
PY
umount "$restore/workspaces"
mounted=false
if PLATFORM_ROOT="$restore" python3 "$root/deploy/probes/sandbox_probe.py" verify-mount > "$restore/reports/missing-mount.txt" 2>&1; then
 echo 'Restored missing mount incorrectly admitted' >&2; exit 1
fi
test -z "$(find "$restore/workspaces" -mindepth 1 -maxdepth 1 -print -quit)"
mount -o loop,prjquota "$restore/quota/workspaces.xfs" "$restore/workspaces"
mounted=true
python3 "$root/deploy/scripts/workspace-manifest.py" verify "$restore/workspaces" "$backup/workspace-files.json"
"${env_compose[@]}" config --quiet
"${app_compose[@]}" config --quiet
"${env_compose[@]}" up -d --wait --wait-timeout 240
"${app_compose[@]}" up -d --wait --wait-timeout 240
curl -fsS http://127.0.0.1:18082/api/v1/readiness > "$restore/reports/readiness.json"
pg=$("${env_compose[@]}" ps -q postgres)
bash "$root/deploy/scripts/recovery-database-evidence.sh" capture "$pg" > "$restore/reports/recovery-database.txt"
diff -u "$backup/recovery-database.txt" "$restore/reports/recovery-database.txt"
mq=$("${env_compose[@]}" ps -q rabbitmq)
docker exec "$mq" rabbitmqctl export_definitions /tmp/r04-restored-definitions.json >/dev/null
docker cp "$mq:/tmp/r04-restored-definitions.json" "$restore/rabbitmq-restored-private.json" >/dev/null
chmod 600 "$restore/rabbitmq-restored-private.json"
python3 - "$backup/rabbitmq-definitions.json" "$restore/rabbitmq-restored-private.json" <<'PY'
import json,sys
def normalized(value):
    if isinstance(value,dict):return {key:normalized(item) for key,item in value.items()}
    if isinstance(value,list):return sorted([normalized(item) for item in value],key=lambda item:json.dumps(item,sort_keys=True))
    return value
before=json.load(open(sys.argv[1]));after=json.load(open(sys.argv[2]))
assert normalized(before)==normalized(after),'Restored RabbitMQ definitions differ'
print('Restored RabbitMQ definitions:PASS')
PY
cp "$backup/object-manifest.json" "$restore/reports/object-manifest.json"
chown -R 10001:10001 "$restore/reports"
docker run --rm --network "$PLATFORM_BACKPLANE" --user 10001:10001 --read-only --cap-drop ALL --security-opt no-new-privileges --memory 192m --tmpfs /tmp:rw,noexec,nosuid,size=16m \
 -e PROBE_MODE=restart --mount "type=bind,src=$restore/reports,dst=/reports" \
 --mount "type=bind,src=$restore/secrets/pg_api,dst=/run/secrets/pg_api,readonly" \
 --mount "type=bind,src=$restore/secrets/pg_migration,dst=/run/secrets/pg_migration,readonly" \
 --mount "type=bind,src=$restore/secrets/mq_publisher,dst=/run/secrets/mq_publisher,readonly" \
 --mount "type=bind,src=$restore/secrets/mq_execution,dst=/run/secrets/mq_execution,readonly" \
 --mount "type=bind,src=$restore/secrets/redis,dst=/run/secrets/redis,readonly" \
 --mount "type=bind,src=$restore/secrets/s3_read,dst=/run/secrets/s3_read,readonly" \
 --mount "type=bind,src=$restore/secrets/s3_write,dst=/run/secrets/s3_write,readonly" \
 "$(sed -n 's/^STORAGE_PROBE_IMAGE=//p' "$restore/deploy/images.env")"
printf '%s\n' "$restore" > "$root/operations/r04-latest-restore"
echo 'Isolated restore verified; temporary stacks stopped by trap; protected snapshot retained.'
