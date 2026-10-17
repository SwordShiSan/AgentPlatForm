#!/usr/bin/env bash
set -euo pipefail
mode=${1:?seed or capture}
container=${2:?exact new-stack PostgreSQL container}
project=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$container")
[[ "$project" == agentplatform-env || "$project" == agentplatform-r04-restore-env ]] || { echo 'Refusing non-fixture database'; exit 1; }
if [[ "$mode" == seed ]]; then
 docker exec -i "$container" psql -X -U ap_admin -d agentplatform -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE IF NOT EXISTS pf.r04_restore_vector(id text PRIMARY KEY, value vector(3) NOT NULL);
INSERT INTO pf.r04_restore_vector VALUES('r04-functional-restore','[1,2,3]') ON CONFLICT(id) DO UPDATE SET value=EXCLUDED.value;
SQL
elif [[ "$mode" == capture ]]; then
 docker exec -i "$container" psql -XAt -U ap_admin -d agentplatform -v ON_ERROR_STOP=1 <<'SQL'
SELECT 'schema='||schema_version FROM pf.baseline_version WHERE id=1;
SELECT 'vector-version='||extversion FROM pg_extension WHERE extname='vector';
SELECT 'vector='||id||':'||value::text FROM pf.r04_restore_vector ORDER BY id;
SELECT 'distance='||('[1,2,3]'::vector <-> value)::text FROM pf.r04_restore_vector ORDER BY id;
SELECT 'state='||md5(COALESCE(string_agg(row_to_json(s)::text,'|' ORDER BY space_id,user_id,conversation_id,state_key),'')) FROM pf_runtime.state_slot s;
SQL
 # R1 facts are checked only when the additive identity tables exist.
 if [[ $(docker exec "$container" psql -XAt -U ap_admin -d agentplatform -c "SELECT to_regclass('pf.failure_record') IS NOT NULL") == t ]]; then
  for table in app_user space membership space_storage auth_session user_preference user_preference_revision user_model_credential credential model_binding conversation message task chat_config_snapshot budget_account model_call budget_reservation acceptance_model_meter failure_record; do
   docker exec "$container" psql -XAt -U ap_admin -d agentplatform -v ON_ERROR_STOP=1 -c "SELECT '$table='||count(*)||':'||md5(coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY row_to_json(t)::text),'')) FROM pf.$table t;"
  done
 fi
else
 echo 'Unknown recovery evidence mode' >&2; exit 2
fi
