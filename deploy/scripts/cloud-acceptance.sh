#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/srv/agentplatform
export PLATFORM_ROOT="$root"
unset PLATFORM_API_IMAGE PLATFORM_GATEWAY_IMAGE POSTGRES_IMAGE REDIS_IMAGE RABBITMQ_IMAGE SEAWEEDFS_IMAGE
env_compose=(docker compose --env-file "$root/deploy/images.env" -p agentplatform-env -f "$root/deploy/compose.env.yaml")
app_compose=(docker compose --env-file "$root/deploy/images.env" -p agentplatform-app -f "$root/deploy/compose.app.yaml")
mode=${1:-dry-run}
case "$mode" in
 dry-run)
  "${env_compose[@]}" config --quiet
  "${app_compose[@]}" --profile migration --profile worker --profile runner --profile egress config --quiet
  echo 'Both isolated projects validated; no services started.'
  ;;
 start)
  test -f "$root/operations/legacy-paused"
  bash "$root/deploy/scripts/sandbox-host.sh" install
  "${env_compose[@]}" up -d --wait --wait-timeout 240
  "${app_compose[@]}" --profile migration run --rm migrate
  "${app_compose[@]}" --profile worker --profile runner --profile egress up -d --wait --wait-timeout 240
  curl -fsS http://127.0.0.1:18080/api/v1/health
  curl -fsS http://127.0.0.1:18080/api/v1/readiness
  ;;
 upgrade)
  test -f "$root/operations/legacy-paused"
  python3 "$root/deploy/scripts/verify-imported-images.py"
  "${app_compose[@]}" --profile migration run --rm migrate
  "${app_compose[@]}" --profile worker --profile runner --profile egress up -d --wait --wait-timeout 240
  curl -fsS http://127.0.0.1:18080/api/v1/readiness
  ;;
 storage|storage-bootstrap|storage-restart|storage-cleanup)
  probe_mode=initial
  [[ "$mode" != storage-restart ]] || probe_mode=restart
  [[ "$mode" != storage-cleanup ]] || probe_mode=cleanup
  [[ "$mode" != storage-bootstrap ]] || probe_mode=bootstrap
  admin_mount=()
  if [[ "$probe_mode" == bootstrap ]]; then admin_mount=(--mount "type=bind,src=$root/secrets/s3_admin,dst=/run/secrets/s3_admin,readonly"); fi
  docker run --rm --name agentplatform-r03-storage-probe --network agentplatform-backplane --user 10001:10001 --read-only --cap-drop ALL --security-opt no-new-privileges --memory 192m --pids-limit 64 --tmpfs /tmp:rw,noexec,nosuid,size=16m \
   -e "PROBE_MODE=$probe_mode" \
   --mount "type=bind,src=$root/reports,dst=/reports" \
   --mount "type=bind,src=$root/deploy/probes/storage_probe.py,dst=/opt/probes/storage_probe.py,readonly" \
   --mount "type=bind,src=$root/secrets/pg_api,dst=/run/secrets/pg_api,readonly" \
   --mount "type=bind,src=$root/secrets/pg_migration,dst=/run/secrets/pg_migration,readonly" \
   --mount "type=bind,src=$root/secrets/mq_publisher,dst=/run/secrets/mq_publisher,readonly" \
   --mount "type=bind,src=$root/secrets/mq_execution,dst=/run/secrets/mq_execution,readonly" \
   --mount "type=bind,src=$root/secrets/redis,dst=/run/secrets/redis,readonly" \
   --mount "type=bind,src=$root/secrets/s3_read,dst=/run/secrets/s3_read,readonly" \
   --mount "type=bind,src=$root/secrets/s3_write,dst=/run/secrets/s3_write,readonly" \
   "${admin_mount[@]}" \
   agentplatform-storage-probe:r0.3-candidate
  ;;
 java-storage)
  docker run --rm --name agentplatform-r03-java-storage --network agentplatform-backplane --user 10001:10001 --read-only --cap-drop ALL --security-opt no-new-privileges --memory 512m --pids-limit 128 --tmpfs /tmp:rw,noexec,nosuid,size=32m \
   -e PLATFORM_PROBE_JDBC_URL='jdbc:postgresql://postgres:5432/agentplatform?connectTimeout=2&socketTimeout=3' -e PLATFORM_PROBE_PASSWORD_FILE=/run/secrets/pg_api \
   --mount "type=bind,src=$root/reports,dst=/reports" --mount "type=bind,src=$root/secrets/pg_api,dst=/run/secrets/pg_api,readonly" \
   --entrypoint java agentplatform-framework-probe:r0.3-candidate -Xmx256m -cp '/opt/probe/target/test-classes:/opt/probe/classes:/opt/probe/lib/*' org.junit.platform.console.ConsoleLauncher execute --select-class com.agentplatform.infrastructure.storage.PostgresStateStorageIT --reports-dir=/reports/java-storage --fail-if-no-tests
  ;;
 restart)
  "${env_compose[@]}" stop s3 filer volume master
  "${env_compose[@]}" up -d --wait --wait-timeout 240 master volume filer s3
  "${env_compose[@]}" restart redis rabbitmq
  "${env_compose[@]}" up -d --wait --wait-timeout 240
  ;;
 stop)
  "${app_compose[@]}" --profile worker --profile runner --profile egress stop -t60
  "${env_compose[@]}" stop -t60
  ;;
 *) echo 'Usage:cloud-acceptance.sh [dry-run|start|storage|java-storage|restart|storage-restart|storage-cleanup|stop]' >&2;exit 2;;
esac
