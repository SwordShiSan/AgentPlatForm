#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/srv/agentplatform
export PLATFORM_ROOT="$root"
test -f "$root/operations/legacy-paused"
test -s "$root/deploy/images.env"
available=$(df --output=avail -B1 "$root" | tail -1 | tr -d ' ')
required=$(( $(du -sb "$root/data" | cut -f1) + 4294967296 + 5368709120 ))
(( available >= required )) || { echo 'Backup capacity below data+XFS+5GiB reserve' >&2; exit 1; }
backup="$root/operations/r04-backup-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m700 "$backup"
python3 - "$backup/profile-health.json" <<'PY'
import json,pathlib,subprocess,sys
ids=[]
for project in ['agentplatform-app','agentplatform-env']:
    ids.extend(subprocess.check_output(['docker','ps','-q','--filter','label=com.docker.compose.project='+project],text=True).split())
expected=10 if pathlib.Path('/srv/agentplatform/deploy/r1-enabled').is_file() else 12
assert len(ids)==expected,'Expected exact active release services before backup'
rows=[]
for item in json.loads(subprocess.check_output(['docker','inspect',*ids])):
    state=item['State'];host=item['HostConfig'];config=item['Config']
    assert state['Running'] and not state['OOMKilled']
    assert state.get('Health',{}).get('Status','healthy')=='healthy'
    assert config.get('User') not in ['', '0', '0:0', 'root']
    assert not host['Privileged'] and 'ALL' in host.get('CapDrop',[])
    assert any(value.startswith('no-new-privileges') for value in host.get('SecurityOpt',[]))
    assert all(mount['Destination'] not in ['/var/run/docker.sock','/run/docker.sock'] for mount in item.get('Mounts',[]))
    rows.append({'name':item['Name'],'running':True,'oomKilled':False,'health':state.get('Health',{}).get('Status','not-configured'),
                 'user':config['User'],'memoryLimit':host['Memory'],'pidsLimit':host['PidsLimit'],
                 'readOnlyRoot':host['ReadonlyRootfs'],'privileged':False,'capDrop':host['CapDrop']})
pathlib.Path(sys.argv[1]).write_text(json.dumps({'status':'PASS','services':rows},indent=2)+'\n')
PY
env_compose=(docker compose --env-file "$root/deploy/images.env" -p agentplatform-env -f "$root/deploy/compose.env.yaml")
app_compose=(docker compose --env-file "$root/deploy/images.env" -p agentplatform-app -f "$root/deploy/compose.app.yaml")
"${app_compose[@]}" --profile worker --profile runner --profile egress stop -t60
test -z "$(docker ps -q --filter label=com.docker.compose.project=agentplatform-app)"
for container in $(docker ps -q); do
 [[ $(docker inspect --format '{{.HostConfig.Runtime}}' "$container") != runsc ]] || { echo 'Active sandbox prevents consistent snapshot' >&2; exit 1; }
done
pg=$("${env_compose[@]}" ps -q postgres)
mq=$("${env_compose[@]}" ps -q rabbitmq)
test -n "$pg" && test -n "$mq"
bash "$root/deploy/scripts/recovery-database-evidence.sh" capture "$pg" > "$backup/recovery-database.txt"
docker exec "$pg" pg_dumpall -U ap_admin > "$backup/postgres.sql"
docker exec "$mq" rabbitmqctl export_definitions /tmp/r04-definitions.json >/dev/null
docker cp "$mq:/tmp/r04-definitions.json" "$backup/rabbitmq-definitions.json" >/dev/null
"${env_compose[@]}" stop -t60
test -z "$("${env_compose[@]}" ps -q --status running)"
# Cold DB and object data, including PG-held filer metadata, are copied at one quiescent point.
tar -C "$root" -czf "$backup/data.tgz" data deploy
mkdir -m700 "$backup/protected-keys"
cp -a "$root/secrets" "$backup/protected-keys/"
cp -a "$root/config" "$backup/protected-keys/"
chmod 700 "$backup/protected-keys"
printf '{"schemaVersion":1,"s3KekVersion":"local-s3-kek-v1","secretsLocation":"protected-keys/secrets","configLocation":"protected-keys/config"}\n' > "$backup/key-version.json"
frozen=false
unfreeze() { if [[ "$frozen" == true ]]; then xfs_freeze -u "$root/workspaces"; fi; }
trap unfreeze EXIT
python3 "$root/deploy/probes/sandbox_probe.py" verify-mount >/dev/null
sync
xfs_freeze -f "$root/workspaces"
frozen=true
cp --reflink=auto "$root/quota/workspaces.xfs" "$backup/workspaces.xfs"
unfreeze
frozen=false
python3 "$root/deploy/scripts/workspace-manifest.py" write "$root/workspaces" "$backup/workspace-files.json"
cp "$root/reports/object-manifest.json" "$backup/object-manifest.json"
xfs_quota -x -c 'report -p -b -i' "$root/workspaces" > "$backup/quota-report.txt"
(cd "$backup" && sha256sum data.tgz workspaces.xfs postgres.sql rabbitmq-definitions.json object-manifest.json workspace-files.json key-version.json recovery-database.txt profile-health.json > checksums.sha256)
printf '%s\n' "$backup" > "$root/operations/r04-latest-backup"
echo 'Quiescent backup prepared. Secret files are protected separately and are not public evidence.'
