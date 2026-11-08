#!/usr/bin/env bash
set -Eeuo pipefail

root=${1:?protected acceptance root required}
app_image=${2:?fixed R6.3 application config ID required}
renderer_image=${3:?fixed renderer config ID required}
bundle=${4:?fixed parser bundle required}
policy=${5:?fixed policy hash required}
database=${6:?isolated R6.3 database required}
archive_sha=${7:?fixed image archive hash required}
expected_runsc=210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a
runsc_path=/usr/local/lib/agentplatform-gvisor/20261005.0/runsc
name=7d2f6b70138a48228a7dc5bf6807b8ce2aa7c1d2c6f00f4d9b8a7f145b3e901a

[[ "$root" =~ ^/srv/agentplatform/acceptance/r63-signed-[a-z0-9-]+$ ]]
[[ "$app_image" =~ ^sha256:[a-f0-9]{64}$ && "$renderer_image" =~ ^sha256:[a-f0-9]{64}$ ]]
[[ "$policy" =~ ^[a-f0-9]{64}$ && "$database" =~ ^r63_[a-z0-9_]+$ ]]
[[ "$bundle" == asset-parser-tika3.3.3-v2 ]]
[[ "$archive_sha" =~ ^[a-f0-9]{64}$ ]]
[[ -d "$root" && -f "$root/runner-db-password" && -f "$root/runner-private.pem" && -f "$root/control-public.pem" ]]
[[ -f "$root/s3-access" && -f "$root/s3-secret" ]]

