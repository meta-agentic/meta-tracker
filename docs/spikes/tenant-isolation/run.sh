#!/usr/bin/env bash
# Tenant isolation spike: RLS over the reactive pool. See ../tenant-isolation.md.
#
#   ./run.sh all          bootstrap, build, setup, probe, controls, templates, bypass, roles, bench (as recorded)
#   ./run.sh <command>    one of: bootstrap build setup probe control control-textual templates bypass roles bench
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
drop role if exists spike_migrator;
drop role if exists spike_definer;
-- Neither role is a superuser or has BYPASSRLS: a superuser is exempt from every policy.
create role spike_owner login password 'spike_owner' nosuperuser nobypassrls;
create role spike_app   login password 'spike_app'   nosuperuser nobypassrls;
create database tenancy_spike owner spike_owner;
-- For the role-design options only (./run.sh roles): roles that bypass RLS by attribute.
create role spike_migrator nologin nosuperuser bypassrls;
create role spike_definer  nologin nosuperuser bypassrls;
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

# What FORCE ROW LEVEL SECURITY does to the owner, and three ways to give legitimate
# cross-tenant work (seeding built-ins, data migrations, system jobs) its rows back. Each
# option runs in a transaction that is rolled back, so the schema is left as setup made it.
roles() {
  docker exec -i "$PG_CONTAINER" psql -q -U "$PG_SUPERUSER" -d tenancy_spike -tA -v ON_ERROR_STOP=0 2>&1 <<'SQL'
\echo '-- owner, FORCE on: seed a built-in, migrate data, run a SECURITY DEFINER function it owns'
begin;
set local role spike_owner;
create function count_items() returns bigint language sql security definer as 'select count(*) from item';
with u as (update item set title = title returning 1) select 'owner: data migration touches ' || count(*) || ' rows' from u;
select 'owner: definer function owned by the owner sees ' || count_items();
savepoint s;
insert into template_level (id, tenant_id, key, version) values (gen_random_uuid(), null, 'kanban', 1);
rollback to savepoint s;
rollback;
\echo '-- option 1: migrations run as a BYPASSRLS role'
begin;
grant select, insert, update on item, template_level to spike_migrator;
set local role spike_migrator;
with u as (update item set title = title returning 1) select 'migrator: data migration touches ' || count(*) || ' rows' from u;
insert into template_level (id, tenant_id, key, version) values (gen_random_uuid(), null, 'kanban', 1);
select 'migrator: built-in seeded, built-ins now ' || count(*) from template_level where tenant_id is null;
rollback;
\echo '-- option 2: FORCE kept, plus policies scoped to the owner role'
begin;
create policy owner_all on item to spike_owner using (true) with check (true);
create policy owner_all on template_level to spike_owner using (true) with check (true);
set local role spike_owner;
create function count_items() returns bigint language sql security definer as 'select count(*) from item';
with u as (update item set title = title returning 1) select 'owner + owner policy: data migration touches ' || count(*) || ' rows' from u;
select 'owner + owner policy: definer function sees ' || count_items();
insert into template_level (id, tenant_id, key, version) values (gen_random_uuid(), null, 'kanban', 1);
select 'owner + owner policy: built-in seeded, built-ins now ' || count(*) from template_level where tenant_id is null;
rollback;
\echo '-- option 3: system functions owned by a dedicated BYPASSRLS definer role; the owner stays forced'
begin;
create function count_items() returns bigint language sql security definer as 'select count(*) from item';
alter function count_items() owner to spike_definer;
grant select on item to spike_definer;
grant execute on function count_items() to spike_app;
set local role spike_app;
select 'app calling a definer-role function sees ' || count_items();
select 'app directly, unbound, sees ' || count(*) from item;
rollback;
SQL
}

down() {
  psql_super -c 'drop database if exists tenancy_spike' -c 'drop role if exists spike_app' \
             -c 'drop role if exists spike_owner' -c 'drop role if exists spike_migrator' \
             -c 'drop role if exists spike_definer' || true
  if [ "$PG_CONTAINER" = "$OWN_CONTAINER" ]; then docker rm -f "$OWN_CONTAINER" >/dev/null 2>&1 || true; fi
}

case "${1:-all}" in
  all)
    bootstrap; build
    echo "== setup";     run setup
    echo "== probe";     run probe
    echo "== control";   run control
    echo "== control-textual"; run control-textual
    echo "== templates"; run templates
    echo "== bypass";    bypass
    echo "== roles";     roles
    echo "== bench";     run bench
    ;;
  bootstrap) bootstrap ;;
  build) build ;;
  down) down ;;
  bypass) bypass ;;
  roles) roles ;;
  setup|probe|control|control-textual|templates|bench) run "$1" ;;
  *) echo "usage: $0 [all|bootstrap|build|setup|probe|control|control-textual|templates|bypass|roles|bench|down]" >&2; exit 2 ;;
esac
