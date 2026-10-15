#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/srv/agentplatform
export PLATFORM_ROOT="$root"
unset PLATFORM_API_IMAGE PLATFORM_GATEWAY_IMAGE POSTGRES_IMAGE REDIS_IMAGE RABBITMQ_IMAGE SEAWEEDFS_IMAGE
unset PLATFORM_BACKPLANE PLATFORM_INGRESS PLATFORM_EGRESS_CONTROL HTTP_BIND HTTP_PORT PRODUCTION_NGINX_CONFIG
env_compose=(docker compose --env-file "$root/deploy/images.env" -p agentplatform-env -f "$root/deploy/compose.env.yaml" -f "$root/deploy/compose.production.env.yaml")
app_compose=(docker compose --env-file "$root/deploy/images.env" -p agentplatform-app -f "$root/deploy/compose.app.yaml" -f "$root/deploy/compose.production.app.yaml")
case "${1:-check}" in
 check)
  python3 "$root/deploy/scripts/production-preflight.py"
  "${env_compose[@]}" config --quiet
  "${app_compose[@]}" --profile worker --profile runner --profile egress config --quiet
  ;;
 start)
  bash "$root/deploy/scripts/production-stack.sh" check
  "${env_compose[@]}" up -d --wait --wait-timeout 240
  "${app_compose[@]}" --profile worker --profile runner --profile egress up -d --wait --wait-timeout 240
  curl --noproxy '*' -fsS -H 'Host: agentplatform.lailihao.cloud' http://127.0.0.1/api/v1/readiness
  ;;
 stop)
  "${app_compose[@]}" --profile worker --profile runner --profile egress stop -t60
  "${env_compose[@]}" stop -t60
  ;;
 *) echo 'Usage:production-stack.sh [check|start|stop]' >&2; exit 2;;
esac
