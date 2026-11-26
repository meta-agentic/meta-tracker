#!/usr/bin/env bash
# Runs Vectis on the demo data in e2e/seed. One command brings up a throwaway PostgreSQL, builds
# the web client and the server, starts the server, loads the demo workspaces and leaves the
# server in the foreground; Ctrl-C stops it.
#
#   scripts/run-local.sh
#
# Needs Docker, JDK 25, Maven and Node (see CONTRIBUTING.md). Loading the demo data again
# replaces the demo workspaces and touches nothing else.
# Settings, all optional: VECTIS_PORT (8080), VECTIS_DB_PORT (5432), VECTIS_DB_PASSWORD
# (vectis, for the throwaway database only), VECTIS_LOCAL_DB (the container name,
# vectis-demo-db). To import a backlog kept as Markdown files as well, set
# VECTIS_IMPORT_VAULT_ROOT (and VECTIS_IMPORT_VAULT_SPACE): the server reads that directory
# and the script runs the import once the demo data is loaded.
# A port already in use stops the script before it creates anything, naming the setting to
# change. The web client's dependencies are installed again when web/package-lock.json is newer
# than the last install.
set -euo pipefail

cd "$(dirname "$0")/.."

port="${VECTIS_PORT:-8080}"
db_container="${VECTIS_LOCAL_DB:-vectis-demo-db}"
export VECTIS_DB_HOST=127.0.0.1
export VECTIS_DB_PORT="${VECTIS_DB_PORT:-5432}"
export VECTIS_DB_NAME=vectis
export VECTIS_DB_USER=vectis
export VECTIS_DB_PASSWORD="${VECTIS_DB_PASSWORD:-vectis}"
export QUARKUS_HTTP_PORT="$port"

# Something on this machine listens on port $1 (lsof, or a connection to it where lsof is missing
# or sees nothing, as for a port another user's process holds).
port_busy() {
  { command -v lsof >/dev/null && lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; } ||
    { command -v nc >/dev/null && nc -z -w 1 127.0.0.1 "$1" >/dev/null 2>&1; }
}

# Stops the script when port $1 is in use, naming the setting $2 that moves it and a free port.
need_free_port() {
  local free=$(($1 + 1))
  port_busy "$1" || return 0
  while port_busy "$free"; do free=$((free + 1)); done
  echo "port $1 is already in use; set $2 to a free port, for example: $2=$free scripts/run-local.sh" >&2
  exit 1
}

need_free_port "$port" VECTIS_PORT

echo "== database ($db_container, postgres:18-alpine, 127.0.0.1:$VECTIS_DB_PORT)"
state="$(docker inspect -f '{{.State.Status}}' "$db_container" 2>/dev/null || true)"
if [ "$state" = created ]; then
  # Left behind by a run that could not start it; it never held data.
  docker rm "$db_container" >/dev/null
  state=""
fi
case "$state" in
  "")
    need_free_port "$VECTIS_DB_PORT" VECTIS_DB_PORT
    if ! docker run -d --name "$db_container" \
      -e POSTGRES_USER="$VECTIS_DB_USER" -e POSTGRES_PASSWORD="$VECTIS_DB_PASSWORD" -e POSTGRES_DB="$VECTIS_DB_NAME" \
      -p "127.0.0.1:${VECTIS_DB_PORT}:5432" postgres:18-alpine >/dev/null; then
      docker rm -f "$db_container" >/dev/null 2>&1 || true
      echo "the database container did not start; nothing was left behind" >&2
      exit 1
    fi
    ;;
  running) ;;
  *)
    docker start "$db_container" >/dev/null || {
      echo "could not start the existing container $db_container (is its port in use?);" \
        "'docker rm $db_container' removes it and its data, or set VECTIS_LOCAL_DB to use another" >&2
      exit 1
    }
    ;;
esac
for _ in $(seq 60); do
  docker exec "$db_container" pg_isready -q -U "$VECTIS_DB_USER" && break
  sleep 1
done
docker exec "$db_container" pg_isready -q -U "$VECTIS_DB_USER" || { echo "the database did not start" >&2; exit 1; }

echo "== web client (demo workspace E2E)"
# npm writes node_modules/.package-lock.json on every install: a lockfile newer than it means the
# dependencies changed since, so a checkout that was installed before is installed again.
if [ ! -d web/node_modules ] || [ web/package-lock.json -nt web/node_modules/.package-lock.json ]; then
  echo "web/node_modules is missing or older than web/package-lock.json: npm ci"
  (cd web && npm ci)
fi
(cd web && VITE_VECTIS_WORKSPACE_KEY=E2E npm run build)

echo "== server"
mvn -B -ntp -q -pl vectis-server -am package -DskipTests
java -jar vectis-server/target/quarkus-app/quarkus-run.jar &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT INT TERM

for _ in $(seq 60); do
  curl -fsS -o /dev/null "http://localhost:$port/q/health/ready" 2>/dev/null && break
  kill -0 "$server" 2>/dev/null || { echo "the server stopped" >&2; exit 1; }
  sleep 1
done

# After the server is up: it creates the schema and the built-in templates at start.
echo "== demo data (e2e/seed)"
for seed in boards filter workspaces forms; do
  docker exec -i "$db_container" psql -q -v ON_ERROR_STOP=1 -U "$VECTIS_DB_USER" -d "$VECTIS_DB_NAME" \
    < "e2e/seed/$seed.sql"
done

if [ -n "${VECTIS_IMPORT_VAULT_ROOT:-}" ]; then
  echo "== import ($VECTIS_IMPORT_VAULT_ROOT)"
  curl -fsS -X POST "http://localhost:$port/api/v1/imports/vault"
  echo
fi

echo
echo "Vectis is showing the demo data at http://localhost:$port (Ctrl-C stops the server;"
echo "the database keeps its data, 'docker rm -f $db_container' removes it)."
wait "$server"
