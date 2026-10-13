#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Ephemeral GitHub Actions fixture only' >&2; exit 1; }
[[ ! -e /srv/agentplatform/operations/legacy-paused && ! -e /srv/agentplatform/source-images.json ]] || { echo 'Refusing operator cloud environment' >&2; exit 1; }
export PLATFORM_ROOT=/srv/agentplatform
export PLATFORM_API_IMAGE=agentplatform-api:r0.1-local
export PLATFORM_GATEWAY_IMAGE=agentplatform-gateway:r0.1-local
sudo python3 deploy/scripts/prepare-cloud-config.py
sudo mkdir -p "$PLATFORM_ROOT/deploy/probes" "$PLATFORM_ROOT/reports/java-storage"
sudo cp deploy/probes/storage_probe.py "$PLATFORM_ROOT/deploy/probes/"
sudo chown -R 10001:10001 "$PLATFORM_ROOT/reports"
env_compose=(docker compose -p agentplatform-env -f deploy/compose.env.yaml)
cleanup() { "${env_compose[@]}" logs --no-color > evidence/r0.3/ci-env.log 2>&1; "${env_compose[@]}" stop; }
mkdir -p evidence/r0.3
trap cleanup EXIT
"${env_compose[@]}" up -d --wait --wait-timeout 240
docker compose -p agentplatform-app -f deploy/compose.app.yaml --profile migration run --rm migrate
docker build -t agentplatform-storage-probe:r0.3-ci -f deploy/storage-probe.Dockerfile .
probe() {
 local mode=$1
 local admin=()
 [[ "$mode" != bootstrap ]] || admin=(--mount "type=bind,src=$PLATFORM_ROOT/secrets/s3_admin,dst=/run/secrets/s3_admin,readonly")
 docker run --rm --network agentplatform-backplane --user 10001:10001 --read-only --cap-drop ALL --security-opt no-new-privileges --memory 192m --tmpfs /tmp:rw,noexec,nosuid,size=16m \
  -e "PROBE_MODE=$mode" --mount "type=bind,src=$PLATFORM_ROOT/reports,dst=/reports" \
  --mount "type=bind,src=$PLATFORM_ROOT/secrets/pg_api,dst=/run/secrets/pg_api,readonly" \
  --mount "type=bind,src=$PLATFORM_ROOT/secrets/pg_migration,dst=/run/secrets/pg_migration,readonly" \
  --mount "type=bind,src=$PLATFORM_ROOT/secrets/mq_publisher,dst=/run/secrets/mq_publisher,readonly" \
  --mount "type=bind,src=$PLATFORM_ROOT/secrets/mq_execution,dst=/run/secrets/mq_execution,readonly" \
  --mount "type=bind,src=$PLATFORM_ROOT/secrets/redis,dst=/run/secrets/redis,readonly" \
  --mount "type=bind,src=$PLATFORM_ROOT/secrets/s3_read,dst=/run/secrets/s3_read,readonly" \
  --mount "type=bind,src=$PLATFORM_ROOT/secrets/s3_write,dst=/run/secrets/s3_write,readonly" \
  "${admin[@]}" agentplatform-storage-probe:r0.3-ci
}
probe bootstrap
probe initial
"${env_compose[@]}" stop s3 filer volume master
"${env_compose[@]}" up -d --wait --wait-timeout 240 master volume filer s3
"${env_compose[@]}" restart redis
"${env_compose[@]}" up -d --wait --wait-timeout 240
probe restart
docker run --rm --network agentplatform-backplane --user 10001:10001 --read-only --cap-drop ALL --security-opt no-new-privileges --memory 512m --tmpfs /tmp:rw,noexec,nosuid,size=32m \
 -e PLATFORM_PROBE_JDBC_URL='jdbc:postgresql://postgres:5432/agentplatform?connectTimeout=2&socketTimeout=3' -e PLATFORM_PROBE_PASSWORD_FILE=/run/secrets/pg_api \
 --mount "type=bind,src=$PLATFORM_ROOT/reports,dst=/reports" --mount "type=bind,src=$PLATFORM_ROOT/secrets/pg_api,dst=/run/secrets/pg_api,readonly" \
 --entrypoint java agentplatform-framework-probe:r0.2-local -Xmx256m -cp '/opt/probe/target/test-classes:/opt/probe/classes:/opt/probe/lib/*' org.junit.platform.console.ConsoleLauncher execute --select-class com.agentplatform.infrastructure.storage.PostgresStateStorageIT --reports-dir=/reports/java-storage --fail-if-no-tests
probe cleanup
sudo cp -a "$PLATFORM_ROOT/reports/." evidence/r0.3/
sudo chown -R "$(id -u):$(id -g)" evidence/r0.3
echo 'Real CI PostgreSQL/RabbitMQ/S3/Redis and Java CAS:PASS; cloud-only gVisor/XFS/soak evidence is separate.'
