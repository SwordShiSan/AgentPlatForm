#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/srv/agentplatform/operations
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$root/legacy-backup-$stamp"
mkdir -p "$backup"
# Credentials remain inside existing containers; no environment dump or output.
docker exec mysql sh -c 'exec mysqldump --user=root --password="$MYSQL_ROOT_PASSWORD" --single-transaction --quick --routines --events --triggers --all-databases' 2> "$backup/mysql-stderr.txt" | gzip -1 > "$backup/mysql-all.sql.gz"
gzip -t "$backup/mysql-all.sql.gz"
gzip -dc "$backup/mysql-all.sql.gz" | awk '/CREATE TABLE/{n++} END{if(n<1)exit 1; print "mysql_table_definitions="n}' > "$backup/validation.txt"
docker exec ai-agent-pgvector sh -c 'exec pg_dumpall --username="$POSTGRES_USER"' | gzip -1 > "$backup/postgres-all.sql.gz"
gzip -t "$backup/postgres-all.sql.gz"
gzip -dc "$backup/postgres-all.sql.gz" | awk '/CREATE DATABASE|CREATE TABLE/{n++} END{if(n<1)exit 1; print "postgres_definitions="n}' >> "$backup/validation.txt"
cp /home/ai-agent_big-market/docker-compose-app-agent_cloud.yml "$backup/"
cp /home/ai-agent_big-market/docker-compose-app-front_cloud.yml "$backup/"
docker image inspect swordshisan/ai-agent-app:6.133 swordshisan/ai-agent-app:6.132 swordshisan/ai-agent_big-market-front-app:4.79 swordshisan/ai-agent_big-market-front-app:4.78 --format '{{.Id}} {{json .RepoTags}}' > "$backup/rollback-images.txt"
sha256sum "$backup"/*.gz "$backup"/*.yml "$backup"/rollback-images.txt > "$backup/SHA256SUMS"
(cd "$backup" && sha256sum --check SHA256SUMS > checksum-check.txt)
printf '%s\n' "$backup" > "$root/backup-verified"
echo "Protected fresh SQL backup and integrity checks: $backup"
echo 'SQL syntax/integrity verified; isolated restore rehearsal remains an acceptance gate.'
