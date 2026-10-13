#!/bin/sh
set -eu
# Only randomly generated hex secrets are accepted for safe SQL literal construction.
api=$(cat /run/secrets/pg_api)
worker=$(cat /run/secrets/pg_worker)
filer=$(cat /run/secrets/pg_filer)
migration=$(cat /run/secrets/pg_migration)
for value in "$api" "$worker" "$filer" "$migration"; do
 test "${#value}" -eq 64
 case "$value" in *[!0-9a-f]*) exit 1;; esac
done
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<EOF
REVOKE ALL ON DATABASE agentplatform FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE ROLE ap_migration LOGIN PASSWORD '$migration';
CREATE ROLE ap_api LOGIN PASSWORD '$api';
CREATE ROLE ap_worker LOGIN PASSWORD '$worker';
CREATE ROLE ap_filer LOGIN PASSWORD '$filer';
GRANT CONNECT ON DATABASE agentplatform TO ap_migration,ap_api,ap_worker,ap_filer;
GRANT CREATE ON DATABASE agentplatform TO ap_migration;
CREATE SCHEMA pf AUTHORIZATION ap_migration;
CREATE SCHEMA pf_runtime AUTHORIZATION ap_migration;
CREATE SCHEMA weed AUTHORIZATION ap_filer;
ALTER ROLE ap_filer SET search_path=weed;
GRANT USAGE ON SCHEMA pf,pf_runtime TO ap_api,ap_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE ap_migration IN SCHEMA pf_runtime GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO ap_api,ap_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE ap_migration IN SCHEMA pf GRANT SELECT ON TABLES TO ap_api,ap_worker;
EOF
