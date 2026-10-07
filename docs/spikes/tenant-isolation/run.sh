#!/usr/bin/env bash
# Tenant isolation spike: RLS over the reactive pool. See ../tenant-isolation.md.
#
#   ./run.sh all          bootstrap, build, setup, probe, control, templates, bypass, bench (as recorded)
#   ./run.sh <command>    one of: bootstrap build setup probe control templates bypass bench
#   ./run.sh down         drop the spike database and roles (and the container, if this script made it)
#
# PostgreSQL: by default this script starts its own container, vec14-pg (postgres:16-alpine),
# on 127.0.0.1:5714. To use an existing PostgreSQL 16 container instead, set PG_CONTAINER to its
# name and PG_PORT to its published port; PG_SUPERUSER must be a superuser there. The spike
# creates its own database (tenancy_spike) and two roles (spike_owner, spike_app), and touches
# nothing else.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
OWN_CONTAINER=vec14-pg
PG_CONTAINER="${PG_CONTAINER:-$OWN_CONTAINER}"
PG_PORT="${PG_PORT:-5714}"
PG_SUPERUSER="${PG_SUPERUSER:-postgres}"
URL="postgresql://127.0.0.1:${PG_PORT}/tenancy_spike"

psql_super() { docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -q -U "$PG_SUPERUSER" -d postgres "$@"; }

up() {
  if [ "$PG_CONTAINER" = "$OWN_CONTAINER" ] && ! docker inspect "$OWN_CONTAINER" >/dev/null 2>&1; then
    docker run -d --name "$OWN_CONTAINER" -e POSTGRES_PASSWORD=postgres \
      -p "127.0.0.1:${PG_PORT}:5432" postgres:16-alpine >/dev/null
    until docker exec "$OWN_CONTAINER" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
  fi
}

bootstrap() {
  up
  psql_super <<'SQL'
drop database if exists tenancy_spike;
drop role if exists spike_app;
drop role if exists spike_owner;
-- Neither role is a superuser or has BYPASSRLS: a superuser is exempt from every policy.
create role spike_owner login password 'spike_owner' nosuperuser nobypassrls;
create role spike_app   login password 'spike_app'   nosuperuser nobypassrls;
create database tenancy_spike owner spike_owner;
SQL
}

build() { (cd "$DIR" && mvn -B -ntp -q package -DskipTests); }

run() {
  java -Dquarkus.datasource.reactive.url="$URL" -Dquarkus.datasource.owner.reactive.url="$URL" \
       -jar "$DIR/target/quarkus-app/quarkus-run.jar" "$@"
}

# Who is exempt from the policy, and what an unset or ended binding reads back as.
bypass() {
  docker exec "$PG_CONTAINER" psql -q -U "$PG_SUPERUSER" -d tenancy_spike -tAc \
    "select 'superuser $PG_SUPERUSER, unbound, sees ' || count(*) from item"
  docker exec -e PGPASSWORD=spike_owner "$PG_CONTAINER" psql -q -h 127.0.0.1 -U spike_owner -d tenancy_spike -tA \
    -c "select 'owner, FORCE on, unbound, sees ' || count(*) from item" \
    -c "alter table item no force row level security" \
    -c "select 'owner, FORCE off, unbound, sees ' || count(*) from item" \
    -c "alter table item force row level security"
  docker exec -e PGPASSWORD=spike_app "$PG_CONTAINER" psql -q -h 127.0.0.1 -U spike_app -d tenancy_spike -tA \
    -c "select 'app, unbound, sees ' || count(*) from item" \
    -c "select 'app, never set, setting reads ' || coalesce(current_setting('app.current_tenant_id', true), 'NULL')" \
    -c "begin" -c "select set_config('app.current_tenant_id', (select id::text from tenant limit 1), true) is not null" \
    -c "commit" \
    -c "select 'app, after a committed local binding, setting reads [' || current_setting('app.current_tenant_id', true) || ']'" \
    -c "select count(*) from item where tenant_id = current_setting('app.current_tenant_id', true)::uuid" 2>&1 || true
}

down() {
  psql_super -c 'drop database if exists tenancy_spike' -c 'drop role if exists spike_app' \
             -c 'drop role if exists spike_owner' || true
  if [ "$PG_CONTAINER" = "$OWN_CONTAINER" ]; then docker rm -f "$OWN_CONTAINER" >/dev/null 2>&1 || true; fi
}

case "${1:-all}" in
  all)
    bootstrap; build
    echo "== setup";     run setup
    echo "== probe";     run probe
    echo "== control";   run control
    echo "== templates"; run templates
    echo "== bypass";    bypass
    echo "== bench";     run bench
    ;;
  bootstrap) bootstrap ;;
  build) build ;;
  down) down ;;
  bypass) bypass ;;
  setup|probe|control|templates|bench) run "$1" ;;
  *) echo "usage: $0 [all|bootstrap|build|setup|probe|control|templates|bypass|bench|down]" >&2; exit 2 ;;
esac