for pair in \
  'agentplatform-r5-app-runner-1:761e3c048ee0fb85591e8b63094466b6c85536ac23034ecd6e22d5881d80e10f' \
  'agentplatform-r5-app-worker-1:10b6d7a35691d2eea0c3f3316b9d5d6baf27fc2b814d44b6a79260c54e3d7054' \
  'agentplatform-r5-app-api-1:12723516b93121f83fe23ea331a5c60f6298b6810df4a45330cd4fe63ab103a1' \
  'agentplatform-r5-app-gateway-1:2c13a3b30ea372c2694686ae00077515af62df33954ec2af39f3160ed0488c27'; do
  service=${pair%%:*}; expected=${pair#*:}
  [[ $(docker inspect --format '{{.Id}}' "$service") == "$expected" ]]
  [[ $(docker inspect --format '{{.State.Status}}' "$service") == exited ]]
done
[[ ! $(docker ps -q --filter "name=^/${name}$") ]]

free_bytes=$(df -B1 --output=avail / | tail -n 1 | tr -d ' ')
(( free_bytes >= 5*1024*1024*1024 ))
mem_available_kb=$(awk '$1=="MemAvailable:" {print $2}' /proc/meminfo)
(( mem_available_kb >= 1728*1024 ))
[[ $(sha256sum "$runsc_path" | awk '{print $1}') == "$expected_runsc" ]]
python3 - "$runsc_path" <<'PY'
import json, subprocess, sys
runtimes=json.loads(subprocess.check_output(['docker','info','--format','{{json .Runtimes}}'],text=True))
runsc=runtimes.get('runsc',{})
assert runsc.get('path')==sys.argv[1]
assert runsc.get('runtimeArgs')==['--platform=systrap']
PY
if [[ -f "$root/r63-api-runner.tar" ]]; then
  [[ $(sha256sum "$root/r63-api-runner.tar" | awk '{print $1}') == "$archive_sha" ]]
  docker load -i "$root/r63-api-runner.tar" >/dev/null
fi
[[ $(docker image inspect --format '{{.Id}}' "$app_image") == "$app_image" ]]
[[ $(docker image inspect --format '{{.Architecture}}' "$app_image") == amd64 ]]
[[ $(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$app_image") == abc8dd11f11cfd7fd7195cb9a2012d69975b1d77 ]]
[[ $(docker image inspect --format '{{.Id}}' "$renderer_image") == "$renderer_image" ]]
rm -f "$root/r63-api-runner.tar"
free_bytes=$(df -B1 --output=avail / | tail -n 1 | tr -d ' ')
(( free_bytes >= 5*1024*1024*1024 ))
mkdir -p "$root/previews"
chown 10001:10001 "$root/previews"
chmod 700 "$root/previews"
for file in runner-db-password runner-private.pem control-public.pem s3-access s3-secret; do
  chown 10001:10001 "$root/$file"
  chmod 400 "$root/$file"
done
sha256sum "$runsc_path" | awk -v p="$runsc_path" '{print "{\"path\":\""p"\",\"sha256\":\""$1"\",\"runtimeArgs\":[\"--platform=systrap\"],\"verifiedAt\":"systime()"}"}' > "$root/runtime-attestation.json"
chmod 444 "$root/runtime-attestation.json"
docker_group=$(stat -c '%g' /var/run/docker.sock)

docker run -d --name "$name" --hostname "$name" --label ap.role=runner --label ap.r63.acceptance=true --network host --user 10001:10001 --group-add "$docker_group" \
  --read-only --cap-drop ALL --security-opt no-new-privileges:true --pids-limit 128 \
  --memory 320m --memory-swap 320m --cpus 1 \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m,uid=10001,gid=10001,mode=700 \
  --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock \
  --mount "type=bind,source=$root/previews,target=$root/previews" \
  --mount "type=bind,source=$root/runtime-attestation.json,target=/run/fixture/runtime-attestation.json,readonly" \
  --mount "type=bind,source=$root/runner-private.pem,target=/run/secrets/runner-private.pem,readonly" \
  --mount "type=bind,source=$root/control-public.pem,target=/run/secrets/control-public.pem,readonly" \
  --mount "type=bind,source=$root/runner-db-password,target=/run/secrets/runner-db-password,readonly" \
  --mount "type=bind,source=$root/s3-access,target=/run/secrets/s3-access,readonly" \
  --mount "type=bind,source=$root/s3-secret,target=/run/secrets/s3-secret,readonly" \
  -e SPRING_PROFILES_ACTIVE=preview-runner -e SERVER_ADDRESS=127.0.0.1 -e SERVER_PORT=18163 \
  -e HOSTNAME="$name" \
  -e JAVA_TOOL_OPTIONS='-Xms32m -Xmx128m -XX:MaxDirectMemorySize=48m -XX:+UseSerialGC' \
  -e PLATFORM_STORAGE_JDBC_URL="jdbc:postgresql://127.0.0.1:25463/$database?connectTimeout=2&socketTimeout=15" \
  -e PLATFORM_STORAGE_USER=ap_r63_runner -e PLATFORM_STORAGE_PASSWORD_FILE=/run/secrets/runner-db-password \
  -e PLATFORM_ATTACHMENTS_ENABLED=true -e PLATFORM_PREVIEW_ENABLED=true \
  -e PLATFORM_PREVIEW_IMAGE_ID="$renderer_image" -e PLATFORM_PREVIEW_BUNDLE="$bundle" -e PLATFORM_PREVIEW_POLICY_HASH="$policy" \
  -e PLATFORM_PREVIEW_RUNNER_STAGING_ROOT="$root/previews" \
  -e PLATFORM_PREVIEW_RUNNER_RUNTIME_ATTESTATION=/run/fixture/runtime-attestation.json \
  -e PLATFORM_PREVIEW_RUNNER_IDENTITY_PRIVATE_KEY_FILE=/run/secrets/runner-private.pem \
  -e PLATFORM_PREVIEW_RUNNER_IDENTITY_CONTROL_PUBLIC_KEY_FILE=/run/secrets/control-public.pem \
  -e PLATFORM_PREVIEW_RUNNER_IDENTITY_KEY_ID=r63-runner -e PLATFORM_PREVIEW_RUNNER_IDENTITY_CONTROL_KEY_ID=r63-control \
  -e PLATFORM_WORKSPACE_ENABLED=true -e PLATFORM_WORKSPACE_FILES_ENABLED=true \
  -e PLATFORM_WORKSPACE_FILES_HELPER_IMAGE=sha256:2ca37bd871c3b646161dded4c6623e62f31c6f5305e7d34489d0311889d5ebeb \
  -e PLATFORM_WORKSPACE_FILES_POLICY_HASH=7e647a0a4d5190f05f833bf940c83c1184f1084f50e4f1b3689d738fef57781f \
  -e PLATFORM_FILES_ASSETS_ENDPOINT=http://127.0.0.1:28333 -e PLATFORM_FILES_ASSETS_BUCKET=r63-assets \
  -e PLATFORM_FILES_ASSETS_ACCESS_KEY_FILE=/run/secrets/s3-access -e PLATFORM_FILES_ASSETS_SECRET_KEY_FILE=/run/secrets/s3-secret \
  "$app_image" >/dev/null

deadline=$((SECONDS+90))
while (( SECONDS < deadline )); do
  state=$(docker inspect --format '{{.State.Status}}' "$name")
  [[ "$state" == running ]] || { docker logs --tail 60 "$name" >&2; exit 1; }
  status=$(curl --noproxy '*' -sS --max-time 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:18163/internal/extractions/v1 || true)
  if [[ "$status" == 401 || "$status" == 403 || "$status" == 405 ]]; then
printf 'R63_PREVIEW_RUNNER_READY name=%s id=%s policy=%s runsc_sha256=%s\n' "$name" "$(docker inspect --format '{{.Id}}' "$name")" "$policy" "$expected_runsc"
    exit 0
  fi
  sleep 2
done
docker logs --tail 80 "$name" >&2
exit 1
